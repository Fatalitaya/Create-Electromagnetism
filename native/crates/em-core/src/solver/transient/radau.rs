use super::*;
use crate::solver::{SolveError, cooperative::checkpoint, progress, sparse::Matrix};

// A⁻¹ converts stage charge increments to time derivatives.
pub(super) const INVERSE: [[f64; 2]; 2] = [[1.5, 0.5], [-4.5, 2.5]];

// Standalone replays hold the integration and error-control policy fixed across tolerances.
pub(super) fn reference_replay() -> bool {
    static REFERENCE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *REFERENCE.get_or_init(|| std::env::var("EM_BRIDGE_REFERENCE").as_deref() == Ok("1"))
}

pub(super) fn enabled(open_bridge: bool) -> bool {
    static OVERRIDE: std::sync::OnceLock<Option<bool>> = std::sync::OnceLock::new();
    OVERRIDE.get_or_init(|| std::env::var_os("EM_RADAU").map(|s| s == "1")).unwrap_or(open_bridge)
}

/// Match an unloaded six-pulse bridge after collapsing its winding and wire impedances.
/// Only capacitances across the six junctions are part of this topology.
pub(super) fn is_open_bridge(branches: &[DcBranch]) -> bool {
    use em_protocol::contract::PortId;
    use std::collections::{HashMap, HashSet};
    let shafts: Vec<_> = branches.iter().filter(|b| matches!(b.element, Element::AcShaft { .. })).collect();
    if shafts.len() != 3 ||
        shafts.iter().any(|s| s.clock_id() != shafts[0].clock_id() || s.negative != shafts[0].negative) {
        return false;
    }
    let mut links = HashMap::<PortId, Vec<PortId>>::new();
    let mut junctions = Vec::new();
    let mut capacitances = Vec::new();
    for b in branches {
        let (a, z) = (b.positive, b.negative);
        links.entry(a).or_default();
        links.entry(z).or_default();
        match b.element {
            Element::AcShaft { .. } => {}
            Element::Rectifier(_) => junctions.push((a, z)),
            Element::Capacitor { .. } => capacitances.push((a, z)),
            Element::Inductor { .. } | Element::Resistor { .. } | Element::ThermalResistor(_) => {
                links.get_mut(&a).unwrap().push(z);
                links.get_mut(&z).unwrap().push(a);
            }
            _ => return false,
        }
    }
    if junctions.len() != 6 || capacitances.iter().any(|&(a,z)|
        !junctions.contains(&(a,z)) && !junctions.contains(&(z,a))) {
        return false;
    }
    let mut group = HashMap::new();
    for &root in links.keys() {
        if group.contains_key(&root) { continue; }
        group.insert(root, root);
        let mut pending = vec![root];
        while let Some(port) = pending.pop() {
            for &next in &links[&port] {
                if group.insert(next, root).is_none() { pending.push(next); }
            }
        }
    }
    let edges: Vec<_> = junctions.into_iter().map(|(a,z)| (group[&a], group[&z])).collect();
    let phases: Vec<_> = shafts.iter().map(|s| group[&s.positive]).collect();
    let Some(&(_, positive)) = edges.iter().find(|(a,_)| *a == phases[0]) else { return false };
    let Some(&(negative, _)) = edges.iter().find(|(_,z)| *z == phases[0]) else { return false };
    let neutral = group[&shafts[0].negative];
    let distinct: HashSet<_> = phases.iter().copied().chain([positive, negative, neutral]).collect();
    distinct.len() == 6 && phases.iter().all(|&phase|
        edges.contains(&(phase, positive)) && edges.contains(&(negative, phase)))
}

impl TransientCircuit {
    pub(super) async fn radau_pair(
        &self,
        end: f64,
        middle: f64,
        accuracy: Accuracy,
    ) -> Result<Trial, SolveError> {
        static REDUCED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
        if !reference_replay()
            && *REDUCED.get_or_init(|| std::env::var("EM_BRIDGE_REDUCED").as_deref() == Ok("1"))
            && let Some(bridge) = self.plans.get().and_then(|p| p.bridge.as_ref())
        {
            match self.radau_trajectories(end, middle, accuracy, Some(bridge)).await {
                Ok(trial) => return Ok(trial),
                Err(error) if error.retryable() => progress::radau_fallback(),
                Err(error) => return Err(error),
            }
        }
        // Discard both candidate trajectories on fallback, including any completed half step.
        self.radau_trajectories(end, middle, accuracy, None).await
    }

