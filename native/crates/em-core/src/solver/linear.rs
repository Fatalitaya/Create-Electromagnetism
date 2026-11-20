//! Equilibrate small matrices by row, then perform LU elimination with partial pivoting.
use crate::model::element::rejected;
use em_protocol::contract::Diagnostic;

/// Eliminate on a copy of the finite row-major square matrix and diagnose singular or nearly
/// singular systems.
pub fn solve(matrix: &[f64], rhs: &[f64]) -> Result<Vec<f64>, Diagnostic> {
    let n = rhs.len();
    if n.checked_mul(n) != Some(matrix.len()) || matrix.iter().chain(rhs).any(|x| !x.is_finite()) {
        return Err(rejected(
            "Invalid dense system dimensions or non-finite coefficients",
        ));
    }
    let mut a = matrix.to_vec();
    let mut b = rhs.to_vec();
    for row in 0..n {
        let scale = a[row * n..(row + 1) * n]
            .iter()
            .fold(0.0_f64, |s, v| s.max(v.abs()));
        if scale == 0.0 {
            return Err(rejected("Singular system: empty equation"));
        }
        for value in &mut a[row * n..(row + 1) * n] {
            *value /= scale;
        }
        b[row] /= scale;
    }
    for col in 0..n {
        let pivot = (col..n)
            .max_by(|&i, &j| a[i * n + col].abs().total_cmp(&a[j * n + col].abs()))
            .unwrap();
        if a[pivot * n + col].abs() <= 64.0 * f64::EPSILON {
            return Err(rejected(
                "Singular or ill-conditioned system: dependent branch constraints",
            ));
        }
        for j in 0..n {
            a.swap(col * n + j, pivot * n + j);
        }
        b.swap(col, pivot);
        for row in col + 1..n {
            let factor = a[row * n + col] / a[col * n + col];
            a[row * n + col] = factor;
            for j in col + 1..n {
                a[row * n + j] -= factor * a[col * n + j];
            }
            b[row] -= factor * b[col];
        }
    }
    let mut x = vec![0.0; n];
    for row in (0..n).rev() {
        let tail: f64 = (row + 1..n).map(|j| a[row * n + j] * x[j]).sum();
        x[row] = (b[row] - tail) / a[row * n + row];
    }
    if x.iter().any(|v| !v.is_finite()) {
        return Err(rejected("Non-finite linear solution"));
    }
    for row in 0..n {
        let product: f64 = (0..n).map(|j| matrix[row * n + j] * x[j]).sum();
        let scale = rhs[row].abs()
            + (0..n)
                .map(|j| (matrix[row * n + j] * x[j]).abs())
                .sum::<f64>();
        let residual = (product - rhs[row]).abs();
        if !scale.is_finite()
            || !residual.is_finite()
            || residual > 1e-10 * scale.max(f64::MIN_POSITIVE)
        {
            return Err(rejected("Linear backward residual exceeded tolerance"));
        }
    }
    Ok(x)
}
