//! Apply damped Newton to the original DAE, checking both residual and undamped correction.
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

/// Forward-oriented junction voltages use global Newton coordinates. A missing node is ground.
pub(super) struct Junction {
    anode: Option<usize>,
    cathode: Option<usize>,
    thermal_volts: f64,
    critical_volts: f64,
}

impl Junction {
    pub(super) fn collect(branch: &DcBranch, map: &[Option<usize>], out: &mut Vec<Self>) {
        use crate::model::{element::Element, rectifier::Rectifier};
        let mut add = |a: usize, c: usize, reverse: bool, vt: f64, saturation: f64| {
            let (anode, cathode) = if reverse { (map[c], map[a]) } else { (map[a], map[c]) };
            let critical_volts = vt * (vt.ln() - saturation.ln() - std::f64::consts::LN_2 / 2.0);
            out.push(Self { anode, cathode, thermal_volts: vt, critical_volts });
        };
        match &branch.element {
            Element::Diode { saturation_amperes, thermal_volts, .. } =>
                add(0, 1, false, *thermal_volts, *saturation_amperes),
            Element::Rectifier(_) =>
                add(0, 1, false, Rectifier::FORWARD_THERMAL_VOLTS, Rectifier::FORWARD_SATURATION_AMPERES),
            Element::Bipolar(m) => {
                add(3, 1, m.pnp, m.thermal_volts, m.saturation_amperes);
                add(3, 0, m.pnp, m.thermal_volts, m.saturation_amperes);
            }
            Element::Mosfet(m) => add(1, 0, m.p_channel, m.thermal_volts, m.saturation_amperes),
            _ => {}
        }
    }

    pub(super) fn damping(&self, x: &[f64], dx: &[f64]) -> f64 {
        let voltage = |v: &[f64]| self.anode.map_or(0.0, |r| v[r]) - self.cathode.map_or(0.0, |r| v[r]);
        let old = voltage(x);
        let step = voltage(dx);
        if step <= 2.0 * self.thermal_volts || old + step <= self.critical_volts {
            return 1.0;
        }
        // Near the threshold, rounding can leave the iterate a few ulps below it.
        // Allow the logarithmic increment there so a trial can still make progress.
        let logarithmic = self.thermal_volts * (step / self.thermal_volts).ln_1p();
        let allowed = (self.critical_volts - old).max(logarithmic);
        let alpha = allowed / step;
        if alpha > 0.0 && alpha < 1.0 { alpha } else { 1.0 }
    }
}

pub(super) struct InitialVoltageRow {
    pub row: usize,
    pub coefficients: Vec<(usize, f64)>,
}

