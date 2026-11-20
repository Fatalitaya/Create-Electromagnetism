//! Solve the DC operating point for the supplied topology and bias.
use crate::model::{
    DeviceModel, Evaluation,
    element::{Element, rejected},
};
use crate::topology::Layout;
use em_protocol::contract::{Diagnostic, Id, PortId};
use std::collections::HashMap;

/// A stable port represents each equipotential node. The caller first resolves geometric wiring and
/// merges ideal connections.
#[derive(Clone, Debug, PartialEq)]
pub struct DcBranch {
    pub id: Id,
    pub positive: PortId,
    pub negative: PortId,
    /// Additional terminals follow model order and map to matrix rows during assembly.
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
    /// Branch power in watts is positive for absorption and negative for supply.
    pub absorbed_watts: f64,
    pub observed_watts: f64,
    pub dissipated_watts: f64,
    pub stored_joules: f64,
    /// Local order is `[v0, v1, i0, v2, i2, …]`, with port 1 as the common return.
    pub local_values: Vec<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DcOperatingPoint {
    /// Each connected component has its own voltage reference.
    pub voltages: HashMap<PortId, f64>,
    pub references: Vec<PortId>,
    pub branches: Vec<BranchOperatingPoint>,
    /// Algebraic sum of absorbed branch powers, in watts.
    pub power_balance_watts: f64,
}

/// Choose one voltage reference per component and run a bounded sparse solve without changing
/// physical state.
pub fn solve(branches: &[DcBranch]) -> Result<DcOperatingPoint, Diagnostic> {
    let layout = Layout::compile(branches)?;
    equilibrium(branches, &layout)
}

pub(crate) fn equilibrium(branches: &[DcBranch], layout: &Layout) -> Result<DcOperatingPoint, Diagnostic> {
    if branches.iter().any(|b| b.element.is_nonlinear()) {
        return super::newton::solve_compiled(branches, 0.0, None, layout, |b, x| b.element.evaluate(x, 0.0));
    }
    solve_compiled(branches, 0.0, layout, |branch| {
        branch.element.evaluate(&vec![0.0; branch.element.unknown_count()], 0.0)
    })
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
    let x = solve_compiled_values_async(branches, layout, None, |_, _, _| Ok(false), equation).await?;
    point(branches, time, layout, &x).await.map_err(Into::into)
}

pub(super) async fn solve_compiled_values_async(
    branches: &[DcBranch],
    layout: &Layout,
    cache_key: Option<(&std::sync::Arc<Vec<DcBranch>>, u64)>,
    stamp_rhs: impl Fn(usize, &DcBranch, &mut [f64]) -> Result<bool, super::SolveError>,
    equation: impl Fn(&DcBranch) -> Result<Evaluation, super::SolveError>,
) -> Result<Vec<f64>, super::SolveError> {
    let n = layout.size;
    let timing = super::progress::timer();
    let cached = cache_key.and_then(|(owner, step)| layout.symbolic.prepared(owner, step));
    let mut matrix = if cached.is_none() { Some(super::sparse::Matrix::new(n)?) } else { None };
    let mut rhs = vec![0.0; n];
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        if cached.is_some() && stamp_rhs(i, branch, &mut rhs)? { continue; }
        let mapping = &layout.mappings[i];
        let evaluation = equation(branch)?;
        for (local_row, row) in mapping.iter().enumerate() {
            if let Some(row) = *row {
                rhs[row] -= evaluation.residual[local_row];
            }
        }
        if let Some(matrix) = &mut matrix { layout.stamp(i, &evaluation.residual_jacobian, matrix)?; }
    }
    super::progress::assembled(timing);
    if let Some(cached) = cached {
        return cached.solve(&rhs, &layout.symbolic).await;
    }
    let matrix = matrix.expect("assembled on cache miss");
    if let Some((owner, step)) = cache_key {
        return layout.symbolic.prepare(owner, step, matrix).await?.solve(&rhs, &layout.symbolic).await;
    }
    matrix.solve_symbolic_async(&rhs, &layout.symbolic).await
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
    let mut results = Vec::with_capacity(branches.len());
    let mut power_balance_watts = 0.0;
    for (i, branch) in branches.iter().enumerate() {
        super::cooperative::checkpoint().await;
        let local_values = layout.local(i, x);
        let va = local_values[0];
        let vb = local_values[1];
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
        // Assembly already evaluated the source constraint. An ideal source has zero internal
        // storage and dissipation.
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
