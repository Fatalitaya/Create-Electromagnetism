//! Fixed linear DAE Gx + Qx' = b. Cache the two-stage transition factors for the
//! actual interval length. Only the RHS depends on accepted fluxes and charges.
use super::*;
use crate::solver::{
    SolveError,
    coefficients::{Cache, Key},
    cooperative::checkpoint,
    sparse::{Matrix, PreparedMatrix, Symbolic},
};
use std::sync::{Arc, Mutex};

const BUTCHER: [[f64; 2]; 2] = [[5.0 / 12.0, -1.0 / 12.0], [0.75, 0.25]];

struct Transition {
    symbolic: Symbolic,
    matrix: PreparedMatrix,
}

pub(crate) struct Plan {
    stamps: Vec<(usize, usize, f64, f64)>,
    forcing: Vec<f64>,
    // Capacitor voltage increments are h*A*i/C in the two original stages.
    // Coefficients for this term have units of inverse farads.
    inverse_farads: Vec<(usize, f64)>,
    segment_currents: Vec<Option<usize>>,
    windows: Mutex<Vec<(u64, Arc<Transition>)>>,
}

impl TransientCircuit {
    pub(super) async fn linear_storage_plan(&self) -> Result<Option<&Arc<Plan>>, SolveError> {
        if let Some(plan) = self.linear_plan.get() {
            return Ok(plan.as_ref());
        }
        let supported = self.layout.size <= 128
            && self.branches.iter().all(|b| {
                matches!(
                    b.element,
                    Element::Resistor { .. }
                        | Element::DcVoltageSource { .. }
                        | Element::Capacitor { .. }
                        | Element::Inductor { .. }
                        | Element::Windings(_)
                        | Element::Meter { .. }
                )
            })
            && self.branches.iter().any(|b| {
                matches!(
                    b.element,
                    Element::Inductor { .. } | Element::Windings(_) | Element::Capacitor { .. }
                )
            });
        let plan = if supported {
            static CACHE: Cache<Plan> = Cache::new();
            let key = Key::new(&self.branches, &self.layout);
            Some(if let Some(plan) = CACHE.get(&key) {
                plan
            } else {
                // A reused two-stage factor already handles a lone winding
                // return. Expanding both stages costs more than that single slot.
                let ports = self.layout.series.as_ref().filter(|p| !p.paths.is_empty());
                let coordinates = ports.map_or(&self.layout.coordinates, |p| &p.coordinates);
                let mut forcing = vec![0.0; coordinates.size];
                let mut segment_currents = vec![None; self.branches.len()];
                let mut inverse_farads = Vec::new();
                let mut stamps = Vec::new();
                for (i, (b, map)) in self.branches.iter().zip(&coordinates.mappings).enumerate() {
                    checkpoint().await;
                    if map.is_empty() {
                        continue;
                    }
                    if let Some((ports, j)) = ports.and_then(|p| p.path_for[i].map(|j| (p, j))) {
                        let path = &ports.paths[j];
                        let current = map[2].unwrap();
                        for (node, sign) in map[..2].iter().zip([1.0, -1.0]) {
                            if let Some(node) = *node {
                                stamps.push((node, current, sign, 0.0));
                                stamps.push((current, node, sign, 0.0));
                            }
                        }
                        let (mut ohms, mut henries, mut reciprocal) = (0.0, 0.0, 0.0);
                        for segment in &path.segments {
                            segment_currents[segment.branch] = Some(current);
                            match self.branches[segment.branch].element {
                                Element::Resistor { ohms: r } => ohms += r,
                                Element::Inductor { henries: l } => henries += l,
                                Element::Capacitor { farads } => reciprocal += 1.0 / farads,
                                Element::DcVoltageSource { volts } => {
                                    forcing[current] += segment.sign * volts
                                }
                                _ => unreachable!("linear storage admission"),
                            }
                        }
                        stamps.push((current, current, -ohms, -henries));
                        if reciprocal != 0.0 {
                            inverse_farads.push((current, reciprocal));
                        }
                        continue;
                    }
                    let mut e = b.element.evaluate(&vec![0.0; map.len()], 0.0)?;
                    if let Some(p) = ports {
                        p.project(i, &mut e);
                    }
                    for (i, row) in map.iter().enumerate() {
                        let Some(r) = *row else { continue };
                        forcing[r] -= e.residual[i];
                        for (j, col) in map.iter().enumerate() {
                            let Some(c) = *col else { continue };
                            let k = i * map.len() + j;
                            let (g, q) = (e.residual_jacobian[k], e.charge_jacobian[k]);
                            if g != 0.0 || q != 0.0 {
                                stamps.push((r, c, g, q));
                            }
                        }
                    }
                }
                CACHE.publish(
                    key,
                    Plan {
                        stamps,
                        forcing,
                        inverse_farads,
                        segment_currents,
                        windows: Default::default(),
                    },
                )
            })
        } else {
            None
        };
        let _ = self.linear_plan.set(plan);
        Ok(self.linear_plan.get().and_then(Option::as_ref))
    }
}

