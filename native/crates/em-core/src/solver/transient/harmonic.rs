//! Large-signal periodic response of a single prescribed AC shaft. The cache
//! belongs to an immutable set of branch coefficients, never to a checkpoint.
use super::*;
use crate::solver::{SolveError, ac::Phasor, cooperative::checkpoint, sparse::Matrix};
use std::f64::consts::{PI, TAU};

mod mixed;
mod wave;
use wave::Power;

struct BranchWave {
    local: Vec<Phasor>,
    voltage: Phasor,
    power: Power,
    loss: Power,
    gain: Option<f64>,
}

pub(crate) struct Plan {
    first_time: f64,
    retry_after: std::sync::Mutex<f64>,
    reference: Response,
    initial_energy: EnergyLedger,
    admissible: std::sync::OnceLock<bool>,
}

struct Response {
    clock: em_protocol::contract::Id,
    omega: f64,
    direction: f64,
    values: Vec<Phasor>,
    waves: Vec<BranchWave>,
    shaft_power_bound: Option<f64>,
}

impl Response {
    // Along a degree-two series path the shaft current also flows through
    // every two-terminal resistor. With at least R ohms in that path,
    // Vs*i <= Vs_peak^2/(2R) + R*i^2/2 bounds total Joule heat in a step.
    fn series_resistance(c: &TransientCircuit, source: usize) -> Option<f64> {
        let mut contacts = std::collections::HashMap::new();
        for (i, b) in c.branches.iter().enumerate() {
            for port in b.ports() {
                contacts.entry(port).or_insert_with(Vec::new).push(i);
            }
        }
        let mut floor = 0.0_f64;
        for mut port in c.branches[source].ports() {
            let mut last = source;
            for _ in 0..c.branches.len() {
                let incident = &contacts[&port];
                if incident.len() != 2 {
                    break;
                }
                let i = if incident[0] == last {
                    incident[1]
                } else {
                    incident[0]
                };
                if i == source {
                    break;
                }
                let b = &c.branches[i];
                match b.element {
                    Element::ThermalResistor(m) => floor = floor.max(m.ambient_ohms),
                    Element::Resistor { ohms } => floor = floor.max(ohms),
                    Element::Inductor { .. } | Element::Capacitor { .. } => {}
                    _ => break,
                }
                port = if port == b.positive {
                    b.negative
                } else {
                    b.positive
                };
                last = i;
            }
        }
        (floor > 0.).then_some(floor)
    }
    async fn compile(circuit: &TransientCircuit) -> Result<Option<Self>, SolveError> {
        let mut source = None;
        for (i, branch) in circuit.branches.iter().enumerate() {
            match branch.element {
                Element::AcShaft {
                    omega, pole_pairs, ..
                } if source.is_none() && omega * pole_pairs != 0. => {
                    source = Some((i, omega * pole_pairs));
                }
                Element::Resistor { .. }
                | Element::Meter { .. }
                | Element::Capacitor { .. }
                | Element::Inductor { .. }
                | Element::Windings(_)
                | Element::Regulator(_) => {}
                Element::ThermalResistor(_) | Element::TappedWire(_) => {}
                _ => return Ok(None),
            }
        }
        let Some((source, signed_omega)) = source else {
            return Ok(None);
        };
        let direction = signed_omega.signum();
        let omega = signed_omega.abs();
        let n = circuit.layout.size;
        // Symbolic ordering is synchronous. Larger eligible circuits continue
        // through the budget-aware general solver.
        if n > 256 {
            return Ok(None);
        }
        let mut matrix = Matrix::new(2 * n)?;
        let mut rhs = vec![0.; 2 * n];
        let mut gains = Vec::new();
        for (i, branch) in circuit.branches.iter().enumerate() {
            checkpoint().await;
            let map = &circuit.layout.mappings[i];
            let mut bias = vec![0.; map.len()];
            let mut gain = None;
            if let Element::Regulator(m) = branch.element {
                let detector = circuit.accepted.operating_point.branches[i].local_values[5];
                bias[5] = detector;
                gain = Some((m.target / detector.max(1e-12).sqrt()).clamp(m.minimum, m.maximum));
            }
            if let Some((_, row)) = branch.element.thermal_node() {
                bias[row] = circuit.accepted.operating_point.branches[i].local_values[row];
            }
            gains.push(gain);
            let e = branch.element.evaluate(&bias, 0.)?;
            for (lr, row) in map.iter().enumerate() {
                let Some(r) = *row else { continue };
                for (lc, col) in map.iter().enumerate() {
                    let Some(c) = *col else { continue };
                    let j = lr * map.len() + lc;
                    let f = e.residual_jacobian[j];
                    let q = omega * e.charge_jacobian[j];
                    if f != 0. {
                        matrix.add(r, c, f)?;
                        matrix.add(r + n, c + n, f)?;
                    }
                    if q != 0. {
                        matrix.add(r, c + n, -q)?;
                        matrix.add(r + n, c, q)?;
                    }
                }
            }
            if i == source {
                let r = map[2].expect("shaft voltage equation");
                rhs[r] = branch.element.source_voltage(0.).unwrap();
                rhs[r + n] = -branch.element.source_voltage(direction * PI / 2.).unwrap();
            }
        }
        let symbolic = matrix.analyze()?;
        let x = matrix.solve_symbolic_async(&rhs, &symbolic).await?;
        let values: Vec<_> = (0..n)
            .map(|i| Phasor {
                real: x[i],
                imaginary: x[i + n],
            })
            .collect();
        let mut waves = Vec::new();
        for (i, b) in circuit.branches.iter().enumerate() {
            checkpoint().await;
            let local: Vec<_> = circuit.layout.mappings[i]
                .iter()
                .map(|r| r.map_or(Phasor::default(), |j| values[j]))
                .collect();
            let voltage = local[0].minus(local[1]);
            let mut power = Power::product(voltage, local[2]);
            for pair in local[3..2 * b.element.port_count() - 1].chunks_exact(2) {
                power.add(Power::product(pair[0].minus(local[1]), pair[1]));
            }
            // Copper losses are homogeneous quadratic functions of the terminal values.
            let loss_at = |phase| {
                b.element
                    .evaluate(&local.iter().map(|v| v.at(phase)).collect::<Vec<_>>(), 0.)
                    .map(|e| e.dissipated_watts)
            };
            let p = loss_at(0.)?;
            let q = loss_at(PI / 2.)?;
            let dc = (p + q) * 0.5;
            let loss = Power {
                dc,
                cos: (p - q) * 0.5,
                sin: loss_at(PI / 4.)? - dc,
            };
            waves.push(BranchWave {
                local,
                voltage,
                power,
                loss,
                gain: gains[i],
            });
        }
        let shaft_power_bound = Self::series_resistance(circuit, source)
            .map(|ohms| waves[source].voltage.peak().powi(2) / ohms);
        Ok(Some(Self {
            clock: circuit.branches[source].clock_id(),
            omega,
            direction,
            values,
            waves,
            shaft_power_bound,
        }))
    }

