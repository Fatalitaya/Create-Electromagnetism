//! Eliminate the passive trees of an unloaded bridge by KCL. Each tree edge carries
//! a signed sum of the six junction currents (conduction plus displacement).
use super::*;
use crate::model::rectifier::Rectifier;
use std::collections::HashMap;

type Currents = [f64; 6];
const HIGH: usize = 3;
const LOW: usize = 4;

struct Edge {
    branch: usize,
    parent: usize,
    child: usize,
    sign: f64,
    flow: Currents,
}

struct Junction {
    diode: usize,
    capacitor: usize,
    orientation: f64,
    model: Rectifier,
    farads: f64,
}

pub(super) struct Binding {
    shafts: [usize; 3],
    roots: [usize; 5],
    terminals: Vec<[usize; 2]>,
    neutral: usize,
    reference: usize,
    edges: Vec<Edge>,
    junctions: [Junction; 6],
    withdrawals: Vec<Currents>,
    coefficients: std::sync::Arc<Coefficients>,
}

struct Coefficients {
    resistance: [Currents; 6],
    inductance: [Currents; 6],
    shaft_inductance: [f64; 3],
}

fn dot(a: &Currents, b: &Currents) -> f64 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

impl Binding {
    pub(super) fn compile(c: &TransientCircuit) -> Option<Self> {
        let mut nodes = HashMap::new();
        let terminals: Vec<_> = c
            .branches
            .iter()
            .map(|b| {
                [b.positive, b.negative].map(|port| {
                    let next = nodes.len();
                    *nodes.entry(port).or_insert(next)
                })
            })
            .collect();
        let reference = nodes[&c.layout.references[0]];
        let mut tree = vec![Vec::new(); nodes.len()];
        let mut shafts = Vec::new();
        let mut diodes = Vec::new();
        let mut caps = HashMap::new();
        for (i, b) in c.branches.iter().enumerate() {
            let [a, z] = terminals[i];
            match b.element {
                Element::AcShaft { .. } => shafts.push(i),
                Element::Rectifier(m) => diodes.push((i, m)),
                Element::Capacitor { farads } => {
                    if caps.insert((a.min(z), a.max(z)), (i, farads)).is_some() {
                        return None;
                    }
                }
                Element::Inductor { .. }
                | Element::ThermalResistor(_)
                | Element::Resistor { .. } => {
                    tree[a].push((z, i, 1.0));
                    tree[z].push((a, i, -1.0));
                }
                _ => return None,
            }
        }
        let shafts: [usize; 3] = shafts.try_into().ok()?;
        let neutral = terminals[shafts[0]][1];
        if !tree[neutral].is_empty() {
            return None;
        }
        let mut roots = [0; 5];
        let mut group = vec![usize::MAX; nodes.len()];
        let mut edges = Vec::new();
        for g in 0..5 {
            let root = if g < 3 {
                terminals[shafts[g]][0]
            } else {
                let phase_end = usize::from(g == LOW);
                diodes
                    .iter()
                    .map(|(i, _)| terminals[*i])
                    .find(|p| group[p[phase_end]] == 0)?[1 - phase_end]
            };
            if group[root] != usize::MAX {
                return None;
            }
            roots[g] = root;
            group[root] = g;
            let mut stack = vec![(root, usize::MAX)];
            while let Some((parent, incoming)) = stack.pop() {
                for &(child, i, sign) in &tree[parent] {
                    if i == incoming {
                        continue;
                    }
                    if group[child] != usize::MAX {
                        return None;
                    }
                    group[child] = g;
                    edges.push(Edge {
                        branch: i,
                        parent,
                        child,
                        sign,
                        flow: [0.0; 6],
                    });
                    stack.push((child, i));
                }
            }
        }
        if group
            .iter()
            .enumerate()
            .any(|(i, g)| i != neutral && *g >= 5)
        {
            return None;
        }
        let mut ordered: [Option<Junction>; 6] = Default::default();
        let mut withdrawals = vec![[0.0; 6]; nodes.len()];
        for (diode, model) in diodes {
            let [a, z] = terminals[diode];
            let slot = match (group[a], group[z]) {
                (phase @ 0..=2, HIGH) => phase,
                (LOW, phase @ 0..=2) => 3 + phase,
                _ => return None,
            };
            let (capacitor, farads) = caps.remove(&(a.min(z), a.max(z)))?;
            let orientation = if terminals[capacitor][0] == a {
                1.0
            } else {
                -1.0
            };
            ordered[slot] = Some(Junction {
                diode,
                capacitor,
                orientation,
                model,
                farads,
            });
            withdrawals[a][slot] = 1.0;
            withdrawals[z][slot] = -1.0;
        }
        if !caps.is_empty() {
            return None;
        }
        let junctions = ordered
            .into_iter()
            .collect::<Option<Vec<_>>>()?
            .try_into()
            .ok()?;
        for edge in edges.iter_mut().rev() {
            for j in 0..6 {
                let subtree = withdrawals[edge.child][j];
                withdrawals[edge.parent][j] += subtree;
                edge.flow[j] = edge.sign * subtree;
            }
        }
        use crate::solver::coefficients::{Cache, Key};
        static CACHE: Cache<Coefficients> = Cache::new();
        let key = Key::new(&c.branches, &c.layout);
        let coefficients = if let Some(hit) = CACHE.get(&key) {
            hit
        } else {
            let mut fixed = Coefficients {
                resistance: [[0.0; 6]; 6],
                inductance: [[0.0; 6]; 6],
                shaft_inductance: [0.0; 3],
            };
            for edge in &edges {
                let (matrix, value) = match c.branches[edge.branch].element {
                    Element::Inductor { henries } => {
                        for p in 0..3 {
                            let flow = withdrawals[roots[p]];
                            if edge.flow == flow || edge.flow == flow.map(|a| -a) {
                                fixed.shaft_inductance[p] += henries;
                            }
                        }
                        (&mut fixed.inductance, henries)
                    }
                    Element::Resistor { ohms } => (&mut fixed.resistance, ohms),
                    Element::ThermalResistor(m) => (&mut fixed.resistance, m.ambient_ohms),
                    _ => return None,
                };
                for j in 0..6 {
                    for k in 0..6 {
                        matrix[j][k] += edge.flow[j] * value * edge.flow[k];
                    }
                }
            }
            CACHE.publish(key, fixed)
        };
        Some(Self {
            shafts,
            roots,
            terminals,
            neutral,
            reference,
            edges,
            junctions,
            withdrawals,
            coefficients,
        })
    }

