//! Damped Newton on the original shared DAE, with residual AND undamped correction tests.
use crate::{
    model::{Evaluation, ResidualUnit, element::rejected},
    solver::{
        dc::{self, DcBranch, DcOperatingPoint},
        sparse::Matrix,
    },
    topology::Layout,
};
use em_protocol::contract::Diagnostic;

const ITERATIONS: usize = 100;
const BACKTRACKS: usize = 32;
const RTOL: f64 = 1e-9;
const VOLT_TOL: f64 = 1e-10;
const AMP_TOL: f64 = 1e-12;

type AssembledSystem = (Vec<f64>, Matrix, Vec<f64>);

fn correction_norm(layout: &Layout, x: &[f64], correction: &[f64]) -> f64 {
    correction.iter().zip(x).zip(&layout.unknown_units).fold(0.0_f64,
        |norm, ((delta, value), unit)| norm.max(delta.abs() /
            (unit.absolute_tolerance() + RTOL * value.abs())))
}

/// Layout belongs to the immutable topology; numeric Jacobians are still rebuilt
/// for every candidate/iteration and verified against the original equations.
pub(crate) fn solve_compiled(
    branches: &[DcBranch],
    time: f64,
    initial: Option<&DcOperatingPoint>,
    layout: &Layout,
    evaluate: impl Fn(&DcBranch, &[f64]) -> Result<Evaluation, Diagnostic>,
) -> Result<DcOperatingPoint, Diagnostic> {
    super::cooperative::complete(solve_compiled_async(branches, time, initial, layout, |branch, values| {
        evaluate(branch, values).map_err(Into::into)
    })).map_err(Into::into)
}