    async fn radau_trajectories(
        &self,
        end: f64,
        middle: f64,
        accuracy: Accuracy,
        bridge: Option<&bridge::Binding>,
    ) -> Result<Trial, SolveError> {
        let (full, (first, half, third, fine)) = crate::solver::cooperative::trajectories(
            true,
            self.radau_step(&self.accepted, end, bridge),
            async {
                let (first, half) = self.radau_protected_step(&self.accepted, middle, bridge).await?;
                let (third, fine) = self.radau_protected_step(&half, end, bridge).await?;
                Ok((first, half, third, fine))
            },
        )
        .await?;
        let error = self
            .refinement_error(&self.accepted, &full.1, &fine, accuracy, true)
            .await?;
        let error = if accuracy.gameplay() {
            error.max(self.gameplay_window_error(&self.accepted, &full.1, &fine, accuracy.energy))
        } else { error };
        let intermediate = first.into_iter().chain([half]).chain(third).collect();
        Ok(Trial {
            end: fine,
            intermediate,
            error,
            recovered_with_quarters: false,
        })
    }

    async fn radau_protected_step(
        &self,
        seed: &AcceptedState,
        end: f64,
        bridge: Option<&bridge::Binding>,
    ) -> Result<(Option<AcceptedState>, AcceptedState), SolveError> {
        let (stage_crossing, endpoint) = self.radau_step(seed, end, bridge).await?;
        let end_crossing = endpoint
            .operating_point
            .branches
            .iter()
            .enumerate()
            .any(|(i, p)| self.protection_triggered(i, p));
        let prefix = if stage_crossing || end_crossing {
            // Only a completed step has a consistent energy and observation ledger for the upper
            // protection bracket.
            let stage_time = seed.time_seconds + (end - seed.time_seconds) / 3.0;
            let (_, prefix) = self.radau_step(seed, stage_time, bridge).await?;
            if stage_crossing
                && !prefix
                    .operating_point
                    .branches
                    .iter()
                    .enumerate()
                    .any(|(i, p)| self.protection_triggered(i, p))
            {
                return Err(SolveError::Subdivide(rejected(
                    "Radau interior crossing requires subdivision",
                )));
            }
            Some(prefix)
        } else {
            None
        };
        Ok((prefix, endpoint))
    }

    async fn radau_step(
        &self,
        seed: &AcceptedState,
        end: f64,
        bridge: Option<&bridge::Binding>,
    ) -> Result<(bool, AcceptedState), SolveError> {
        let h = end - seed.time_seconds;
        let t = [seed.time_seconds + h / 3.0, end];
        if !(seed.time_seconds < t[0] && t[0] < end) {
            return Err(rejected("Radau stages cannot be separated at this time").into());
        }
        let phases = [
            self.phases_after(seed, h / 3.0).await?,
            self.phases_after(seed, h).await?,
        ];
        let [a, b] = match bridge {
            Some(binding) => binding.stages(self, seed, end, &phases).await
                .ok_or_else(|| rejected("Reduced Radau bridge requires full equations"))?,
            None => self.full_radau_stages(seed, h, t, &phases).await?,
        };
        let sample = seed.sampler.sample_at_end(seed.time_seconds, end)?;
        let [pa, pb] = phases;
        self.settle_radau(seed, end, h, a, b, pa, pb, sample).await
    }

    pub(crate) async fn bridge_stage_pair(&self, seconds: f64)
        -> Result<[[dc::DcOperatingPoint; 2]; 2], SolveError> {
        let seed = &self.accepted;
        let end = seed.time_seconds + seconds;
        let h = end - seed.time_seconds;
        let times = [seed.time_seconds + h/3.0, end];
        if !(seed.time_seconds < times[0] && times[0] < end && end.is_finite()) ||
            !is_open_bridge(&self.branches) {
            return Err(rejected("Expected unloaded bridge and representable stage times").into());
        }
        let phases = [self.phases_after(seed, h/3.0).await?, self.phases_after(seed,h).await?];
        let binding = bridge::Binding::compile(self).ok_or_else(|| rejected("Unsupported bridge topology"))?;
        let full = self.full_radau_stages(seed, h, times, &phases).await?;
        let reduced = binding.stages(self, seed, end, &phases).await
            .ok_or_else(|| rejected("Fixed-step reduced bridge requires fallback"))?;
        Ok([full, reduced])
    }

