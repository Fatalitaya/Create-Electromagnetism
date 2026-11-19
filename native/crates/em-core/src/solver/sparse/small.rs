//! Stack storage for the pivoted elimination of small, uncached island systems.
use super::Matrix;
use crate::model::element::rejected;
use crate::solver::{SolveError, cooperative::checkpoint, progress};
use std::time::Instant;

pub(super) const LIMIT: usize = 8;

pub(super) async fn solve(
    matrix: &Matrix,
    rhs: &[f64],
    blocks: &[Vec<usize>],
    timing: Option<Instant>,
) -> Result<Vec<f64>, SolveError> {
    let n = rhs.len();
    let mut a = [[0.0; LIMIT]; LIMIT];
    let mut b = [0.0; LIMIT];
    for (i, coefficients) in matrix.rows.iter().enumerate() {
        let scale = coefficients.values().fold(0.0_f64, |s, v| s.max(v.abs()));
        if scale == 0.0 {
            return Err(rejected("Singular system: empty equation").into());
        }
        for (&col, &value) in coefficients.iter() {
            a[i][col] = value / scale;
        }
        b[i] = rhs[i] / scale;
        if !b[i].is_finite() {
            return Err(rejected("Non-finite equilibrated right-hand side").into());
        }
    }

    let mut fill = 0;
    for block in blocks {
        for (k, &col) in block.iter().enumerate() {
            checkpoint().await;
            // Equal magnitudes select the last row, as in sparse partial pivoting.
            let pivot = block[k..].iter().copied().max_by(|&i, &j| {
                a[i][col].abs().total_cmp(&a[j][col].abs())
            }).expect("pivot column belongs to its block");
            let diagonal = a[pivot][col];
            if diagonal.abs() <= 64.0 * f64::EPSILON {
                return Err(rejected("Singular or ill-conditioned system: dependent branch constraints").into());
            }
            a.swap(col, pivot);
            b.swap(col, pivot);
            for &row in &block[k + 1..] {
                let factor = a[row][col] / diagonal;
                if factor == 0.0 { continue; }
                for &j in &block[k + 1..] {
                    let update = -factor * a[col][j];
                    if update == 0.0 { continue; }
                    fill += usize::from(a[row][j] == 0.0);
                    a[row][j] += update;
                    if !a[row][j].is_finite() {
                        return Err(rejected("Non-finite sparse coefficient sum").into());
                    }
                }
                b[row] -= factor * b[col];
                if !b[row].is_finite() {
                    return Err(rejected("Non-finite elimination right-hand side").into());
                }
            }
        }
    }
    progress::InitializationProfile::fill(fill);
    progress::factored(timing, n);
    let timing = progress::timer();
    let mut x = vec![0.0; n];
    let mut block_norm = [0.0; LIMIT];
    for block in blocks {
        let mut norm = 0.0_f64;
        for (k, &row) in block.iter().enumerate().rev() {
            let tail: f64 = block[k + 1..].iter().map(|&j| a[row][j] * x[j]).sum();
            x[row] = (b[row] - tail) / a[row][row];
            if !x[row].is_finite() {
                return Err(rejected("Non-finite linear solution").into());
            }
            norm = norm.max(x[row].abs());
        }
        for &row in block {
            block_norm[row] = norm;
        }
    }
    matrix.check_solution(&x, &block_norm, rhs).await?;
    progress::substituted(timing, false);
    Ok(x)
}
