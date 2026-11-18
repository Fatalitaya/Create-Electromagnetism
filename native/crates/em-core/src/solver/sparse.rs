//! Safe, bounded sparse Gaussian elimination with row equilibration and partial pivoting.
//! Algebraically disconnected blocks are solved independently inside one candidate;
//! neither blocks nor Newton iterations commit physical time or state.
use crate::model::element::rejected;
use em_protocol::contract::Diagnostic;
use std::collections::{BTreeMap, BTreeSet};

const MAX_UNKNOWNS: usize = 8 * em_protocol::wire::MAX_BRANCHES;
const MAX_ENTRIES: usize = 1_000_000;
const MAX_WORK: usize = 100_000_000;

/// Sorted contiguous coefficients; elimination allocates by row, never by coefficient.
#[derive(Clone, Default, Debug, PartialEq)]
struct Row(Vec<(usize, f64)>);
impl Row {
    fn get(&self, col: &usize) -> Option<&f64> { self.0.binary_search_by_key(col, |e| e.0).ok().map(|i| &self.0[i].1) }
    fn get_mut(&mut self, col: &usize) -> Option<&mut f64> { self.0.binary_search_by_key(col, |e| e.0).ok().map(|i| &mut self.0[i].1) }
    fn insert(&mut self, col: usize, value: f64) {
        match self.0.binary_search_by_key(&col, |e| e.0) {
            Ok(i) => self.0[i].1 = value, Err(i) => self.0.insert(i, (col, value)),
        }
    }
    fn remove(&mut self, col: &usize) -> Option<f64> { self.0.binary_search_by_key(col, |e| e.0).ok().map(|i| self.0.remove(i).1) }
    fn iter(&self) -> impl Iterator<Item = (&usize, &f64)> { self.0.iter().map(|(c,v)| (c,v)) }
    fn keys(&self) -> impl Iterator<Item = &usize> { self.0.iter().map(|(c,_)| c) }
    fn values(&self) -> impl Iterator<Item = &f64> { self.0.iter().map(|(_,v)| v) }
    fn values_mut(&mut self) -> impl Iterator<Item = &mut f64> { self.0.iter_mut().map(|(_,v)| v) }
    fn range(&self, range: std::ops::RangeFrom<usize>) -> impl Iterator<Item = (&usize, &f64)> {
        let start = self.0.partition_point(|(c,_)| *c < range.start);
        self.0[start..].iter().map(|(c,v)| (c,v))
    }
}
impl std::ops::Index<&usize> for Row {
    type Output = f64;
    fn index(&self, col: &usize) -> &f64 { self.get(col).expect("existing pivot") }
}

// Small winding islands alternate full and half steps. Keep the row operations
// for two exact coefficient matrices, scoped to the immutable symbolic layout.
#[derive(Default)]
struct Elimination {
    scales: Vec<f64>,
    operations: Vec<RowOperation>,
}

enum RowOperation {
    Swap(usize, usize),
    Subtract { row: usize, pivot: usize, factor: f64 },
}

struct Factors {
    original: Matrix,
    upper: Matrix,
    elimination: Elimination,
}

type FactorCache = std::sync::Mutex<Vec<std::sync::Arc<Factors>>>;

pub(crate) struct Symbolic {
    factors: FactorCache,
    order: Vec<usize>, inverse: Vec<usize>, blocks: Vec<Vec<usize>>, work: usize,
}

#[derive(Clone)]
pub struct Matrix {
    rows: Vec<Row>,
    entries: usize,
}

impl Matrix {
    pub fn new(size: usize) -> Result<Self, Diagnostic> {
        if size > MAX_UNKNOWNS {
            return Err(rejected("Sparse unknown resource limit exceeded"));
        }
        Ok(Self {
            rows: vec![Row::default(); size],
            entries: 0,
        })
    }

    /// Keep row allocations while assembling new coefficients; no numeric factor is retained.
    pub(crate) fn clear(&mut self) {
        for row in &mut self.rows { row.0.clear(); }
        self.entries = 0;
    }

