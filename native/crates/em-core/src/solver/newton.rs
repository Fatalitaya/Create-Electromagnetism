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

fn residual_tolerance(unit: &ResidualUnit) -> f64 {
    match unit {
        ResidualUnit::Ampere => AMP_TOL,
        ResidualUnit::Volt => VOLT_TOL,
        ResidualUnit::VoltSquared => VOLT_TOL * VOLT_TOL,
        ResidualUnit::Watt => 1e-9,
        ResidualUnit::Kelvin => 1e-8,
    }
}

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
        let initial_by_id = std::cell::OnceCell::<std::collections::HashMap<_, _>>::new();
        for (i, b) in branches.iter().enumerate() {
            super::cooperative::checkpoint().await;
            let point = initial.branches.get(i).filter(|point| point.id == b.id)
                .or_else(|| initial_by_id.get_or_init(|| initial.branches.iter().map(|p| (p.id, p)).collect())
                    .get(&b.id).copied())
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
    // At a fixed time and step, linear device rows are affine even in a
    // nonlinear domain. Keep the constant stamp and evaluate only its RHS.
    let timing = super::progress::timer();
    let mut fixed = Matrix::new(n)?;
    let anchor = x.clone();
    let mut fixed_residual = vec![0.0; n];
    let mut nonlinear = Vec::new();
    let mut base_scales = vec![AMP_TOL; n];
    let mut local = Vec::new();
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        if branch.element.is_nonlinear() {
            nonlinear.push(i);
            continue;
        }
        let mapping = &layout.mappings[i];
        local.clear();
        local.extend(layout.local_values(i, &anchor));
        let e = evaluate(branch, &local)?;
        for (lr, row) in mapping.iter().enumerate() {
            let Some(row) = *row else { continue };
            fixed_residual[row] += e.residual[lr];
            if row >= layout.node_count {
                base_scales[row] = residual_tolerance(&e.residual_units[lr]);
            }
        }
        layout.stamp(i, &e.residual_jacobian, &mut fixed)?;
    }
    super::progress::assembled(timing);
    let mut delta = Vec::new();
    let mut assemble = async |x: &[f64], system: &mut AssembledSystem, direct: bool| -> Result<(), super::SolveError> {
        let timing = super::progress::timer();
        let (residual, jacobian, scales) = system;
        residual.fill(0.0);
        jacobian.clone_from(&fixed);
        scales.clone_from(&base_scales);
        if !direct {
            // Center the affine rows at the initial guess so storage-history
            // terms already balanced there retain their original precision.
            delta.clear();
            delta.extend(x.iter().zip(&anchor).map(|(x, old)| x - old));
            for (row, value) in residual.iter_mut().enumerate() {
                super::cooperative::checkpoint().await;
                *value = fixed_residual[row] + fixed.row_product(row, &delta);
            }
        }
        let count = if direct { branches.len() } else { nonlinear.len() };
        for index in 0..count {
            let i = if direct { index } else { nonlinear[index] };
            let b = &branches[i];
            super::cooperative::checkpoint().await;
            local.clear();
            local.extend(layout.local_values(i, x));
            let e = evaluate(b, &local)?;
            let nonlinear_branch = b.element.is_nonlinear();
            for (lr, row) in layout.mappings[i].iter().enumerate() {
                if let Some(row) = *row {
                    residual[row] += e.residual[lr];
                    if !nonlinear_branch { continue; }
                    if row >= layout.node_count {
                        scales[row] = residual_tolerance(&e.residual_units[lr]);
                    }
                }
            }
            if nonlinear_branch { layout.stamp(i, &e.residual_jacobian, jacobian)?; }
        }
        for (row, scale) in scales.iter_mut().enumerate() {
            *scale += RTOL * jacobian.row_magnitude(row, x);
        }
        if residual.iter().chain(scales.iter()).any(|v| !v.is_finite()) {
            return Err(rejected("Non-finite Newton system").into());
        }
        super::progress::assembled(timing);
        Ok(())
    };
    let norm = |values: &[f64], scales: &[f64]| -> f64 {
        values
            .iter()
            .zip(scales)
            .fold(0.0_f64, |a, (v, s)| a.max((v / s).abs()))
    };
    let mut direct = nonlinear.len() == branches.len();
    let mut trial = Vec::with_capacity(n);
    assemble(&x, &mut system, direct).await?;
    for _ in 0..ITERATIONS {
        super::progress::newton_iteration();
        if !direct && norm(&system.0, &system.2) <= 1.0 {
            // Both convergence tests use the original equations, including
            // their storage arithmetic, at the candidate being accepted.
            direct = true;
            assemble(&x, &mut system, direct).await?;
        }
        let (residual, jacobian, scales) = &system;
        let residual_norm = norm(&residual, &scales);
        let rhs: Vec<_> = residual.iter().map(|v| -v).collect();
        // A nearly isolated node can amplify arithmetic roundoff. Only a
        // genuinely different, nonzero right hand side needs another solve.
        let resolved_rhs = (residual_norm <= 1.0).then(|| rhs.iter().enumerate().map(|(row, &v)| {
            let floor = 32.0 * f64::EPSILON * jacobian.row_magnitude(row, &x);
            if v.abs() <= floor { 0.0 } else { v }
        }).collect::<Vec<_>>());
        let prepared = match &resolved_rhs {
            Some(filtered) if filtered != &rhs && filtered.iter().any(|&v| v != 0.0) =>
                Some(super::sparse::PreparedMatrix::new(jacobian.clone(), &layout.symbolic).await?),
            _ => None,
        };
        let correction = match &prepared {
            Some(matrix) => matrix.solve(&rhs, &layout.symbolic).await?,
            None => jacobian.solve_newton_async(&rhs, &layout.symbolic).await?,
        };
        let increment = correction_norm(layout, &x, &correction);
        let resolved_increment = match (&prepared, resolved_rhs) {
            (_, Some(filtered)) if increment > 1.0 && filtered.iter().all(|&v| v == 0.0) => 0.0,
            (Some(matrix), Some(filtered)) if increment > 1.0 => {
                let resolved = matrix.solve(&filtered, &layout.symbolic).await?;
                correction_norm(layout, &x, &resolved)
            }
            _ => increment,
        };
        if residual_norm <= 1.0 && resolved_increment <= 1.0 {
            // Resolve cold thermal states from the original row instead of
            // accepting a small negative Newton iterate. Resistors are affine
            // in theta; tapped conductors and air gaps need scalar Newton steps.
            // Keep their electrical unknowns fixed, then recheck the full DAE.
            // The regulator's squared-voltage detector has the same nonnegative
            // boundary and an affine row. This is not a state clamp or an energy correction.
            let mut reconstructed = false;
            for (i, branch) in branches.iter().enumerate() {
                super::cooperative::checkpoint().await;
                if let Some(state) = branch.element.internal_state() {
                    let thermal_row = state.row;
                    let mut local = layout.local(i, &x);
                    if local[thermal_row] >= 0.0 && !state.reconstruct_always {
                        continue;
                    }
                    let original = local[thermal_row];
                    local[thermal_row] = 0.0;
                    for _ in 0..8 {
                        let e = evaluate(branch, &local)?;
                        let slope = e.residual_jacobian[thermal_row * local.len() + thermal_row];
                        if slope == 0.0 { break; }
                        let next = local[thermal_row] - e.residual[thermal_row] / slope;
                        if !next.is_finite() || next == local[thermal_row] { break; }
                        local[thermal_row] = next;
                    }
                    reconstructed |= original != local[thermal_row];
                    x[layout.mappings[i][thermal_row].expect("nonnegative internal unknown")] =
                        local[thermal_row];
                }
            }
            if reconstructed {
                assemble(&x, &mut system, true).await?;
                if norm(&system.0, &system.2) > 1.0 {
                    continue;
                }
            }
            return dc::point(branches, time, layout, &x).await.map_err(Into::into);
        }
        let mut alpha = 1.0;
        let mut accepted = false;
        for _ in 0..BACKTRACKS {
            super::progress::backtrack();
            trial.clear();
            trial.extend(x.iter().zip(&correction).map(|(v, d)| v + alpha * d));
            if trial.iter().all(|v| v.is_finite()) {
                if let Err(error) = assemble(&trial, &mut trial_system, direct).await {
                    if !error.retryable() { return Err(error); }
                    alpha *= 0.5;
                    continue;
                }
                let merit = norm(&trial_system.0, scales);
                if merit < residual_norm * (1.0 - 1e-4 * alpha) || merit <= 1.0 {
                    accepted = true;
                    break;
                }
            }
            alpha *= 0.5;
        }
        if !accepted {
            if !direct {
                // A cancellation-sensitive affine residual can mask a decrease
                // in the original equations. Retry from the same iterate.
                direct = true;
                assemble(&x, &mut system, direct).await?;
                continue;
            }
            return Err(rejected("Newton line search failed; candidate discarded").into());
        }
        std::mem::swap(&mut x, &mut trial);
        // The accepted line-search point already owns its exact Jacobian and residual.
        std::mem::swap(&mut system, &mut trial_system);
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