    fn ready(&self, circuit: &TransientCircuit) -> bool {
        let state = &circuit.accepted;

        let phase = self.direction * state.source_phases[&self.clock];
        self.waves
            .iter()
            .zip(&state.operating_point.branches)
            .zip(circuit.branches.iter())
            .all(|((w, p), b)| {
                let count = 2 * b.element.port_count() - 1;
                (0..count).all(|i| {
                    let current = i >= 2 && i % 2 == 0;
                    let absolute = if current { 1e-7 } else { 1e-4 };
                    let delta = (w.local[i].at(phase) - p.local_values[i]).abs();
                    let peak = w.local[i].peak();

                    delta <= absolute + 0.006 * peak
                })
            })
    }

    async fn trial(
        &self,
        c: &TransientCircuit,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<Option<Trial>, SolveError> {
        let old = &c.accepted;
        let dt = end - old.time_seconds;
        let a = self.direction * old.source_phases[&self.clock];
        let b = a + self.omega * dt;
        if !(b > a && b - a <= TAU) {
            return Ok(None);
        }
        let mut candidate = old.clone();
        let mut x: Vec<_> = self.values.iter().map(|p| p.at(b)).collect();
        for (i, (branch, w)) in c.branches.iter().zip(&self.waves).enumerate() {
            checkpoint().await;
            let old_point = &old.operating_point.branches[i];
            let v = w.voltage;
            let current = w.local[2];
            let square = Power::product(v, v);
            if let Element::Regulator(m) = branch.element {
                let prev = old_point.local_values[5];
                let detector = square.filtered(prev, a, b, self.omega, m.seconds);
                let gain = |d: f64| (m.target / d.max(1e-12).sqrt()).clamp(m.minimum, m.maximum);
                let change = -(-dt / m.seconds).exp_m1();
                let low = prev * (1. - change);
                let high = prev + (v.peak().powi(2) - prev).max(0.) * change;
                if detector < 0. || [prev, low, high].iter().any(|&d| Some(gain(d)) != w.gain) {
                    return Ok(None);
                }
                x[c.layout.mappings[i][5].unwrap()] = detector;
            }
            let observed = if matches!(branch.element, Element::Meter { .. }) {
                Power::product(v, current)
            } else {
                w.power
            };
            let (absorbed, returned) = observed.positive_negative(a, b, self.omega);
            let mut loss = w.loss.integral(a, b, self.omega).max(0.);
            if let Some((m, row)) = branch.element.thermal_node() {
                if m.coefficient_per_kelvin != 0. {
                    return Ok(None);
                }
                let prev = old_point.local_values[row];
                let capacity = m.heat_capacity_joules_per_kelvin;
                let conductance = m.conductance_watts_per_kelvin;
                let upper =
                    prev + dt * (w.power.dc + w.power.cos.hypot(w.power.sin)).max(0.) / capacity;
                if m.trip_kelvin.is_some_and(|t| m.ambient_kelvin + upper >= t) {
                    return Ok(None);
                }
                let temp =
                    w.power
                        .filtered(prev * conductance, a, b, self.omega, capacity / conductance)
                        / conductance;
                if !temp.is_finite() || temp < 0. {
                    return Ok(None);
                }
                x[c.layout.mappings[i][row].unwrap()] = temp;
                loss = (w.power.integral(a, b, self.omega) - capacity * (temp - prev)).max(0.);
            }
            let m = &mut candidate.measurements.branches[i];
            m.voltage_seconds += v.integral(a, b, self.omega);
            m.current_seconds += current.integral(a, b, self.omega);
            m.voltage_squared_seconds += square.integral(a, b, self.omega).max(0.);
            m.current_squared_seconds += Power::product(current, current)
                .integral(a, b, self.omega)
                .max(0.);
            m.peak_voltage = m.peak_voltage.max(v.window_peak(a, b));
            m.peak_current = m.peak_current.max(current.window_peak(a, b));
            m.absorbed_joules += absorbed;
            m.returned_joules += returned;
            m.dissipated_joules += loss;
            m.total_absorbed_joules += absorbed;
            m.total_returned_joules += returned;
            m.total_dissipated_joules += loss;
            let slices = ((b - a) * 32. / TAU).ceil().max(1.) as usize;
            for j in 0..slices {
                let step = (b - a) / slices as f64;
                m.periodicity.integrate(
                    v.at(a + step * j as f64),
                    v.at(a + step * (j + 1) as f64),
                    dt / slices as f64,
                )?;
            }
            if let Element::AcShaft { omega, .. } = branch.element {
                m.torque_seconds += (absorbed - returned) / omega;
                candidate.energy.source_supplied_joules += returned;
                candidate.energy.source_absorbed_joules += absorbed;
            }
            if !m.torque_seconds.is_finite() || m.fields().iter().any(|v| !v.is_finite()) {
                return Ok(None);
            }
            candidate.energy.dissipated_joules += loss;
        }
        candidate.operating_point = dc::point(&c.branches, end, &c.layout, &x).await?;
        candidate.energy.stored_joules = candidate
            .operating_point
            .branches
            .iter()
            .map(|p| p.stored_joules)
            .sum();
        let drift =
            candidate.energy.balance_residual_joules() - old.energy.balance_residual_joules();
        let storage: f64 = candidate
            .operating_point
            .branches
            .iter()
            .zip(c.branches.iter())
            .filter(|(_, b)| b.element.thermal_node().is_none())
            .map(|(p, _)| p.stored_joules)
            .sum();
        if !candidate.energy.is_finite()
            || drift.abs() > accuracy.energy + accuracy.relative * storage
        {
            return Ok(None);
        }
        // Dropping a decaying natural mode can release a small amount of
        // stored energy. Account for the loss as numerical damping; reject
        // projections that would require an unmodelled supply of energy.
        if drift < -1e-12 {
            return Ok(None);
        }
        candidate.energy.numerical_damping_joules += drift.max(0.);
        candidate.time_seconds = end;
        candidate
            .source_phases
            .insert(self.clock, (self.direction * b).rem_euclid(TAU));
        Ok(Some(Trial::single(candidate, 0.)))
    }
}

impl Plan {
    fn due(&self, c: &TransientCircuit) -> bool {
        let now = c.accepted.time_seconds;
        now - self.first_time >= TAU / self.reference.omega
            && *self.admissible.get_or_init(|| {
                // A periodic trajectory removes backward Euler damping. Keep
                // the original transient trajectory when that would materially
                // change the mechanical work reported by a lightly loaded shaft.
                let e = c.accepted.energy;
                let damping =
                    e.numerical_damping_joules - self.initial_energy.numerical_damping_joules;
                let net = (e.source_supplied_joules - self.initial_energy.source_supplied_joules)
                    - (e.source_absorbed_joules - self.initial_energy.source_absorbed_joules);
                damping <= 0.007 * net.abs()
            })
            && now >= *self.retry_after.lock().unwrap()
    }

