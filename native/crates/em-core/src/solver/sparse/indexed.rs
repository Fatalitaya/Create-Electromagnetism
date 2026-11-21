//! A bounded numeric workspace for medium Newton systems. Masks describe the live entries;
//! inactive numeric slots can keep their values across factorizations.
use super::*;
use crate::solver::{SolveError, cooperative::checkpoint, progress};
use std::time::Instant;

#[derive(Default)]
struct Workspace {
    values: Vec<f64>,
    rows: Vec<[u128; 4]>,
    columns: Vec<[u128; 4]>,
    order: Vec<usize>,
}

thread_local! {
    static WORK: std::cell::Cell<Workspace> = std::cell::Cell::default();
}

fn indices(mask: [u128; 4], start: usize) -> impl Iterator<Item = usize> {
    mask.into_iter()
        .enumerate()
        .skip(start / 128)
        .flat_map(move |(word, mut bits)| {
            if word == start / 128 {
                bits &= u128::MAX << (start % 128);
            }
            std::iter::from_fn(move || {
                if bits == 0 {
                    return None;
                }
                let index = word * 128 + bits.trailing_zeros() as usize;
                bits &= bits - 1;
                Some(index)
            })
        })
}

pub(super) async fn solve(
    matrix: &Matrix,
    rhs: &[f64],
    blocks: &[Vec<usize>],
    mut work: usize,
    timing: Option<Instant>,
    reduced: bool,
) -> Result<Vec<f64>, SolveError> {
    let n = rhs.len();
    let mut scratch = WORK.take();
    scratch.values.resize(n * n, 0.0);
    scratch.rows.clear();
    scratch.rows.resize(n, [0; 4]);
    scratch.columns.clear();
    scratch.columns.resize(n, [0; 4]);
    scratch.order.clear();
    scratch.order.extend(0..n);
    let Workspace {
        values,
        rows,
        columns,
        order,
    } = &mut scratch;
    let mut b = rhs.to_vec();
    for (i, row) in matrix.rows.iter().enumerate() {
        checkpoint().await;
        let scale = row.values().fold(0.0_f64, |s, v| s.max(v.abs()));
        if scale == 0.0 {
            return Err(rejected("Singular system: empty equation").into());
        }
        let scale = crate::solver::Divisor::new(scale);
        for &(j, value) in &row.0 {
            values[i * n + j] = scale.divide(value);
            rows[i][j / 128] |= 1 << (j % 128);
            columns[j][i / 128] |= 1 << (i % 128);
        }
        b[i] = scale.divide(b[i]);
        if !b[i].is_finite() {
            return Err(rejected("Non-finite equilibrated right-hand side").into());
        }
    }
    let mut tail = Vec::new();
    let mut fill = 0;
    for block in blocks {
        for &col in block {
            checkpoint().await;
            let candidates = columns[col];
            let mut peak = 0.0;
            let mut pivot = None;
            for row in indices(candidates, col) {
                spend(&mut work, 1).map_err(SolveError::Permanent)?;
                let value = values[order[row] * n + col];
                if value.abs() >= peak {
                    peak = value.abs();
                    pivot = Some(row);
                }
            }
            let pivot = pivot.ok_or_else(|| rejected("Singular system: empty pivot column"))?;
            if peak <= 64.0 * f64::EPSILON {
                return Err(rejected(
                    "Singular or ill-conditioned system: dependent branch constraints",
                )
                .into());
            }
            if pivot != col {
                // Column masks only have to track uneliminated rows.
                for j in indices(rows[order[pivot]], col) {
                    columns[j][pivot / 128] &= !(1 << (pivot % 128));
                }
                for j in indices(rows[order[col]], col) {
                    columns[j][pivot / 128] |= 1 << (pivot % 128);
                }
                order.swap(col, pivot);
                b.swap(col, pivot);
            }
            let pivot_row = order[col];
            let pivot_divisor = crate::solver::Divisor::new(values[pivot_row * n + col]);
            tail.clear();
            tail.extend(indices(rows[pivot_row], col + 1).map(|j| (j, values[pivot_row * n + j])));
            for row in indices(columns[col], col + 1) {
                checkpoint().await;
                let physical = order[row];
                rows[physical][col / 128] &= !(1 << (col % 128));
                let factor = pivot_divisor.divide(values[physical * n + col]);
                if factor == 0.0 {
                    continue;
                }
                spend(&mut work, tail.len() + 1).map_err(SolveError::Permanent)?;
                for &(j, v) in &tail {
                    let bit = 1_u128 << (j % 128);
                    let exists = rows[physical][j / 128] & bit != 0;
                    let slot = &mut values[physical * n + j];
                    let delta = -factor * v;
                    if delta == 0.0 {
                        continue;
                    }
                    *slot = if exists { *slot + delta } else { delta };
                    if !slot.is_finite() {
                        return Err(rejected("Non-finite sparse coefficient sum").into());
                    }
                    if !exists {
                        rows[physical][j / 128] |= bit;
                        columns[j][row / 128] |= 1 << (row % 128);
                        fill += 1;
                    } else if *slot == 0.0 {
                        rows[physical][j / 128] &= !bit;
                        columns[j][row / 128] &= !(1 << (row % 128));
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
    progress::factored(timing, n, reduced);
    let timing = progress::timer();
    let mut x = vec![0.0; n];
    let mut norms = vec![0.0; n];
    for block in blocks {
        let mut norm = 0.0_f64;
        for &i in block.iter().rev() {
            checkpoint().await;
            let row = order[i];
            let sum: f64 = indices(rows[row], i + 1)
                .map(|j| values[row * n + j] * x[j])
                .sum();
            x[i] = (b[i] - sum) / values[row * n + i];
            if !x[i].is_finite() {
                return Err(rejected("Non-finite linear solution").into());
            }
            norm = norm.max(x[i].abs());
        }
        for &i in block {
            norms[i] = norm;
        }
    }
    WORK.set(scratch);
    matrix.check_solution(&x, &norms, rhs).await?;
    progress::substituted(timing, false);
    Ok(x)
}