impl Plan {
    async fn transition(&self, h: f64) -> Result<Arc<Transition>, SolveError> {
        let key = h.to_bits();
        let lookup = |windows: &Vec<(u64, Arc<Transition>)>| {
            windows
                .iter()
                .find(|entry| entry.0 == key)
                .map(|entry| entry.1.clone())
        };
        if let Some(hit) = lookup(&self.windows.lock().unwrap()) {
            return Ok(hit);
        }
        let n = self.forcing.len();
        let mut matrix = Matrix::new(2 * n)?;
        for &(r, c, g, q) in &self.stamps {
            checkpoint().await;
            for (stage, weights) in super::radau::INVERSE.iter().enumerate() {
                for (other, weight) in weights.iter().enumerate() {
                    let value = (if stage == other { g } else { 0.0 }) + weight * q / h;
                    if value != 0.0 {
                        matrix.add_solver(stage * n + r, other * n + c, value)?;
                    }
                }
            }
        }
        for &(r, reciprocal) in &self.inverse_farads {
            for (s, weights) in BUTCHER.iter().enumerate() {
                for (j, &weight) in weights.iter().enumerate() {
                    matrix.add_solver(s * n + r, j * n + r, -weight * h * reciprocal)?;
                }
            }
        }
        let symbolic = matrix.analyze()?;
        let matrix = PreparedMatrix::new(matrix, &symbolic).await?;
        let mut windows = self.windows.lock().unwrap();
        if let Some(hit) = lookup(&windows) {
            return Ok(hit);
        }
        if windows.len() >= 4 {
            windows.remove(0);
        }
        let transition = Arc::new(Transition { symbolic, matrix });
        windows.push((key, transition.clone()));
        Ok(transition)
    }

    pub(super) async fn stages(
        &self,
        c: &TransientCircuit,
        seed: &AcceptedState,
        h: f64,
    ) -> Result<Vec<f64>, SolveError> {
        let n = self.forcing.len();
        let mut initial = vec![0.0; n];
        let ports = c.layout.series.as_ref().filter(|p| !p.paths.is_empty());
        let coordinates = ports.map_or(&c.layout.coordinates, |p| &p.coordinates);
        let mut rhs = self.forcing.repeat(2);
        for (i, (map, point)) in coordinates
            .mappings
            .iter()
            .zip(&seed.operating_point.branches)
            .enumerate()
        {
            if let Some((p, j)) = ports.and_then(|p| p.path_for[i].map(|j| (p, j))) {
                let path = &p.paths[j];
                let r = map[2].unwrap();
                // Use each accepted inductor current independently. KCL need only
                // hold at the new stages following a change of topology.
                for seg in &path.segments {
                    let old = &seed.operating_point.branches[seg.branch];
                    for (s, weights) in super::radau::INVERSE.iter().enumerate() {
                        rhs[s * n + r] += seg.sign
                            * match c.branches[seg.branch].element {
                                Element::Inductor { henries } => {
                                    -weights.iter().sum::<f64>() * henries * old.current / h
                                }
                                Element::Capacitor { .. } => old.voltage,
                                _ => 0.0,
                            };
                    }
                }
                continue;
            }
            for (row, &value) in map.iter().zip(&point.local_values) {
                if let Some(r) = row {
                    initial[*r] = value;
                }
            }
        }
        let mut charge = vec![0.0; n];
        for &(r, col, _, q) in &self.stamps {
            charge[r] += q * initial[col];
        }
        for (stage, weights) in super::radau::INVERSE.iter().enumerate() {
            for (r, q) in charge.iter().enumerate() {
                rhs[stage * n + r] += weights.iter().sum::<f64>() * q / h;
            }
        }
        let transition = self.transition(h).await?;
        let x = transition.matrix.solve(&rhs, &transition.symbolic).await?;
        let Some(ports) = ports else {
            return Ok(x);
        };
        let mut full = Vec::with_capacity(c.layout.size * 2);
        for s in 0..2 {
            let stage = &x[s * n..(s + 1) * n];
            let recovered = ports
                .recover(stage, &c.layout.mappings, |segment, amps| {
                    let i = segment.branch;
                    let old = &seed.operating_point.branches[i];
                    match c.branches[i].element {
                        Element::Resistor { ohms } => ohms * amps,
                        Element::DcVoltageSource { volts } => volts,
                        ref element => {
                            let r = self.segment_currents[i].expect("series current row");
                            let currents = [segment.sign * x[r], segment.sign * x[n + r]];
                            match *element {
                                Element::Inductor { henries } => {
                                    henries
                                        * super::radau::INVERSE[s]
                                            .iter()
                                            .zip(currents)
                                            .map(|(w, a)| w * (a - old.current))
                                            .sum::<f64>()
                                        / h
                                }
                                Element::Capacitor { farads } => {
                                    old.voltage
                                        + h * BUTCHER[s]
                                            .iter()
                                            .zip(currents)
                                            .map(|(w, a)| w * a)
                                            .sum::<f64>()
                                            / farads
                                }
                                _ => unreachable!("linear storage admission"),
                            }
                        }
                    }
                })
                .await
                .ok_or_else(|| rejected("Series voltage recovery lost precision"))?;
            full.extend(recovered);
        }
        Ok(full)
    }
}