    /// Exact zero cancellation is removed; no numerical dropping or hidden conductance.
    pub fn add(&mut self, row: usize, col: usize, value: f64) -> Result<(), Diagnostic> {
        self.add_solver(row, col, value).map_err(Into::into)
    }

    pub(super) fn add_solver(&mut self, row: usize, col: usize, value: f64) -> Result<(), super::SolveError> {
        if row >= self.rows.len() || col >= self.rows.len() || !value.is_finite() {
            return Err(super::permanent("Invalid sparse coefficient or index"));
        }
        if value == 0.0 {
            return Ok(());
        }
        let next = self.rows[row].get(&col).copied().unwrap_or(0.0) + value;
        if !next.is_finite() {
            return Err(rejected("Non-finite sparse coefficient sum").into());
        }
        if next == 0.0 {
            if self.rows[row].remove(&col).is_some() {
                self.entries -= 1;
            }
        } else if let Some(old) = self.rows[row].get_mut(&col) {
            *old = next;
        } else {
            if self.entries == MAX_ENTRIES {
                return Err(super::permanent("Sparse fill resource limit exceeded"));
            }
            self.rows[row].insert(col, next);
            self.entries += 1;
        }
        Ok(())
    }

    pub(crate) fn row_magnitude(&self, row: usize, x: &[f64]) -> f64 {
        self.rows[row]
            .iter()
            .map(|(&col, &v)| (v * x[col]).abs())
            .sum()
    }

    pub fn nonzeros(&self) -> usize {
        self.entries
    }

    /// Components of the union of row/column dependencies. This also keeps real/imaginary
    /// AC coupling together. Future physical coupling must be stamped into this matrix.
    fn blocks(&self) -> Vec<Vec<usize>> {
        fn root(parent: &mut [usize], mut i: usize) -> usize {
            while parent[i] != i {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            i
        }
        let mut parent: Vec<_> = (0..self.rows.len()).collect();
        for (row, values) in self.rows.iter().enumerate() {
            for &col in values.keys() {
                let a = root(&mut parent, row);
                let b = root(&mut parent, col);
                parent[a.max(b)] = a.min(b);
            }
        }
        let mut blocks = BTreeMap::<usize, Vec<usize>>::new();
        for row in 0..self.rows.len() {
            blocks.entry(root(&mut parent, row)).or_default().push(row);
        }
        blocks.into_values().collect()
    }

    /// Returns no solution unless every block and the original backward residual pass.
    pub fn solve(&self, rhs: &[f64]) -> Result<Vec<f64>, Diagnostic> {
        if rhs.len() != self.rows.len() || rhs.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid sparse right-hand side"));
        }
        let symbolic = self.analyze()?;
        self.solve_symbolic(rhs, &symbolic)
    }

    pub(crate) fn analyze(&self) -> Result<Symbolic, Diagnostic> {
        let mut work = MAX_WORK;
        let order = self.minimum_degree(&mut work)?;
        let mut inverse = vec![0; order.len()];
        for (new, &old) in order.iter().enumerate() { inverse[old] = new; }
        let blocks = self.blocks().into_iter().map(|block| {
            let mut block: Vec<_> = block.into_iter().map(|old| inverse[old]).collect();
            block.sort_unstable(); block
        }).collect();
        Ok(Symbolic { factors: Default::default(), order, inverse, blocks, work })
    }

    pub(crate) fn solve_symbolic(&self, rhs: &[f64], symbolic: &Symbolic) -> Result<Vec<f64>, Diagnostic> {
        super::cooperative::complete(self.solve_symbolic_async(rhs, symbolic)).map_err(Into::into)
    }

    pub(super) async fn solve_symbolic_async(
        &self, rhs: &[f64], symbolic: &Symbolic,
    ) -> Result<Vec<f64>, super::SolveError> {
        self.solve_with_cache(rhs, symbolic, false).await
    }