pub(super) fn residual_tolerance(unit: &ResidualUnit) -> f64 {
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

/// Topology fixes the unknown layout. Reused numerical Jacobians must pass convergence checks on
/// the original equations.
pub(crate) fn solve_compiled(
    branches: &[DcBranch],
    time: f64,
    initial: Option<&DcOperatingPoint>,
    layout: &Layout,
    evaluate: impl Fn(&DcBranch, &[f64]) -> Result<Evaluation, Diagnostic>,
) -> Result<DcOperatingPoint, Diagnostic> {
    super::cooperative::complete(solve_compiled_async(branches, time, initial, layout, &[], |branch, values| {
        evaluate(branch, values).map_err(Into::into)
    })).map_err(Into::into)
}

pub(super) async fn solve_compiled_async(
    branches: &[DcBranch],
    time: f64,
    initial: Option<&DcOperatingPoint>,
    layout: &Layout,
    initial_voltage_rows: &[InitialVoltageRow],
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
    // At fixed time and step size, linear rows stay affine within a nonlinear domain. Cache
    // coefficients and update the right-hand side.
    let timing = super::progress::timer();
    let mut fixed = Matrix::new(n)?;
    let anchor = x.clone();
    let mut fixed_residual = vec![0.0; n];
    let mut nonlinear = Vec::new();
    let mut junctions = Vec::new();
    let mut changing = vec![false; n];
    let mut base_scales = vec![AMP_TOL; n];
    let mut local = Vec::new();
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        if branch.element.is_nonlinear() {
            nonlinear.push(i);
            let map = &layout.mappings[i];
            Junction::collect(branch, map, &mut junctions);
            for &row in map.iter().flatten() {
                changing[row] = true;
            }
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
    for constraint in initial_voltage_rows {
        changing[constraint.row] = true;
    }
    let changing: Vec<_> = changing.iter().enumerate().filter_map(|(i, &set)| set.then_some(i)).collect();
    let mut system = (vec![0.0; n], fixed.clone(), base_scales.clone());
    let mut trial_system = system.clone();
    let mut delta = Vec::new();
    let mut assemble = async |x: &[f64], system: &mut AssembledSystem, direct: bool| -> Result<(), super::SolveError> {
        let timing = super::progress::timer();
        let (residual, jacobian, scales) = system;
        residual.fill(0.0);
        jacobian.restore_rows(&fixed, &changing);
        scales.clone_from(&base_scales);
        if !direct {
            // Center affine rows at the initial guess to preserve the precision of balanced
            // storage-history terms.
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
        for constraint in initial_voltage_rows {
            let row = constraint.row;
            jacobian.clear_row(row);
            for &(column, weight) in &constraint.coefficients {
                jacobian.add_solver(row, column, weight)?;
            }
            residual[row] = jacobian.row_product(row, x);
            scales[row] = VOLT_TOL;
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
    // Factor validity depends on time, step size, parameters and storage history. Piecewise
    // devices require an exact matrix match. When thermal resistors supply all nonlinear rows, try
    // a chord iteration with the previous Jacobian and verify it against the original equations.
    static REUSE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    let reuse = *REUSE.get_or_init(|| std::env::var("EM_NEWTON_REUSE").as_deref() == Ok("1"));
    let smooth = reuse && nonlinear.iter().all(|&i|
        matches!(branches[i].element, crate::model::element::Element::ThermalResistor(_)));
    let mut cached: Option<(Matrix, super::sparse::PreparedMatrix)> = None;
    let mut reuse_count = 0;
    let mut trial = Vec::with_capacity(n);
    assemble(&x, &mut system, direct).await?;
    for _ in 0..ITERATIONS {
        super::progress::newton_iteration();
        if !direct && norm(&system.0, &system.2) <= 1.0 {
            // Before accepting a candidate, check the residual and undamped correction on the
            // original equations, including storage terms.
            direct = true;
            assemble(&x, &mut system, direct).await?;
        }
        let (residual, jacobian, scales) = &system;
        let residual_norm = norm(&residual, &scales);
        let rhs: Vec<_> = residual.iter().map(|v| -v).collect();
        // Weakly connected nodes can amplify rounding error. Solve again only for a nonzero change
        // in the right-hand side.
        let resolved_rhs = (residual_norm <= 1.0).then(|| rhs.iter().enumerate().map(|(row, &v)| {
            let floor = 32.0 * f64::EPSILON * jacobian.row_magnitude(row, &x);
            if v.abs() <= floor { 0.0 } else { v }
        }).collect::<Vec<_>>());
        let second_rhs = resolved_rhs.as_ref().is_some_and(|filtered|
            filtered != &rhs && filtered.iter().any(|&v| v != 0.0));
        let chord = smooth && residual_norm > 1.0 && reuse_count < 2
            && cached.as_ref().is_some_and(|(previous, _)| previous != jacobian);
        if reuse && !chord && !cached.as_ref().is_some_and(|(previous, _)| previous == jacobian) {
            cached = Some((jacobian.clone(), super::sparse::PreparedMatrix::new(jacobian.clone(), &layout.symbolic).await?));
            reuse_count = 0;
        }
        let current_factors = if !reuse && second_rhs {
            Some(super::sparse::PreparedMatrix::newton_pair(jacobian.clone(), &layout.symbolic).await?)
        } else { None };
        let prepared = current_factors.as_ref().or_else(|| cached.as_ref().map(|(_, factors)| factors));
        let correction = match prepared {
            Some(matrix) => matrix.solve(&rhs, &layout.symbolic).await,
            None => jacobian.solve_newton_async(&rhs, &layout.symbolic).await,
        };
        let correction = match correction {
            Err(error) if chord && error.retryable() => {
                cached = None;
                continue;
            }
            result => result?,
        };
        let increment = correction_norm(layout, &x, &correction);
        let resolved_increment = match (prepared, resolved_rhs) {
            (_, Some(filtered)) if increment > 1.0 && filtered.iter().all(|&v| v == 0.0) => 0.0,
            (Some(matrix), Some(filtered)) if second_rhs && increment > 1.0 => {
                let resolved = matrix.solve(&filtered, &layout.symbolic).await?;
                correction_norm(layout, &x, &resolved)
            }
            _ => increment,
        };
        if residual_norm <= 1.0 && resolved_increment <= 1.0 {
            // Recover cold thermal states from the original equation. The resistor temperature row
            // is affine; tapped conductors and air gaps require scalar Newton steps.
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
        if chord { reuse_count += 1; }
        let mut alpha = 1.0_f64;
        for junction in &junctions {
            super::cooperative::checkpoint().await;
            alpha = alpha.min(junction.damping(&x, &correction));
        }
        let mut accepted = false;
        for _ in 0..BACKTRACKS {
            super::progress::backtrack();
            trial.clear();
            trial.extend(x.iter().zip(&correction).map(|(v, d)| v + alpha * d));
            if trial.iter().all(|v| v.is_finite()) {
                if let Err(error) = assemble(&trial, &mut trial_system, direct).await {
                    if chord && error.retryable() { break; }
                    if !error.retryable() { return Err(error); }
                    alpha *= 0.5;
                    continue;
                }
                let merit = norm(&trial_system.0, scales);
                if chord && merit > 0.5 * residual_norm { break; }
                if merit < residual_norm * (1.0 - 1e-4 * alpha) || merit <= 1.0 {
                    accepted = true;
                    break;
                }
            }
            alpha *= 0.5;
        }
        if alpha < 1.0 || (chord && !accepted) { cached = None; }
        if !accepted && chord { continue; }
        if !accepted {
            if !direct {
                // Cancellation can hide a decrease in the original equations' residual. Retry from
                // the same iterate.
                direct = true;
                assemble(&x, &mut system, direct).await?;
                continue;
            }
            return Err(rejected("Newton line search failed; candidate discarded").into());
        }
        std::mem::swap(&mut x, &mut trial);
        // The accepted line-search point carries its exact residual and Jacobian.
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