    async fn full_radau_stages(&self, seed: &AcceptedState, h: f64, t: [f64; 2], phases: &[Phases; 2])
        -> Result<[dc::DcOperatingPoint; 2], SolveError> {
        let end = t[1];
        if let Some(plan) = self.linear_storage_plan().await? {
            let n = self.layout.size;
            match plan.stages(self, seed, h).await {
                Ok(x) => {
                    let a = dc::point(&self.branches, t[0], &self.layout, &x[..n]).await?;
                    let b = dc::point(&self.branches, end, &self.layout, &x[n..]).await?;
                    return Ok([a, b]);
                }
                Err(e) if self.layout.series.is_some() && e.retryable() => {}
                Err(e) => return Err(e),
            }
        }
        let history = self.storage_history(seed).await?;
        let n = self.layout.size;
        let mut x = vec![0.0; n * 2];
        for (map, point) in self
            .layout
            .mappings
            .iter()
            .zip(&seed.operating_point.branches)
        {
            for (row, value) in map.iter().zip(&point.local_values) {
                if let Some(row) = row {
                    x[*row] = *value;
                    x[n + row] = *value;
                }
            }
        }

        let weights = INVERSE.map(|row| row.map(|coefficient| coefficient / h));
        let symbolic = self.layout.coupled_pattern();
        progress::newton_trial();
        let assembly = progress::timer();
        let stamp = |i: usize, x: &[f64], mut jacobian: Option<&mut Matrix>, residual: &mut [f64],
                     tolerance: &mut [f64]| -> Result<(), SolveError> {
            let branch = &self.branches[i];
            let map = &self.layout.mappings[i];
            let eval = |stage: usize| {
                evaluate_at_phase(
                    branch,
                    &self.layout.local(i, &x[stage * n..]),
                    t[stage],
                    &phases[stage],
                )
            };
            let stages = [eval(0)?, eval(1)?];
            for (s, stage) in stages.iter().enumerate() {
                for (r, row) in map.iter().enumerate() {
                    let Some(row) = row else { continue };
                    let row = s * n + row;
                    tolerance[row] =
                        crate::solver::newton::residual_tolerance(&stage.residual_units[r]);
                    residual[row] += stage.residual[r];
                    for (j, other) in stages.iter().enumerate() {
                        let weight = weights[s][j];
                        if let Some(old) = history[i].get(r) {
                            residual[row] += weight * (other.charge[r] - old);
                        }
                        let Some(jacobian) = jacobian.as_deref_mut() else { continue };
                        for (c, col) in map.iter().enumerate() {
                            if let Some(col) = col {
                                let k = r * map.len() + c;
                                let v = weight * other.charge_jacobian[k]
                                    + if s == j {
                                        stage.residual_jacobian[k]
                                    } else {
                                        0.0
                                    };
                                if v != 0.0 {
                                    jacobian.add_solver(row, j * n + col, v)?;
                                }
                            }
                        }
                    }
                }
            }
            Ok(())
        };

        let anchor = x.clone();
        let mut fixed = Matrix::new(2 * n)?;
        let mut base_residual = vec![0.0; 2 * n];
        let mut base_tolerance = vec![1e-12; 2 * n];
        let mut nonlinear = Vec::new();
        let mut junctions = Vec::new();
        let mut changing = vec![false; n];
        for (i, branch) in self.branches.iter().enumerate() {
            checkpoint().await;
            if branch.element.is_nonlinear() {
                nonlinear.push(i);
                let map = &self.layout.mappings[i];
                crate::solver::newton::Junction::collect(branch, map, &mut junctions);
                for &row in map.iter().flatten() {
                    changing[row] = true;
                }
            } else {
                stamp(i, &anchor, Some(&mut fixed), &mut base_residual, &mut base_tolerance)?;
            }
        }
        let changing: Vec<_> = (0..2 * n).filter(|&r| changing[r % n]).collect();
        let mut direct = nonlinear.len() == self.branches.len();
        let mut jacobian = fixed.clone();
        let mut residual = base_residual.clone();
        let mut tolerance = base_tolerance.clone();
        let mut delta = vec![0.0; 2 * n];
        progress::assembled(assembly);

        let mut iterations = 0;
        while iterations < 20 {
            checkpoint().await;
            progress::newton_iteration();
            let assembly = progress::timer();
            jacobian.restore_rows(&fixed, &changing);
            tolerance.clone_from(&base_tolerance);
            if direct {
                residual.fill(0.0);
                for (i, branch) in self.branches.iter().enumerate() {
                    checkpoint().await;
                    // Re-evaluate every original residual. Affine derivatives are already in fixed.
                    let matrix = branch.element.is_nonlinear().then_some(&mut jacobian);
                    stamp(i, &x, matrix, &mut residual, &mut tolerance)?;
                }
            } else {
                for (r, value) in delta.iter_mut().enumerate() {
                    *value = x[r] - anchor[r];
                }
                for (row, value) in residual.iter_mut().enumerate() {
                    checkpoint().await;
                    *value = base_residual[row] + fixed.row_product(row, &delta);
                }
                for &i in &nonlinear {
                    checkpoint().await;
                    stamp(i, &x, Some(&mut jacobian), &mut residual, &mut tolerance)?;
                }
            }
            progress::assembled(assembly);
            let balanced = residual.iter().enumerate().all(|(r, v)| {
                v.abs() <= tolerance[r] + 32.0 * f64::EPSILON * jacobian.row_magnitude(r, &x)
            });
            if balanced && !direct {
                // Check the original stage equations and their undamped correction at this
                // iterate. Factoring the affine assembly first would discard that solve.
                direct = true;
                continue;
            }
            let rhs: Vec<_> = residual.iter().map(|v| -v).collect();
            let dx = jacobian.solve_newton_async(&rhs, symbolic).await?;
            if balanced
                && dx.iter().enumerate().all(|(r, d)| {
                    d.abs()
                        <= self.layout.unknown_units[r % n].absolute_tolerance() + 1e-9 * x[r].abs()
                })
            {
                let a = dc::point(&self.branches, t[0], &self.layout, &x[..n]).await?;
                let b = dc::point(&self.branches, end, &self.layout, &x[n..]).await?;
                return Ok([a, b]);
            }
            iterations += 1;
            let mut alpha = 1.0_f64;
            for junction in &junctions {
                checkpoint().await;
                alpha = alpha.min(junction.damping(&x[..n], &dx[..n]));
                alpha = alpha.min(junction.damping(&x[n..], &dx[n..]));
            }
            for (value, delta) in x.iter_mut().zip(dx) {
                *value += alpha * delta;
            }
        }
        Err(rejected("Radau coupled Newton iteration failed").into())
    }