    pub(super) async fn solve_with_cache(
        &self, rhs: &[f64], symbolic: &Symbolic, reuse: bool,
    ) -> Result<Vec<f64>, super::SolveError> {
        super::progress::linear_solve();
        if rhs.len() != self.rows.len() || rhs.iter().any(|v| !v.is_finite()) || symbolic.order.len() != rhs.len() {
            return Err(super::permanent("Invalid sparse right-hand side or symbolic layout"));
        }
        let order = &symbolic.order;
        let inverse = &symbolic.inverse;
        let mut permuted = Self::new(order.len())?;
        for (row, values) in self.rows.iter().enumerate() {
            super::cooperative::checkpoint().await;
            for (&col, &value) in values.iter() {
                permuted.add_solver(inverse[row], inverse[col], value)?;
            }
        }
        let b: Vec<_> = order.iter().map(|&old| rhs[old]).collect();
        let cache = reuse.then_some(&symbolic.factors);
        let solution = permuted.solve_blocks(&b, MAX_ENTRIES, symbolic.work, &symbolic.blocks, cache).await?;
        let mut x = vec![0.0; order.len()];
        for (new, &old) in order.iter().enumerate() {
            x[old] = solution[new];
        }
        Ok(x)
    }

    /// Symmetric minimum-degree ordering on the structural union. It reduces fill
    /// without assuming symmetry or positive definiteness of the numeric matrix.
    fn minimum_degree(&self, work: &mut usize) -> Result<Vec<usize>, Diagnostic> {
        let n = self.rows.len();
        let mut graph = vec![BTreeSet::new(); n];
        let mut edges = 0;
        for (row, values) in self.rows.iter().enumerate() {
            for &col in values.keys() {
                if row != col && graph[row].insert(col) {
                    graph[col].insert(row);
                    edges += 2;
                }
            }
        }
        let mut queue: BTreeSet<_> = graph
            .iter()
            .enumerate()
            .map(|(i, neighbors)| (neighbors.len(), i))
            .collect();
        let mut order = Vec::with_capacity(n);
        while let Some((_, node)) = queue.pop_first() {
            super::cooperative::check_tick_deadline()?;
            let neighbors: Vec<_> = graph[node].iter().copied().collect();
            spend(work, neighbors.len().saturating_mul(neighbors.len()).max(1))?;
            for &a in &neighbors {
                queue.remove(&(graph[a].len(), a));
            }
            for (i, &a) in neighbors.iter().enumerate() {
                super::cooperative::check_tick_deadline()?;
                graph[a].remove(&node);
                edges -= 2;
                for &b in &neighbors[i + 1..] {
                    if graph[a].insert(b) {
                        graph[b].insert(a);
                        edges += 2;
                        if edges > 2 * MAX_ENTRIES {
                            return Err(rejected("Sparse ordering fill resource limit exceeded"));
                        }
                    }
                }
            }
            for &a in &neighbors {
                queue.insert((graph[a].len(), a));
            }
            graph[node].clear();
            order.push(node);
        }
        Ok(order)
    }

    #[cfg(test)]
    fn solve_bounded(
        &self,
        rhs: &[f64],
        max_entries: usize,
        work: usize,
    ) -> Result<Vec<f64>, Diagnostic> {
        super::cooperative::complete(self.solve_blocks(rhs, max_entries, work, &self.blocks(), None)).map_err(Into::into)
    }

