//! Small-signal frequency analysis of the shared constitutive Jacobians at DC bias.
//! Phasors are peak amplitudes with the exp(+j omega t) convention, never RMS.
use crate::{
    model::{
        DeviceModel,
        element::{Element, rejected},
    },
    solver::dc::{self, DcBranch},
    topology::Layout,
};
use em_protocol::contract::{Diagnostic, Id};
use std::collections::HashMap;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Phasor {
    pub real: f64,
    pub imaginary: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BranchResponse {
    pub id: Id,
    pub voltage: Phasor,
    pub current: Phasor,
}

#[derive(Clone, Debug, PartialEq)]
pub struct FrequencyResponse {
    pub hertz: f64,
    pub branches: Vec<BranchResponse>,
}

/// Excitations are explicit independent voltage-source peak perturbations.
/// Unspecified sources have zero perturbation; their DC bias remains unchanged.
/// Time-varying sources are rejected: a sampled transient is not a DC bias point.
pub fn solve(
    branches: &[DcBranch],
    hertz: f64,
    excitations: &[(Id, Phasor)],
) -> Result<FrequencyResponse, Diagnostic> {
    let omega = std::f64::consts::TAU * hertz;
    if !hertz.is_finite() || hertz < 0.0 || !omega.is_finite() {
        return Err(rejected("AC frequency must be nonnegative and finite"));
    }
    if branches.iter().any(|b| !b.element.supports_stationary_ac_bias()) {
        return Err(rejected(
            "AC bias requires stationary sources; use a DC source with explicit AC excitation",
        ));
    }
    let layout = Layout::compile(branches)?;
    if excitations.len() > branches.len() {
        return Err(rejected("Too many AC excitations"));
    }
    let mut sources = HashMap::new();
    for &(id, amplitude) in excitations {
        if !amplitude.real.is_finite()
            || !amplitude.imaginary.is_finite()
            || sources.insert(id, amplitude).is_some()
            || !branches
                .iter()
                .any(|b| b.id == id && matches!(b.element, Element::DcVoltageSource { .. }))
        {
            return Err(rejected(
                "AC excitation must identify a unique voltage source with finite amplitude",
            ));
        }
    }
    let bias = dc::solve(branches)?;
    let n = layout.size;
    let size = 2 * n;
    // [F -omega Q; omega Q F] acts on [Re(x), Im(x)].
    let mut matrix = super::sparse::Matrix::new(size)?;
    let mut rhs = vec![0.0; size];
    for (i, branch) in branches.iter().enumerate() {
        let e = branch
            .element
            .evaluate(&bias.branches[i].local_values, 0.0)?;
        let mapping = &layout.mappings[i];
        for (lr, row) in mapping.iter().enumerate() {
            if let Some(row) = *row {
                for (lc, col) in mapping.iter().enumerate() {
                    if let Some(col) = *col {
                        let f = e.residual_jacobian[lr * mapping.len() + lc];
                        let q = omega * e.charge_jacobian[lr * mapping.len() + lc];
                        matrix.add(row, col, f)?;
                        matrix.add(row, col + n, -q)?;
                        matrix.add(row + n, col, q)?;
                        matrix.add(row + n, col + n, f)?;
                    }
                }
            }
        }
        if let Some(amplitude) = sources.get(&branch.id) {
            let row = layout.mappings[i][2].expect("branch current equation");
            rhs[row] = amplitude.real;
            rhs[row + n] = amplitude.imaginary;
        }
    }
    let x = matrix.solve(&rhs)?;
    let mut results = Vec::with_capacity(branches.len());
    for (i, branch) in branches.iter().enumerate() {
        let real = layout.local(i, &x[..n]);
        let imaginary = layout.local(i, &x[n..]);
        let voltage = Phasor {
            real: real[0] - real[1],
            imaginary: imaginary[0] - imaginary[1],
        };
        if !voltage.real.is_finite() || !voltage.imaginary.is_finite() {
            return Err(rejected("Non-finite AC voltage"));
        }
        results.push(BranchResponse {
            id: branch.id,
            voltage,
            current: Phasor {
                real: real[2],
                imaginary: imaginary[2],
            },
        });
    }
    Ok(FrequencyResponse {
        hertz,
        branches: results,
    })
}