    // A prescribed speed jump changes emf instantly while junction charge and winding
    // flux are continuous. Resolve the initial LC excursion before comparing endpoints.
    pub(super) fn initial_step_limit(&self, c: &TransientCircuit) -> f64 {
        let mut seconds = f64::INFINITY;
        for (phase, &i) in self.shafts.iter().enumerate() {
            let b = &c.branches[i];
            let emf = b
                .element
                .source_voltage(c.accepted.source_phases[&b.clock_id()])
                .unwrap();
            let old = c.accepted.operating_point.branches[i].voltage;
            if (emf - old).abs() > 0.01 + 0.01 * emf.abs() {
                let l = self.coefficients.shaft_inductance[phase];
                let capacitance = self.junctions[phase]
                    .farads
                    .min(self.junctions[3 + phase].farads);
                if l > 0.0 {
                    seconds = seconds.min(0.25 * l.sqrt() * capacitance.sqrt());
                }
            }
        }
        seconds
    }

    pub(super) async fn stages(
        &self,
        c: &TransientCircuit,
        old: &AcceptedState,
        end: f64,
        phases: &[Phases; 2],
    ) -> Option<[dc::DcOperatingPoint; 2]> {
        let h = end - old.time_seconds;
        let points = &old.operating_point.branches;
        // If W is the electrical energy, |i_phase| <= sqrt(2 W / L_phase).
        // Integrate the resulting bound on d(sqrt(W))/dt. All initial storage and
        // all additional source work could heat a single body between observations.
        let stored: f64 = c
            .branches
            .iter()
            .zip(points)
            .filter(|(b, _)| {
                matches!(
                    b.element,
                    Element::Inductor { .. } | Element::Capacitor { .. }
                )
            })
            .map(|(_, p)| p.stored_joules)
            .sum();
        let mut bound = stored.sqrt();
        for (p, &i) in self.shafts.iter().enumerate() {
            let Element::AcShaft { k, omega, .. } = c.branches[i].element else {
                return None;
            };
            let henries = self.coefficients.shaft_inductance[p];
            if henries <= 0.0 {
                return None;
            }
            bound += h * (k * omega).abs() / (2.0 * henries).sqrt();
        }
        if c.branches.iter().zip(points).any(|(b, p)| {
            b.element.thermal_node().is_some_and(|(m, row)| {
                m.trip_kelvin.is_some_and(|trip| {
                    bound * bound
                        >= (trip - m.ambient_kelvin - p.local_values[row])
                            * m.heat_capacity_joules_per_kelvin
                })
            })
        }) {
            return None;
        }

        let weights = radau::INVERSE.map(|row| row.map(|w| w / h));
        let mut nodes = vec![[0.0; 2]; self.withdrawals.len()];
        let mut currents = vec![[0.0; 2]; points.len()];
        let mut temperatures = vec![[0.0; 2]; points.len()];
        for (phase, &i) in self.shafts.iter().enumerate() {
            let b = &c.branches[i];
            for s in 0..2 {
                nodes[self.roots[phase]][s] = b.element.source_voltage(phases[s][&b.clock_id()])?;
            }
        }
        let voltage = |node| {
            let i = self
                .terminals
                .iter()
                .position(|pair| pair.contains(&node))?;
            Some(points[i].local_values[usize::from(self.terminals[i][1] == node)])
        };
        let mut x = [0.0; 16];
        for s in 0..2 {
            for (j, b) in self.junctions.iter().enumerate() {
                x[8 * s + j] = points[b.diode].voltage;
            }
            for g in [HIGH, LOW] {
                x[8 * s + g + 3] = voltage(self.roots[g])? - voltage(self.neutral)?;
            }
        }
        // d(junction drop at s)/d(total junction current at u), excluding thermal feedback.
        let mut fixed = [[[[0.0; 6]; 6]; 2]; 2];
        for s in 0..2 {
            for u in 0..2 {
                for j in 0..6 {
                    for k in 0..6 {
                        fixed[s][u][j][k] = weights[s][u] * self.coefficients.inductance[j][k];
                        if s == u {
                            fixed[s][u][j][k] += self.coefficients.resistance[j][k];
                        }
                    }
                }
            }
        }
        crate::solver::progress::newton_trial();
        for _ in 0..48 {
            crate::solver::cooperative::checkpoint().await;
            crate::solver::progress::newton_iteration();
            let mut total = [[0.0; 6]; 2];
            let mut slope = [[[0.0; 6]; 2]; 2];
            let mut impedance = fixed;
            for (j, b) in self.junctions.iter().enumerate() {
                let initial = b.orientation * points[b.capacitor].voltage;
                let mut power = [0.0; 2];
                for s in 0..2 {
                    let v = x[8 * s + j];
                    if v <= -0.98 * b.model.knee_volts {
                        return None;
                    }
                    let (amps, gain) = b.model.characteristic(v);
                    let mut displacement = 0.0;
                    for u in 0..2 {
                        let conductance = weights[s][u] * b.farads;
                        displacement += conductance * (x[8 * u + j] - initial);
                        slope[s][u][j] = conductance;
                    }
                    slope[s][s][j] += gain;
                    total[s][j] = amps + displacement;
                    currents[b.diode][s] = amps;
                    currents[b.capacitor][s] = b.orientation * displacement;
                    power[s] = v * amps;
                    nodes[self.roots[HIGH]][s] = x[8 * s + 6];
                    nodes[self.roots[LOW]][s] = x[8 * s + 7];
                }
                let m = b.model.thermal();
                temperatures[b.diode] = heat_stages(
                    h,
                    points[b.diode].local_values[3],
                    m.heat_capacity_joules_per_kelvin,
                    [m.conductance_watts_per_kelvin; 2],
                    power,
                )?
                .0;
            }
            for edge in &self.edges {
                let i = edge.branch;
                let amps = total.map(|stage| dot(&edge.flow, &stage));
                currents[i] = amps;
                let drop = match c.branches[i].element {
                    Element::Inductor { henries } => weights.map(|row| {
                        henries
                            * (row[0] * (amps[0] - points[i].current)
                                + row[1] * (amps[1] - points[i].current))
                    }),
                    Element::Resistor { ohms } => amps.map(|i| i * ohms),
                    Element::ThermalResistor(m) => {
                        let dr = m.ambient_ohms * m.coefficient_per_kelvin;
                        let (temp, response) = heat_stages(
                            h,
                            points[i].local_values[3],
                            m.heat_capacity_joules_per_kelvin,
                            amps.map(|i| m.conductance_watts_per_kelvin - dr * i * i),
                            amps.map(|i| m.ambient_ohms * i * i),
                        )?;
                        temperatures[i] = temp;
                        let resistance = temp.map(|t| m.ambient_ohms + dr * t);
                        if resistance.iter().any(|r| *r <= 0.0) {
                            return None;
                        }
                        for s in 0..2 {
                            for u in 0..2 {
                                let feedback = dr
                                    * (if s == u { temp[s] } else { 0.0 }
                                        + amps[s] * response[s][u] * 2.0 * amps[u] * resistance[u]);
                                if feedback == 0.0 {
                                    continue;
                                }
                                for (j, &a) in
                                    edge.flow.iter().enumerate().filter(|(_, a)| **a != 0.0)
                                {
                                    for (k, &b) in edge.flow.iter().enumerate() {
                                        impedance[s][u][j][k] += a * feedback * b;
                                    }
                                }
                            }
                        }
                        std::array::from_fn(|s| amps[s] * resistance[s])
                    }
                    _ => return None,
                };
                for s in 0..2 {
                    nodes[edge.child][s] = nodes[edge.parent][s] - edge.sign * drop[s];
                }
            }
            let mut residual = [0.0; 16];
            let mut jac = [0.0; 256];
            for s in 0..2 {
                for (j, b) in self.junctions.iter().enumerate() {
                    let [a, z] = self.terminals[b.diode];
                    let row = 8 * s + j;
                    let bus = 8 * s + 6 + j / 3;
                    residual[row] = x[row] - (nodes[a][s] - nodes[z][s]);
                    residual[bus] += total[s][j];
                    for u in 0..2 {
                        for k in 0..6 {
                            jac[row * 16 + 8 * u + k] = impedance[s][0][j][k] * slope[0][u][k]
                                + impedance[s][1][j][k] * slope[1][u][k];
                        }
                        jac[bus * 16 + 8 * u + j] = slope[s][u][j];
                    }
                    jac[row * 16 + row] += 1.0;
                    jac[row * 16 + bus] = if j < 3 { 1.0 } else { -1.0 };
                }
            }
            let dx = crate::solver::linear::solve(&jac, &residual.map(|r| -r)).ok()?;
            if residual
                .iter()
                .enumerate()
                .all(|(i, r)| r.abs() <= if i % 8 < 6 { 1e-10 } else { 1e-12 })
                && dx
                    .iter()
                    .zip(x)
                    .all(|(d, v)| d.abs() <= 1e-9 * (1.0 + v.abs()))
            {
                let mut stages = Vec::with_capacity(2);
                for s in 0..2 {
                    for (p, &i) in self.shafts.iter().enumerate() {
                        currents[i][s] = -dot(&self.withdrawals[self.roots[p]], &total[s]);
                    }
                    let mut values = vec![0.0; c.layout.size];
                    for (i, map) in c.layout.mappings.iter().enumerate() {
                        for (j, &node) in self.terminals[i].iter().enumerate() {
                            if let Some(row) = map[j] {
                                values[row] = nodes[node][s] - nodes[self.reference][s];
                            }
                        }
                        values[map[2]?] = currents[i][s];
                        if let Some((m, row)) = c.branches[i].element.thermal_node() {
                            let temp = temperatures[i][s];
                            if !temp.is_finite()
                                || m.trip_kelvin.is_some_and(|trip| {
                                    m.ambient_kelvin + temp.max(points[i].local_values[row])
                                        >= trip - 0.1
                                })
                            {
                                return None;
                            }
                            values[map[row]?] = temp;
                        }
                    }
                    let time = if s == 0 {
                        old.time_seconds + h / 3.0
                    } else {
                        end
                    };
                    stages.push(
                        dc::point(&c.branches, time, &c.layout, &values)
                            .await
                            .ok()?,
                    );
                }
                return stages.try_into().ok();
            }
            let mut scale = 1.0_f64;
            for i in (0..16).filter(|i| i % 8 < 6 && dx[*i] > 0.25) {
                scale = scale.min((0.25 - x[i]).max(0.25) / dx[i]);
            }
            for (v, d) in x.iter_mut().zip(dx) {
                *v += scale * d;
            }
        }
        None
    }
}

// Eliminate both thermal stage increments. The inverse also gives dT/dP for
// differentiating the winding resistance in the electrical Newton matrix.
fn heat_stages(
    h: f64,
    initial: f64,
    capacity: f64,
    loss: [f64; 2],
    power: [f64; 2],
) -> Option<([f64; 2], [[f64; 2]; 2])> {
    let scale = h / capacity;
    let a = radau::INVERSE[0][0] + scale * loss[0];
    let b = radau::INVERSE[0][1];
    let c = radau::INVERSE[1][0];
    let d = radau::INVERSE[1][1] + scale * loss[1];
    let det = a * d - b * c;
    if !det.is_finite() || det <= 0.0 {
        return None;
    }
    let response = [[d, -b], [-c, a]].map(|r| r.map(|v| v * scale / det));
    let rhs: [f64; 2] = std::array::from_fn(|s| power[s] - loss[s] * initial);
    Some((
        response.map(|r| initial + r[0] * rhs[0] + r[1] * rhs[1]),
        response,
    ))
}