    // Fixed-ratio operation is exact while the RMS detector stays clamped.
    // Interior gain modulation and temperature-dependent coefficients use the
    // checked time-domain trajectory, which preserves their endpoint constraints.
    async fn candidate(
        &self,
        c: &TransientCircuit,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<Option<Trial>, SolveError> {
        if !self.reference.ready(c) {
            return Ok(None);
        }
        self.reference.trial(c, end, accuracy).await
    }
}

impl TransientCircuit {
    pub(crate) fn harmonic_ready(&self) -> bool {
        if !self.game_interval {
            return false;
        }
        self.harmonic
            .get()
            .and_then(Option::as_ref)
            .is_some_and(|plan| plan.due(self))
    }

    pub(super) async fn harmonic_trial(
        &self,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<Option<Trial>, SolveError> {
        if !self.game_interval || !accuracy.gameplay() || !self.accepted.sampler.channels.is_empty()
        {
            return Ok(None);
        }
        if self.harmonic.get().is_none() {
            let plan = Response::compile(self)
                .await
                .ok()
                .flatten()
                .map(|reference| Plan {
                    first_time: self.accepted.time_seconds,
                    retry_after: Default::default(),
                    reference,
                    initial_energy: self.accepted.energy,
                    admissible: Default::default(),
                });
            let _ = self.harmonic.set(plan);
        }
        if let Some(plan) = self.harmonic.get().and_then(Option::as_ref) {
            if plan.due(self) {
                if let Ok(Some(trial)) = plan.candidate(self, end, accuracy).await {
                    super::super::progress::harmonic_trial();
                    return Ok(Some(trial));
                }
                if let Ok(Some(trial)) = plan.checked_transient(self, end).await {
                    super::super::progress::mixed_interval();
                    return Ok(Some(trial));
                }
                super::super::progress::periodic_fallback();
                *plan.retry_after.lock().unwrap() = self.accepted.time_seconds + 0.05;
                if end - self.accepted.time_seconds > self.source_step_limit() {
                    return Err(rejected("Periodic candidate requires transient resolution").into());
                }
            }
        }
        Ok(None)
    }
}