pub(super) async fn solve_compiled_async(
    branches: &[DcBranch],
    time: f64,
    initial: Option<&DcOperatingPoint>,
    layout: &Layout,
    evaluate: impl Fn(&DcBranch, &[f64]) -> Result<Evaluation, super::SolveError>,
) -> Result<DcOperatingPoint, super::SolveError> {
    let n = layout.size;
    let mut x = vec![0.0; n];
    if let Some(initial) = initial {
        for (port, row) in &layout.rows {
            if let Some(row) = row {
                x[*row] = initial.voltages[port];
            }
        }
        let initial_by_id: std::collections::HashMap<_, _> =
            initial.branches.iter().map(|p| (p.id, p)).collect();
        for (i, b) in branches.iter().enumerate() {
            super::cooperative::checkpoint().await;
            let point = initial_by_id
                .get(&b.id)
                .ok_or_else(|| super::permanent("Missing Newton initial branch identity"))?;
            for (local, row) in layout.mappings[i].iter().enumerate() {
                if let Some(row) = row
                    && *row >= layout.node_count
                {
                    x[*row] = point.local_values[local];
                }
            }
        }
    }
    let mut system: AssembledSystem = (vec![0.0; n], Matrix::new(n)?, vec![AMP_TOL; n]);
    let mut trial_system: AssembledSystem = (vec![0.0; n], Matrix::new(n)?, vec![AMP_TOL; n]);
    let assemble = async |x: &[f64], system: &mut AssembledSystem| -> Result<(), super::SolveError> {
        let (residual, jacobian, scales) = system;
        residual.fill(0.0);jacobian.clear();scales.fill(AMP_TOL);
        for (i, b) in branches.iter().enumerate() {
            super::cooperative::checkpoint().await;
            let local = layout.local(i, x);
            let e = evaluate(b, &local)?;
            for (lr, row) in layout.mappings[i].iter().enumerate() {
                if let Some(row) = *row {
                    residual[row] += e.residual[lr];
                    if row >= layout.node_count {
                        scales[row] = match e.residual_units[lr] {
                            ResidualUnit::Ampere => AMP_TOL,
                            ResidualUnit::Volt => VOLT_TOL,
                            ResidualUnit::VoltSquared => VOLT_TOL * VOLT_TOL,
                            ResidualUnit::Watt => 1e-9,
                            ResidualUnit::Kelvin => 1e-8,
                        };
                    }
                    for (lc, col) in layout.mappings[i].iter().enumerate() {
                        if let Some(col) = *col {
                            jacobian.add_solver(row, col, e.residual_jacobian[lr * local.len() + lc])?;
                        }
                    }
                }
            }
        }
        for (row, scale) in scales.iter_mut().enumerate() {
            *scale += RTOL * jacobian.row_magnitude(row, x);
        }
        if residual.iter().chain(scales.iter()).any(|v| !v.is_finite()) {
            return Err(rejected("Non-finite Newton system").into());
        }
        Ok(())
    };
    let norm = |values: &[f64], scales: &[f64]| -> f64 {
        values
            .iter()
            .zip(scales)
            .fold(0.0_f64, |a, (v, s)| a.max((v / s).abs()))
    };
    for _ in 0..ITERATIONS {
        super::progress::newton_iteration();
        assemble(&x, &mut system).await?;
        let (residual, jacobian, scales) = &system;
        let residual_norm = norm(&residual, &scales);
        let rhs: Vec<_> = residual.iter().map(|v| -v).collect();
        let correction = jacobian.solve_symbolic_async(&rhs, &layout.symbolic).await?;
        let increment = correction_norm(layout, &x, &correction);
        // A nearly isolated subcircuit can amplify cancellation of terminal
        // voltages into a large Newton correction even after the original DAE
        // is satisfied to floating-point precision. Remove only residuals below
        // the arithmetic roundoff floor, then still require a small correction
        // for every resolvable equation. This is not a conductance or tolerance
        // relaxation; the original dimensioned residual test remains mandatory.
        let resolved_increment = if residual_norm <= 1.0 && increment > 1.0 {
            let resolved_rhs: Vec<_> = residual
                .iter()
                .enumerate()
                .map(|(row, value)| {
                    let floor = 32.0 * f64::EPSILON * jacobian.row_magnitude(row, &x);
                    if value.abs() <= floor { 0.0 } else { -*value }
                })
                .collect();
            let resolved = jacobian.solve_symbolic_async(&resolved_rhs, &layout.symbolic).await?;
            correction_norm(layout, &x, &resolved)
        } else {
            increment
        };
        if residual_norm <= 1.0 && resolved_increment <= 1.0 {
            // Resolve cold thermal states from the original row instead of
            // accepting a small negative Newton iterate. Resistors are affine
            // in theta; tapped conductors and air gaps need scalar Newton steps.
            // Keep their electrical unknowns fixed, then recheck the full DAE.
            // The regulator's squared-voltage detector has the same nonnegative
            // boundary and an affine row. This is not a state clamp or an energy correction.
            for (i, branch) in branches.iter().enumerate() {
                super::cooperative::checkpoint().await;
                if let Some(state) = branch.element.internal_state() {
                    let thermal_row = state.row;
                    let mut local = layout.local(i, &x);
                    if local[thermal_row] >= 0.0 && !state.reconstruct_always {
                        continue;
                    }
                    local[thermal_row] = 0.0;
                    for _ in 0..8 {
                        let e = evaluate(branch, &local)?;
                        let slope = e.residual_jacobian[thermal_row * local.len() + thermal_row];
                        if slope == 0.0 { break; }
                        let next = local[thermal_row] - e.residual[thermal_row] / slope;
                        if !next.is_finite() || next == local[thermal_row] { break; }
                        local[thermal_row] = next;
                    }
                    x[layout.mappings[i][thermal_row].expect("nonnegative internal unknown")] =
                        local[thermal_row];
                }
            }
            assemble(&x, &mut trial_system).await?;
            let (verified, _, scale) = &trial_system;
            if norm(&verified, &scale) > 1.0 {
                continue;
            }
            return dc::point(branches, time, layout, &x).await.map_err(Into::into);
        }
        let mut alpha = 1.0;
        let mut next = None;
        for _ in 0..BACKTRACKS {
            super::progress::backtrack();
            let trial: Vec<_> = x
                .iter()
                .zip(&correction)
                .map(|(v, d)| v + alpha * d)
                .collect();
            if trial.iter().all(|v| v.is_finite()) {
                if let Err(error) = assemble(&trial, &mut trial_system).await {
                    if !error.retryable() { return Err(error); }
                    alpha *= 0.5;
                    continue;
                }
                let merit = norm(&trial_system.0, scales);
                if merit < residual_norm * (1.0 - 1e-4 * alpha) || merit <= 1.0 {
                    next = Some(trial);
                    break;
                }
            }
            alpha *= 0.5;
        }
        x = next.ok_or_else(|| rejected("Newton line search failed; candidate discarded"))?;
    }
    Err(rejected(
        "Newton iteration budget exhausted; candidate discarded",
    ).into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{UnknownUnit, element::Element};
    use em_protocol::contract::{DeviceId, Id, PortId};

    #[test]
    fn mixed_layout_scales_potentials_currents_and_internal_states_independently() {
        use UnknownUnit::{Volt, Ampere, Kelvin, VoltSquared};
        let elements = [
            Element::from_parameters(12, &[2.0, 0.004, 4.0, 0.5, 300.0]).unwrap(),
            Element::from_parameters(21, &[0.0, 0.01, 1e-12, 2.0, 1000.0, 1000.0, 1.0, 1.0, 300.0]).unwrap(),
            Element::from_parameters(22, &[2.0, 0.1, 0.1, 5.0]).unwrap(),
        ];
        let expected = [vec![Volt, Volt, Ampere, Kelvin], vec![Volt, Volt, Ampere, Kelvin],
            vec![Volt, Volt, Ampere, Volt, Ampere, VoltSquared]];
        let branches: Vec<_> = elements.into_iter().enumerate().map(|(i, element)| {
            let port = |terminal| PortId { device: DeviceId { instance: Id([0,i as u64]), generation: 0 }, terminal };
            DcBranch { id: Id([1,i as u64]), positive: port(0), negative: port(1),
                additional_ports: (2..element.port_count()).map(|p| port(p as u32)).collect(), element }
        }).collect();
        let layout = Layout::compile(&branches).unwrap();
        assert_eq!(layout.unknown_units.len(), layout.size);
        for (mapping, units) in layout.mappings.iter().zip(expected) {
            for (row, unit) in mapping.iter().zip(units) {
                if let Some(row) = row { assert_eq!(layout.unknown_units[*row], unit); }
            }
        }
        let x = vec![0.0; layout.size];
        for i in 0..layout.size {
            let mut delta = vec![0.0; layout.size];
            delta[i] = layout.unknown_units[i].absolute_tolerance() * 0.5;
            assert_eq!(correction_norm(&layout, &x, &delta), 0.5);
            delta[i] *= 4.0;
            assert_eq!(correction_norm(&layout, &x, &delta), 2.0);
        }
    }
}