    async fn settle_radau(&self, seed: &AcceptedState, end: f64, h: f64,
        a: dc::DcOperatingPoint, b: dc::DcOperatingPoint, pa: Phases, pb: Phases, sample: Option<f64>)
        -> Result<(bool, AcceptedState), SolveError> {
        let stage_time = seed.time_seconds + h / 3.0;
        let damping = self.radau_damping(&seed.operating_point, &a, &b);
        let stage_crossing = a
            .branches
            .iter()
            .enumerate()
            .any(|(i, p)| self.protection_triggered(i, p));
        let weighted = self
            .observe_stage(seed, stage_time, 0.75 * h, h / 3.0, None, a, pa, false)
            .await?;
        let mut endpoint = self
            .observe_stage(&weighted, end, 0.25 * h, h - h / 3.0, sample, b, pb, false)
            .await?;
        endpoint.energy.numerical_damping_joules += damping;
        if !endpoint.energy.is_finite() {
            return Err(rejected("Non-finite Radau energy").into());
        }
        Ok((stage_crossing, endpoint))
    }

    fn radau_damping(
        &self,
        seed: &dc::DcOperatingPoint,
        first: &dc::DcOperatingPoint,
        end: &dc::DcOperatingPoint,
    ) -> f64 {
        // For quadratic storage, the Runge–Kutta energy defect equals the energy of the increment
        // h·(k1−k2)/4.
        self.branches
            .iter()
            .enumerate()
            .map(|(i, b)| {
                let delta = |j: usize| {
                    let old = seed.branches[i].local_values[j];
                    1.5 * (first.branches[i].local_values[j] - old)
                        - 0.5 * (end.branches[i].local_values[j] - old)
                };
                match b.element {
                    Element::Capacitor { farads }
                    | Element::Diode {
                        junction_farads: farads,
                        ..
                    } => 0.5 * farads * (delta(0) - delta(1)).powi(2),
                    Element::Inductor { henries } => 0.5 * henries * delta(2).powi(2),
                    Element::Windings(m) => m.energy(delta(2), delta(4)),
                    Element::Bipolar(_) | Element::Mosfet(_) => {
                        let (a, b) = b.element.control_capacitances().unwrap();
                        0.5 * (a * (delta(3) - delta(1)).powi(2)
                            + b * (delta(3) - delta(0)).powi(2))
                    }
                    _ => 0.0,
                }
            })
            .sum()
    }
}
