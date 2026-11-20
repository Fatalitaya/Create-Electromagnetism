//! Statically condense series nodes while keeping observations, probes and checkpoints in full MNA
//! coordinates.
use super::{ColumnRows, FactorCache, Matrix, Row, Symbolic, MAX_ENTRIES, spend};
use crate::solver::{SolveError, cooperative::checkpoint};

struct Pivot {
    row: usize,
    column: usize,
    diagonal: f64,
    tail: Row,
    updates: Vec<(usize, f64)>,
}

pub(super) struct Reduced {
    matrix: Matrix,
    kept: Vec<usize>,
    blocks: Vec<Vec<usize>>,
    work: usize,
    pivots: Vec<Pivot>,
}

impl Reduced {
    pub(super) async fn compile(original: &Matrix, symbolic: &Symbolic) -> Result<Option<Self>, SolveError> {
        if symbolic.pivots.is_empty() && symbolic.contacts.is_empty() { return Ok(None); }
        let n = original.rows.len();
        let mut matrix = original.clone();
        let mut columns = ColumnRows::new(n);
        let mut affected = Vec::new();
        for (row, entries) in matrix.rows.iter().enumerate() {
            for &col in entries.keys() { columns.insert(col, row); }
        }
        let mut removed = vec![false; n];
        let mut pivots = Vec::new();
        let mut work = symbolic.work;
        let mut retained_entries = 0usize;
        let pairs = symbolic.pivots.iter().copied()
            .chain(symbolic.contacts.iter().map(|&current| (current, current)));
        for (node, current) in pairs {
            checkpoint().await;
            // Verify that KCL determines current and the constitutive row determines voltage.
            // Storage initialization may change these constraints.
            let kcl = &matrix.rows[node];
            let diagonal_pivot = node == current;
            if diagonal_pivot {
                if removed[current] || kcl.get(&current).is_none() { continue; }
            } else if kcl.get(&current).is_none_or(|v| v.abs() != 1.0)
                || kcl.get(&node).is_some()
                || matrix.rows[current].get(&node).is_none_or(|v| v.abs() != 1.0)
            { continue; }
            let count = if diagonal_pivot { 1 } else { 2 };
            for (row, column) in [(node, current), (current, node)].into_iter().take(count) {
                let mut tail = std::mem::take(&mut matrix.rows[row]);
                matrix.entries -= tail.0.len();
                for &col in tail.keys() { columns.remove(col, row); }
                let diagonal = tail.remove(&column).expect("series pivot checked");
                let mut updates = Vec::new();
                columns.rows_from(column, 0, &mut affected);
                columns.clear(column);
                for &target in &affected {
                    checkpoint().await;
                    let factor = matrix.rows[target].remove(&column).expect("indexed coefficient") / diagonal;
                    matrix.entries -= 1;
                    if spend(&mut work, tail.0.len() + 1).is_err() { return Ok(None); }
                    for (&col, &value) in tail.iter() {
                        let previous = matrix.entries;
                        if matrix.add_solver(target, col, -factor * value).is_err() { return Ok(None); }
                        if matrix.entries > previous { columns.insert(col, target); }
                        if matrix.entries < previous { columns.remove(col, target); }
                    }
                    updates.push((target, factor));
                }
                retained_entries += tail.0.len() + updates.len();
                if retained_entries > MAX_ENTRIES { return Ok(None); }
                pivots.push(Pivot { row, column, diagonal, tail, updates });
            }
            removed[node] = true;
            removed[current] = true;
        }
        if pivots.is_empty() { return Ok(None); }
        let kept: Vec<_> = symbolic.order.iter().copied().filter(|&i| !removed[i]).collect();
        let mut inverse = vec![usize::MAX; n];
        let mut reduced = Matrix::new(kept.len())?;
        for (i, &row) in kept.iter().enumerate() { inverse[row] = i; }
        for (i, &row) in kept.iter().enumerate() {
            checkpoint().await;
            for (&col, &value) in matrix.rows[row].iter() {
                reduced.add_solver(i, inverse[col], value)?;
            }
        }
        let blocks = symbolic.blocks.iter().filter_map(|block| {
            let indices: Vec<_> = block.iter().map(|&i| inverse[symbolic.order[i]])
                .filter(|&i| i != usize::MAX).collect();
            (!indices.is_empty()).then_some(indices)
        }).collect();
        Ok(Some(Self { matrix: reduced, kept, blocks, work, pivots }))
    }

    pub(super) async fn solve(&self, original: &Matrix, rhs: &[f64], cache: Option<&FactorCache>)
        -> Result<Option<Vec<f64>>, SolveError>
    {
        if rhs.len() != original.rows.len() { return Err(crate::solver::permanent("Invalid series system dimension")); }
        let mut b = rhs.to_vec();
        for pivot in &self.pivots {
            checkpoint().await;
            let source = b[pivot.row];
            for &(row, factor) in &pivot.updates { b[row] -= factor * source; }
        }
        let condensed_rhs: Vec<_> = self.kept.iter().map(|&i| b[i]).collect();
        crate::solver::progress::linear_solve();
        let solved = match self.matrix.solve_blocks(&condensed_rhs, MAX_ENTRIES, self.work, &self.blocks, cache).await {
            Ok(values) => values,
            Err(_) => return Ok(None),
        };
        let mut x = vec![0.0; b.len()];
        for (&i, value) in self.kept.iter().zip(solved) { x[i] = value; }
        for pivot in self.pivots.iter().rev() {
            checkpoint().await;
            let tail: f64 = pivot.tail.iter().map(|(&j, &v)| v * x[j]).sum();
            x[pivot.column] = (b[pivot.row] - tail) / pivot.diagonal;
        }
        // If cancellation in recovered internal voltages violates the original residual check,
        // retry with full pivoted LU.
        for (row, &value) in rhs.iter().enumerate() {
            checkpoint().await;
            let residual = (original.row_product(row, &x) - value).abs();
            let scale = value.abs() + original.row_magnitude(row, &x);
            if !scale.is_finite() || !residual.is_finite() || residual > 1e-10 * scale.max(f64::MIN_POSITIVE) {
                return Ok(None);
            }
        }
        Ok(Some(x))
    }
}
