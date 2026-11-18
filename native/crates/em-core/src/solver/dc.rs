//! Immutable DC prototype, independent of the unavailable world/session backend.
use crate::model::{
    DeviceModel, Evaluation,
    element::{Element, rejected},
};
use crate::topology::Layout;
use em_protocol::contract::{Diagnostic, Id, PortId};
use std::collections::HashMap;

/// An explicit equipotential node is identified by a stable representative port.
/// Geometric wiring and ideal-wire merging must be performed by the caller.
#[derive(Clone, Debug, PartialEq)]
pub struct DcBranch {
    pub id: Id,
    pub positive: PortId,
    pub negative: PortId,
    /// Further terminals follow the model's declared ordering; identities are not matrix rows.
    pub additional_ports: Vec<PortId>,
    pub element: Element,
}

impl DcBranch {
    pub fn clock_id(&self) -> Id {
        if matches!(
            self.element,
            Element::AcShaft { .. } | Element::FieldAcShaft { .. }
        ) {
            self.positive.device.instance
        } else {
            self.id
        }
    }
    pub fn ports(&self) -> impl Iterator<Item = PortId> + '_ {
        [self.positive, self.negative]
            .into_iter()
            .chain(self.additional_ports.iter().copied())
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct BranchOperatingPoint {
    pub id: Id,
    pub voltage: f64,
    pub current: f64,
    /// Positive means absorbed, negative means supplied, in watts.
    pub absorbed_watts: f64,
    pub observed_watts: f64,
    pub dissipated_watts: f64,
    pub stored_joules: f64,
    /// [v0, v1, i0, v2, i2, ...], with terminal 1 the common return.
    pub local_values: Vec<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DcOperatingPoint {
    /// Voltages are relative to the listed reference of each connected component.
    pub voltages: HashMap<PortId, f64>,
    pub references: Vec<PortId>,
    pub branches: Vec<BranchOperatingPoint>,
    /// Sum of branch absorbed power, in watts; not accumulated energy.
    pub power_balance_watts: f64,
}

/// Bounded sparse solve. No state, physical time, energy or events are committed.
/// Each disconnected component receives one gauge reference, not a ground branch.
pub fn solve(branches: &[DcBranch]) -> Result<DcOperatingPoint, Diagnostic> {
    if branches.iter().any(|b| b.element.is_nonlinear()) {
        return super::newton::solve(branches, 0.0, None, |b, x| b.element.evaluate(x, 0.0));
    }
    solve_equations(branches, 0.0, |branch| {
        branch
            .element
            .evaluate(&vec![0.0; branch.element.unknown_count()], 0.0)
    })
}

/// Shared linear DAE assembly. The closure supplies the frozen interval equation.
pub(crate) fn solve_equations(
    branches: &[DcBranch],
    time: f64,
    equation: impl Fn(&DcBranch) -> Result<Evaluation, Diagnostic>,
) -> Result<DcOperatingPoint, Diagnostic> {
    let layout = Layout::compile(branches)?;
    solve_compiled(branches, time, &layout, equation)
}

pub(crate) fn solve_compiled(
    branches: &[DcBranch],
    time: f64,
    layout: &Layout,
    equation: impl Fn(&DcBranch) -> Result<Evaluation, Diagnostic>,
) -> Result<DcOperatingPoint, Diagnostic> {
    super::cooperative::complete(solve_compiled_async(branches, time, layout, |branch| {
        equation(branch).map_err(Into::into)
    })).map_err(Into::into)
}

pub(super) async fn solve_compiled_async(
    branches: &[DcBranch],
    time: f64,
    layout: &Layout,
    equation: impl Fn(&DcBranch) -> Result<Evaluation, super::SolveError>,
) -> Result<DcOperatingPoint, super::SolveError> {
    let n = layout.size;
    let mut matrix = super::sparse::Matrix::new(n)?;
    let mut rhs = vec![0.0; n];
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        let mapping = &layout.mappings[i];
        let evaluation = equation(branch)?;
        for (local_row, row) in mapping.iter().enumerate() {
            if let Some(row) = *row {
                rhs[row] -= evaluation.residual[local_row];
                for (local_col, col) in mapping.iter().enumerate() {
                    if let Some(col) = *col {
                        matrix.add_solver(
                            row,
                            col,
                            evaluation.residual_jacobian[local_row * mapping.len() + local_col],
                        )?;
                    }
                }
            }
        }
    }
    let x = matrix.solve_symbolic_async(&rhs, &layout.symbolic).await?;
    point(branches, time, layout, &x).await.map_err(Into::into)
}

pub(super) async fn point(
    branches: &[DcBranch],
    time: f64,
    layout: &Layout,
    x: &[f64],
) -> Result<DcOperatingPoint, Diagnostic> {
    let voltages: HashMap<_, _> = layout
        .rows
        .iter()
        .map(|(&port, &row)| (port, row.map_or(0.0, |r| x[r])))
        .collect();
    let mut results = Vec::new();
    let mut power_balance_watts = 0.0;
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        let va = voltages[&branch.positive];
        let vb = voltages[&branch.negative];
        let local_values = layout.local(i, x);
        if let Some(state) = branch.element.negative_internal_state(&local_values) {
            return Err(match state.unit {
                crate::model::UnknownUnit::Kelvin => rejected(&format!(
                    "Converged thermal state is below its fixed ambient bath: branch={:?}, model={:?}, time={time}, values={local_values:?}",
                    branch.id, branch.element,
                )),
                _ => rejected(&format!(
                    "Negative RMS detector squared voltage: branch={:?}, time={time}, volts_squared={}",
                    branch.id, local_values[state.row],
                )),
            });
        }
        let current = local_values[2];
        // Source constraint was evaluated by the equation assembler. Its stored energy
        // and dissipation are zero and do not depend on the accepted phase clock.
        let evaluation = branch.element.evaluate(
            &local_values,
            if branch.element.source_clock().is_some() {
                0.0
            } else {
                time
            },
        )?;
        let voltage = va - vb;
        let absorbed_watts = voltage * current
            + local_values[3..2 * branch.element.port_count() - 1]
                .as_chunks::<2>()
                .0
                .iter()
                .map(|pair| (pair[0] - vb) * pair[1])
                .sum::<f64>();
        power_balance_watts += absorbed_watts;
        if !absorbed_watts.is_finite() || !power_balance_watts.is_finite() {
            return Err(rejected("Non-finite branch power"));
        }
        results.push(BranchOperatingPoint {
            id: branch.id,
            voltage,
            current,
            absorbed_watts,
            observed_watts: if matches!(branch.element, Element::Meter { .. }) {
                voltage * current
            } else {
                absorbed_watts
            },
            dissipated_watts: evaluation.dissipated_watts,
            stored_joules: evaluation.stored_joules,
            local_values,
        });
    }
    Ok(DcOperatingPoint {
        voltages,
        references: layout.references.clone(),
        branches: results,
        power_balance_watts,
    })
}
