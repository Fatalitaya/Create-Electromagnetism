use super::*;
use crate::solver::{SolveError, cooperative::checkpoint, sparse::Matrix};
use crate::topology::series::SeriesPorts;

/// Assemble the actual discrete equations. A prescribed storage current at
/// initialization has no voltage pivot and is left to the original solver.
pub(super) async fn solve_ports(
    branches: &[DcBranch],
    layout: &Layout,
    ports: &SeriesPorts,
    cache_key: Option<(&std::sync::Arc<Vec<DcBranch>>, u64)>,
    stamp_rhs: &impl Fn(usize, &DcBranch, &mut [f64]) -> Result<bool, SolveError>,
    equation: &impl Fn(&DcBranch) -> Result<Evaluation, SolveError>,
) -> Result<Option<Vec<f64>>, SolveError> {
    if ports.paths.iter().any(|p| p.feedback.is_some()) {
        return Ok(None);
    }
    let cached =
        cache_key.and_then(|(owner, step)| ports.coordinates.symbolic.prepared(owner, step));
    let mut matrix = if cached.is_some() {
        None
    } else {
        Some(Matrix::new(ports.kept.len())?)
    };
    let mut full_rhs = vec![0.0; layout.size];
    let Some(laws) = PortLaws::compile(branches, ports, equation).await? else {
        return Ok(None);
    };
    for &i in &ports.ordinary {
        checkpoint().await;
        let map = &ports.coordinates.mappings[i];
        if map[2].is_none() {
            let (g, offset) = laws.segments[i];
            for (node, sign) in map[..2].iter().zip([1.0, -1.0]) {
                let Some(r) = *node else { continue };
                full_rhs[ports.kept[r]] -= sign * offset;
                if let Some(matrix) = matrix.as_mut() {
                    for (col, polarity) in map[..2].iter().zip([1.0, -1.0]) {
                        if let Some(c) = *col {
                            matrix.add_solver(r, c, sign * polarity * g)?;
                        }
                    }
                }
            }
            continue;
        }
        if cached.is_some() && stamp_rhs(i, &branches[i], &mut full_rhs)? {
            continue;
        }
        let mut e = equation(&branches[i])?;
        ports.project(i, &mut e);
        for (r, &row) in map.iter().enumerate() {
            let Some(row) = row else { continue };
            full_rhs[ports.kept[row]] -= e.residual[r];
            if let Some(matrix) = matrix.as_mut() {
                for (c, &col) in map.iter().enumerate() {
                    if let Some(col) = col {
                        let v = e.residual_jacobian[r * map.len() + c];
                        if v != 0.0 {
                            matrix.add_solver(row, col, v)?;
                        }
                    }
                }
            }
        }
    }
    for (path, &(impedance, source)) in ports.paths.iter().zip(&laws.ports) {
        full_rhs[path.current] = source;
        if let Some(matrix) = matrix.as_mut() {
            ports.stamp_path(matrix, path, impedance)?;
        }
    }
    let rhs: Vec<_> = ports.kept.iter().map(|&r| full_rhs[r]).collect();
    let compact = if let Some(matrix) = cached {
        matrix.solve(&rhs, &ports.coordinates.symbolic).await?
    } else {
        let matrix = matrix.expect("matrix on first assembly");
        if let Some((owner, step)) = cache_key {
            ports
                .coordinates
                .symbolic
                .prepare(owner, step, matrix)
                .await?
                .solve(&rhs, &ports.coordinates.symbolic)
                .await?
        } else {
            matrix
                .solve_symbolic_async(&rhs, &ports.coordinates.symbolic)
                .await?
        }
    };
    let Some(mut full) = ports
        .recover(&compact, &layout.mappings, |segment, current| {
            let (ohms, volts) = laws.segments[segment.branch];
            ohms * current + volts
        })
        .await
    else {
        return Ok(None);
    };
    laws.restore_shunts(ports, layout, &mut full);
    for &i in &ports.thermal {
        let e = equation(&branches[i])?;
        full[layout.mappings[i][3].unwrap()] = -e.residual[3] / e.residual_jacobian[15];
    }
    Ok(Some(full))
}

pub(in crate::solver) struct PortLaws {
    pub ports: Vec<(f64, f64)>,
    pub segments: Vec<(f64, f64)>,
}

impl PortLaws {
    pub fn restore_shunts(&self, ports: &SeriesPorts, layout: &Layout, full: &mut [f64]) {
        for &i in &ports.shunts {
            let map = &layout.mappings[i];
            let voltage = map[0].map_or(0.0, |r| full[r]) - map[1].map_or(0.0, |r| full[r]);
            let (g, offset) = self.segments[i];
            full[map[2].unwrap()] = g * voltage + offset;
        }
    }

    pub async fn compile(
        branches: &[DcBranch],
        ports: &SeriesPorts,
        equation: impl Fn(&DcBranch) -> Result<Evaluation, SolveError>,
    ) -> Result<Option<Self>, SolveError> {
        let mut segments = vec![(0.0, 0.0); branches.len()];
        let mut totals = Vec::with_capacity(ports.paths.len());
        for path in &ports.paths {
            let mut impedance = 0.0;
            let mut source = 0.0;
            for segment in &path.segments {
                checkpoint().await;
                let i = segment.branch;
                let (ohms, volts) = if let Element::Resistor { ohms } = branches[i].element {
                    (ohms, 0.0)
                } else if let Element::ThermalResistor(m) = branches[i].element {
                    (
                        if m.coefficient_per_kelvin == 0.0 {
                            m.ambient_ohms
                        } else {
                            0.0
                        },
                        0.0,
                    )
                } else {
                    let e = equation(&branches[i])?;
                    let a = e.residual_jacobian[6];
                    if !a.is_finite() || a == 0.0 || a != -e.residual_jacobian[7] {
                        return Ok(None);
                    }
                    (-e.residual_jacobian[8] / a, -e.residual[2] / a)
                };
                segments[i] = (ohms, volts);
                impedance += ohms;
                source += segment.sign * volts;
            }
            if !impedance.is_finite() || !source.is_finite() {
                return Ok(None);
            }
            totals.push((impedance, source));
        }
        for &i in &ports.shunts {
            let e = equation(&branches[i])?;
            let row = &e.residual_jacobian[6..9];
            if row[2] != 1.0 || row[0] != -row[1] || !row[0].is_finite() {
                return Ok(None);
            }
            segments[i] = (-row[0], -e.residual[2]);
        }
        Ok(Some(Self {
            ports: totals,
            segments,
        }))
    }
}
