use super::*;
use crate::solver::{SolveError, cooperative::checkpoint, progress, sparse::Matrix};

// A⁻¹ converts stage charge increments to time derivatives.
const INVERSE: [[f64; 2]; 2] = [[1.5, 0.5], [-4.5, 2.5]];

pub(super) fn enabled() -> bool {
    static ENABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ENABLED.get_or_init(|| std::env::var_os("EM_RADAU").is_some_and(|s| s == "1"))
}

impl TransientCircuit {
    pub(super) async fn radau_pair(
        &self,
        end: f64,
        middle: f64,
        accuracy: Accuracy,
    ) -> Result<Trial, SolveError> {
        let (full, (first, half, third, fine)) = crate::solver::cooperative::trajectories(
            true,
            self.radau_step(&self.accepted, end),
            async {
                let (first, half) = self.radau_protected_step(&self.accepted, middle).await?;
                let (third, fine) = self.radau_protected_step(&half, end).await?;
                Ok((first, half, third, fine))
            },
        )
        .await?;
        let error = self
            .refinement_error(&self.accepted, &full.1, &fine, accuracy, true)
            .await?;
        let intermediate = first.into_iter().chain([half]).chain(third).collect();
        Ok(Trial {
            end: fine,
            intermediate,
            error,
        })
    }

    async fn radau_protected_step(
        &self,
        seed: &AcceptedState,
        end: f64,
    ) -> Result<(Option<AcceptedState>, AcceptedState), SolveError> {
        let (stage_crossing, endpoint) = self.radau_step(seed, end).await?;
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
            let (_, prefix) = self.radau_step(seed, stage_time).await?;
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

        let symbolic = self.layout.coupled_pattern();
        progress::newton_trial();
        for _ in 0..20 {
            checkpoint().await;
            progress::newton_iteration();
            let assembly = progress::timer();
            let mut jacobian = Matrix::new(2 * n)?;
            let mut residual = vec![0.0; 2 * n];
            let mut tolerance = vec![1e-12; 2 * n];
            for (i, branch) in self.branches.iter().enumerate() {
                checkpoint().await;
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
                            let weight = INVERSE[s][j] / h;
                            if let Some(old) = history[i].get(r) {
                                residual[row] += weight * (other.charge[r] - old);
                            }
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
            }
            progress::assembled(assembly);
            let balanced = residual.iter().enumerate().all(|(r, v)| {
                v.abs() <= tolerance[r] + 32.0 * f64::EPSILON * jacobian.row_magnitude(r, &x)
            });
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
                let sample = seed.sampler.sample_at_end(seed.time_seconds, end)?;
                let [pa, pb] = phases;
                let damping = self.radau_damping(&seed.operating_point, &a, &b);
                let stage_crossing = a
                    .branches
                    .iter()
                    .enumerate()
                    .any(|(i, p)| self.protection_triggered(i, p));
                let weighted = self
                    .observe_stage(seed, t[0], 0.75 * h, h / 3.0, None, a, pa, false)
                    .await?;
                let mut endpoint = self
                    .observe_stage(&weighted, end, 0.25 * h, h - h / 3.0, sample, b, pb, false)
                    .await?;
                endpoint.energy.numerical_damping_joules += damping;
                if !endpoint.energy.is_finite() {
                    return Err(rejected("Non-finite Radau energy").into());
                }
                return Ok((stage_crossing, endpoint));
            }
            for (value, delta) in x.iter_mut().zip(dx) {
                *value += delta;
            }
        }
        Err(rejected("Radau coupled Newton iteration failed").into())
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