    async fn solve_blocks(&self, rhs: &[f64], max_entries: usize, mut work: usize, blocks: &[Vec<usize>], cache: Option<&FactorCache>) -> Result<Vec<f64>, super::SolveError> {
        let n = self.rows.len();
        if rhs.len() != n || rhs.iter().any(|v| !v.is_finite()) {
            return Err(super::permanent("Invalid sparse right-hand side"));
        }
        let cached = cache.and_then(|cache| cache.lock().ok()?.iter().find(|f| f.original.rows == self.rows).cloned());
        if let Some(factors) = cached {
            let mut b: Vec<_> = rhs.iter().zip(&factors.elimination.scales).map(|(value, scale)| value / scale).collect();
            for op in &factors.elimination.operations {
                match *op {
                    RowOperation::Swap(i, j) => b.swap(i, j),
                    RowOperation::Subtract { row, pivot, factor } => b[row] -= factor * b[pivot],
                }
            }
            return self.back_substitute(&factors.upper, &b, blocks, rhs).await;
        }
        let mut record = cache.map(|_| Elimination::default());
        let mut fill_insertions = 0;
        let mut a = self.clone();
        let mut b = rhs.to_vec();
        for (row, values) in a.rows.iter_mut().enumerate() {
            super::cooperative::checkpoint().await;
            let scale = values.values().fold(0.0_f64, |s, v| s.max(v.abs()));
            if scale == 0.0 {
                return Err(rejected("Singular system: empty equation").into());
            }
            for value in values.values_mut() {
                *value /= scale;
            }
            if let Some(record) = &mut record { record.scales.push(scale); }
            b[row] /= scale;
            if !b[row].is_finite() {
                return Err(rejected("Non-finite equilibrated right-hand side").into());
            }
        }
        let mut columns = vec![BTreeSet::new(); n];
        for (row, values) in a.rows.iter().enumerate() {
            super::cooperative::checkpoint().await;
            for &col in values.keys() { columns[col].insert(row); }
        }

        for block in blocks {
            for &col in block {
                super::cooperative::checkpoint().await;
                let remaining: Vec<_> = columns[col].range(col..).copied().collect();
                if remaining.is_empty() { return Err(rejected("Singular system: empty pivot column").into()); }
                spend(&mut work, remaining.len()).map_err(super::SolveError::Permanent)?;
                let pivot = *remaining
                    .iter()
                    .max_by(|&&i, &&j| {
                        a.rows[i]
                            .get(&col)
                            .copied()
                            .unwrap_or(0.0)
                            .abs()
                            .total_cmp(&a.rows[j].get(&col).copied().unwrap_or(0.0).abs())
                    })
                    .unwrap();
                let diagonal = a.rows[pivot].get(&col).copied().unwrap_or(0.0);
                if diagonal.abs() <= 64.0 * f64::EPSILON {
                    return Err(rejected(
                        "Singular or ill-conditioned system: dependent branch constraints",
                    ).into());
                }
                if col != pivot {
                    if let Some(record) = &mut record { record.operations.push(RowOperation::Swap(col, pivot)); }
                    for &j in a.rows[col].keys() { columns[j].remove(&col); }
                    for &j in a.rows[pivot].keys() { columns[j].remove(&pivot); }
                    a.rows.swap(col, pivot);
                    for &j in a.rows[col].keys() { columns[j].insert(col); }
                    for &j in a.rows[pivot].keys() { columns[j].insert(pivot); }
                }
                b.swap(col, pivot);
                let tail: Vec<_> = a.rows[col]
                    .range(col + 1..)
                    .map(|(&j, &v)| (j, v))
                    .collect();
                let affected: Vec<_> = columns[col].range(col + 1..).copied().collect();
                for row in affected {
                super::cooperative::checkpoint().await;
                    let Some(value) = a.rows[row].remove(&col) else {
                        continue;
                    };
                    a.entries -= 1;
                    columns[col].remove(&row);
                    let factor = value / diagonal;
                    // Row equilibration can underflow an entry to exact zero.
                    // Its pivot-column entry was removed above; no tail update remains.
                    if factor == 0.0 {
                        continue;
                    }
                    spend(&mut work, tail.len() + 1).map_err(super::SolveError::Permanent)?;
                    for (entry, &(j, v)) in tail.iter().enumerate() {
                        if entry % 128 == 0 { super::cooperative::checkpoint().await; }
                        let previous_entries = a.entries;
                        a.add_solver(row, j, -factor * v)?;
                        if a.entries > previous_entries {
                            columns[j].insert(row);
                            fill_insertions += 1;
                        } else if a.entries < previous_entries {
                            columns[j].remove(&row);
                        }
                        if a.entries > max_entries {
                            return Err(super::permanent("Sparse fill resource limit exceeded"));
                        }
                    }
                    if let Some(record) = &mut record {
                        record.operations.push(RowOperation::Subtract { row, pivot: col, factor });
                    }
                    b[row] -= factor * b[col];
                    if !b[row].is_finite() {
                        return Err(rejected("Non-finite elimination right-hand side").into());
                    }
                }
            }
        }
        super::progress::InitializationProfile::fill(fill_insertions);
        let solution = self.back_substitute(&a, &b, blocks, rhs).await?;
        if let (Some(cache), Some(elimination)) = (cache, record)
            && let Ok(mut cache) = cache.lock() {
            if cache.len() == 2 { cache.remove(0); }
            cache.push(std::sync::Arc::new(Factors { original: self.clone(), upper: a, elimination }));
        }
        Ok(solution)
    }

    async fn back_substitute(&self, a: &Matrix, b: &[f64], blocks: &[Vec<usize>], rhs: &[f64]) -> Result<Vec<f64>, super::SolveError> {
        let mut x = vec![0.0; rhs.len()];
        let mut block_norm = vec![0.0; rhs.len()];
        for block in blocks {
            for &row in block.iter().rev() {
                super::cooperative::checkpoint().await;
                let tail: f64 = a.rows[row]
                    .range(row + 1..)
                    .map(|(&col, &v)| v * x[col])
                    .sum();
                x[row] = (b[row] - tail) / a.rows[row][&row];
                if !x[row].is_finite() {
                    return Err(rejected("Non-finite linear solution").into());
                }
            }
            let norm = block
                .iter()
                .fold(0.0_f64, |norm, &row| norm.max(x[row].abs()));
            for &row in block {
                block_norm[row] = norm;
            }
        }
        for (row, values) in self.rows.iter().enumerate() {
            super::cooperative::checkpoint().await;
            let product: f64 = values.iter().map(|(&col, &v)| v * x[col]).sum();
            let scale = rhs[row].abs() + self.row_magnitude(row, &x);
            let residual = (product - rhs[row]).abs();
            // A zero-valued solution component cannot satisfy a purely relative check:
            // allow normwise floating-point roundoff as well as componentwise error.
            // Newton still checks the physical ampere/volt residual independently.
            let roundoff = 64.0
                * f64::EPSILON
                * block_norm[row]
                * values.values().map(|v| v.abs()).sum::<f64>();
            if !scale.is_finite()
                || !residual.is_finite()
                || !roundoff.is_finite()
                || residual > 1e-10 * scale.max(f64::MIN_POSITIVE) + roundoff
            {
                return Err(rejected(&format!(
                    "Linear backward residual exceeded tolerance: row={row}, residual={residual:e}, scale={scale:e}"
                )).into());
            }
        }
        Ok(x)
    }
}

fn spend(work: &mut usize, amount: usize) -> Result<(), Diagnostic> {
    *work = work
        .checked_sub(amount)
        .ok_or_else(|| rejected("Sparse elimination work budget exhausted"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resource_exhaustion_does_not_modify_inputs() {
        let mut a = Matrix::new(3).unwrap();
        for (r, c, v) in [
            (0, 0, 2.0),
            (0, 1, 1.0),
            (0, 2, 1.0),
            (1, 0, 1.0),
            (1, 1, 2.0),
            (2, 2, 1.0),
        ] {
            a.add(r, c, v).unwrap();
        }
        let before = a.rows.clone();
        assert!(
            a.solve_bounded(&[4.0, 3.0, 1.0], MAX_ENTRIES, 1)
                .unwrap_err()
                .reason
                .contains("work budget")
        );
        assert!(
            a.solve_bounded(&[4.0, 3.0, 1.0], 1, MAX_WORK)
                .unwrap_err()
                .reason
                .contains("fill resource")
        );
        assert_eq!(a.rows, before);
        assert_eq!(a.solve(&[4.0, 3.0, 1.0]).unwrap(), vec![1.0; 3]);
    }

    #[test]
    fn non_symmetric_pivot_and_disconnected_blocks() {
        let mut a = Matrix::new(4).unwrap();
        for (r, c, v) in [
            (0, 2, 2.0),
            (2, 0, 1.0),
            (2, 2, 3.0),
            (1, 1, 4.0),
            (3, 3, -2.0),
        ] {
            a.add(r, c, v).unwrap();
        }
        for (actual, expected) in a
            .solve(&[4.0, 12.0, 7.0, -8.0])
            .unwrap()
            .iter()
            .zip([1.0, 3.0, 2.0, 4.0])
        {
            assert!((actual - expected).abs() < 1e-14);
        }
    }
}
