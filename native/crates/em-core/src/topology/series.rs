//! A path through degree-two nodes carries a single signed current. Its port
//! voltage is the sum of the oriented constitutive voltage drops. Reference
//! nodes and all terminals of other models split paths, including sense inputs.
use crate::model::element::Element;
use crate::solver::dc::DcBranch;
use crate::solver::{SolveError, cooperative::checkpoint, sparse::Matrix};
use em_protocol::contract::Diagnostic;

pub(crate) struct Segment {
    pub branch: usize,
    pub sign: f64,
    end: Option<usize>,
}

pub(crate) struct Path {
    pub feedback: Option<usize>, // Index in segments of a temperature-dependent series resistor.
    pub segments: Vec<Segment>,
    pub terminals: [Option<usize>; 2],
    pub current: usize,
}

/// A zero-voltage field sensor in series with a winding reads its signed
/// current. The private pin is equipotential with the far end of the chain.
#[derive(Clone, Copy)]
pub(crate) struct Sensor {
    node: usize,
    terminal: Option<usize>,
    winding_current: usize,
    pub sign: f64,
}

/// Full rows remain the coordinate system for snapshots and observations.
pub(crate) struct SeriesPorts {
    pub paths: Vec<Path>,
    pub ordinary: Vec<usize>,
    pub kept: Vec<usize>,
    pub rows: Vec<Option<usize>>,
    pub coordinates: super::Coordinates,
    // Local return current is minus the driven port current, or zero for a sense pin.
    pub sensors: Vec<Option<Sensor>>,
    pub thermal: Vec<usize>,
    pub returns: Vec<Option<(usize, Option<usize>)>>,
    pub shunts: Vec<usize>,
    pub path_for: Vec<Option<usize>>,
}

impl SeriesPorts {
    pub(super) fn compile(
        branches: &[DcBranch],
        maps: &[Vec<Option<usize>>],
        nodes: usize,
        units: &[crate::model::UnknownUnit],
    ) -> Result<Option<Self>, Diagnostic> {
        let size = units.len();
        // In the small six-pulse alternator, full-window replays (especially
        // trip localization) favor the existing small-matrix/bridge solvers.
        if size <= u128::BITS as usize
            && branches
                .iter()
                .filter(|b| matches!(b.element, Element::Rectifier(_)))
                .count()
                == 6
            && branches
                .iter()
                .filter(|b| matches!(b.element, Element::AcShaft { .. }))
                .count()
                == 3
            && branches.iter().all(|b| {
                matches!(
                    b.element,
                    Element::AcShaft { .. }
                        | Element::Rectifier(_)
                        | Element::ThermalResistor(_)
                        | Element::Resistor { .. }
                        | Element::Inductor { .. }
                        | Element::Capacitor { .. }
                )
            })
        {
            return Ok(None);
        }
        let eligible = |b: &DcBranch| {
            matches!(
                b.element,
                Element::Resistor { .. }
                    | Element::DcVoltageSource { .. }
                    | Element::SineVoltageSource { .. }
                    | Element::Audio(_)
                    | Element::Impulse { .. }
                    | Element::ShaftBoundary { .. }
                    | Element::AcShaft { .. }
                    | Element::Inductor { .. }
                    | Element::Capacitor { .. }
                    | Element::ThermalResistor(_)
            )
        };
        let mut incident = vec![Vec::new(); nodes];
        for (i, map) in maps.iter().enumerate() {
            for row in map.iter().flatten().filter(|&&row| row < nodes) {
                incident[*row].push(i);
            }
        }
        let next = |node: Option<usize>, incoming: usize| {
            let [a, b] = incident.get(node?)?.as_slice() else {
                return None;
            };
            let other = if *a == incoming { *b } else { *a };
            eligible(&branches[other]).then_some(other)
        };
        let mut visited = vec![false; branches.len()];
        let mut included = vec![false; branches.len()];
        let mut removed = vec![false; size];
        let mut paths = Vec::new();
        for (i, b) in branches.iter().enumerate() {
            if visited[i] || !eligible(b) {
                continue;
            }
            let Some(side) = (0..2).find(|&s| next(maps[i][s], i).is_none()) else {
                continue;
            };
            let start = maps[i][side];
            let mut node = start;
            let mut branch = i;
            let mut segments = Vec::new();
            loop {
                visited[branch] = true;
                let forward = maps[branch][0] == node;
                node = maps[branch][usize::from(forward)];
                segments.push(Segment {
                    branch,
                    sign: if forward { 1.0 } else { -1.0 },
                    end: node,
                });
                let Some(other) = next(node, branch) else {
                    break;
                };
                branch = other;
            }
            // Keep each feedback temperature as an explicit Newton coordinate.
            // Split at a second such resistor so local stamps remain bounded.
            let mut remaining = segments.into_iter().peekable();
            let mut from = start;
            while remaining.peek().is_some() {
                let mut group = Vec::new();
                let mut feedback = None;
                while let Some(segment) = remaining.peek() {
                    if matches!(branches[segment.branch].element, Element::ThermalResistor(m) if m.coefficient_per_kelvin != 0.0)
                    {
                        if feedback.is_some() {
                            break;
                        }
                        feedback = Some(group.len());
                    }
                    group.push(remaining.next().unwrap());
                }
                let end = group.last().unwrap().end;
                if group.len() > 1 {
                    let current = maps[group[0].branch][2].unwrap();
                    for (k, seg) in group.iter().enumerate() {
                        included[seg.branch] = true;
                        if k > 0 {
                            removed[maps[seg.branch][2].unwrap()] = true;
                        }
                        if k + 1 < group.len() {
                            removed[seg.end.unwrap()] = true;
                        }
                    }
                    paths.push(Path {
                        segments: group,
                        terminals: [from, end],
                        current,
                        feedback,
                    });
                }
                from = end;
            }
        }
        let returns: Vec<_> = branches
            .iter()
            .map(|b| match b.element {
                Element::Windings(_)
                | Element::FieldShaft { .. }
                | Element::FieldAcShaft { .. }
                | Element::Mixer { .. }
                | Element::RadioTransmitter { .. }
                | Element::Relay { .. }
                | Element::Converter { .. } => Some((6, Some(4))),
                Element::Meter { .. } => Some((4, Some(2))),
                Element::BatteryContact { .. } => Some((4, None)),
                _ => None,
            })
            .collect();
        for (i, role) in returns.iter().enumerate() {
            if let Some((output, _)) = role {
                removed[maps[i][*output].unwrap()] = true;
            }
        }
        let thermal: Vec<_> = branches
            .iter()
            .enumerate()
            .filter_map(|(i, b)| {
                matches!(b.element, Element::ThermalResistor(m) if m.coefficient_per_kelvin == 0.0)
                    .then_some(i)
            })
            .collect();
        for &i in &thermal {
            removed[maps[i][3].unwrap()] = true;
        }
        // Keep each junction's conduction and heat law. Its parallel charge
        // branch can inject displacement current directly at the same pins.
        let junctions: std::collections::HashSet<_> = branches
            .iter()
            .filter(|b| matches!(b.element, Element::Rectifier(_) | Element::Diode { .. }))
            .flat_map(|b| [(b.positive, b.negative), (b.negative, b.positive)])
            .collect();
        let shunts: Vec<_> = branches
            .iter()
            .enumerate()
            .filter_map(|(i, b)| {
                (!included[i]
                    && matches!(b.element, Element::Capacitor { .. })
                    && junctions.contains(&(b.positive, b.negative)))
                .then_some(i)
            })
            .collect();
        for &i in &shunts {
            removed[maps[i][2].unwrap()] = true;
        }
        let mut sensors = vec![None; branches.len()];
        for (i, winding) in branches.iter().enumerate() {
            if !matches!(winding.element, Element::Windings(_)) {
                continue;
            }
            // Positive currents enter the dotted terminal of each winding.
            for (pin, current, orientation) in
                [(0, 2, 1.0), (1, 2, -1.0), (3, 4, 1.0), (5, 4, -1.0)]
            {
                let mut node = maps[i][pin];
                let mut previous = i;
                let mut chain = Vec::new();
                while let Some(private) = node {
                    let [a, b] = incident[private].as_slice() else {
                        break;
                    };
                    let sensor = if *a == previous { *b } else { *a };
                    if !matches!(
                        branches[sensor].element,
                        Element::FieldShaft { .. } | Element::FieldAcShaft { .. }
                    ) || sensors[sensor].is_some()
                        || removed[private]
                    {
                        break;
                    }
                    let map = &maps[sensor];
                    let (far, sign) = if map[3] == node {
                        (map[5], -orientation)
                    } else if map[5] == node {
                        (map[3], orientation)
                    } else {
                        break;
                    };
                    // A sensor whose pins already coincide has undetermined current.
                    if far == node {
                        break;
                    }
                    removed[private] = true;
                    removed[map[4].unwrap()] = true;
                    chain.push((sensor, private, sign));
                    previous = sensor;
                    node = far;
                }
                for (sensor, private, sign) in chain {
                    sensors[sensor] = Some(Sensor {
                        node: private,
                        terminal: node,
                        winding_current: maps[i][current].unwrap(),
                        sign,
                    });
                }
            }
        }
        if !removed.iter().any(|r| *r) {
            return Ok(None);
        }
        let kept: Vec<_> = (0..size).filter(|&r| !removed[r]).collect();
        let mut rows = vec![None; size];
        for (i, &r) in kept.iter().enumerate() {
            rows[r] = Some(i);
        }
        for sensor in sensors.iter().flatten() {
            rows[sensor.node] = sensor.terminal.and_then(|r| rows[r]);
        }
        let ordinary: Vec<_> = (0..branches.len()).filter(|&i| !included[i]).collect();
        let mut mappings = vec![Vec::new(); branches.len()];
        for &i in &ordinary {
            mappings[i] = maps[i].iter().map(|r| r.and_then(|r| rows[r])).collect();
        }
        for (i, sensor) in sensors.iter().enumerate() {
            if let Some(sensor) = sensor {
                // The two sense-pin KCL contributions cancel after node merging.
                mappings[i][3] = None;
                mappings[i][5] = None;
                mappings[i][4] = rows[sensor.winding_current];
            }
        }
        let mut path_for = vec![None; branches.len()];
        for (j, path) in paths.iter().enumerate() {
            let first = path.segments[0].branch;
            path_for[first] = Some(j);
            mappings[first] = vec![
                path.terminals[0].and_then(|r| rows[r]),
                path.terminals[1].and_then(|r| rows[r]),
                rows[path.current],
            ];
            if let Some(k) = path.feedback {
                mappings[first].push(rows[maps[path.segments[k].branch][3].unwrap()]);
            }
        }
        let mut pattern = Matrix::new(kept.len())?;
        for map in &mappings {
            for &r in map.iter().flatten() {
                for &c in map.iter().flatten() {
                    pattern.add(r, c, 1.0)?;
                }
            }
        }
        let active_indices = mappings
            .iter()
            .map(|map| {
                map.iter()
                    .enumerate()
                    .filter_map(|(i, r)| r.map(|r| (i, r)))
                    .collect()
            })
            .collect();
        let coordinates = super::Coordinates {
            symbolic: pattern.analyze()?,
            size: kept.len(),
            node_count: kept.partition_point(|&r| r < nodes),
            mappings,
            active_indices,
            current_inputs: sensors.iter().map(Option::is_some).collect(),
            unknown_units: kept.iter().map(|&r| units[r]).collect(),
        };
        Ok(Some(Self {
            paths,
            ordinary,
            kept,
            rows,
            coordinates,
            path_for,
            returns,
            thermal,
            sensors,
            shunts,
        }))
    }

    /// Restore the private voltages from each segment's own drop. Check the
    /// original local equation, including the endpoint imposed by the solve.
    pub(crate) async fn recover(
        &self,
        compact: &[f64],
        maps: &[Vec<Option<usize>>],
        mut drop: impl FnMut(&Segment, f64) -> f64,
    ) -> Option<Vec<f64>> {
        let mut full = vec![0.0; self.rows.len()];
        for (&r, &x) in self.kept.iter().zip(compact) {
            full[r] = x;
        }
        self.restore_sensors(maps, &mut full);
        for (i, role) in self.returns.iter().enumerate() {
            if let Some((output, input)) = role {
                full[maps[i][*output]?] = input.map_or(0.0, |j| -full[maps[i][j].unwrap()]);
            }
        }
        for path in &self.paths {
            let amps = compact[self.rows[path.current]?];
            let mut voltage = path.terminals[0].map_or(0.0, |r| full[r]);
            for (k, seg) in path.segments.iter().enumerate() {
                checkpoint().await;
                let current = seg.sign * amps;
                full[maps[seg.branch][2]?] = current;
                let delta = seg.sign * drop(seg, current);
                let end = if k + 1 == path.segments.len() {
                    seg.end.map_or(0.0, |r| full[r])
                } else {
                    let end = voltage - delta;
                    full[seg.end?] = end;
                    end
                };
                let scale = voltage.abs().max(end.abs()).max(delta.abs());
                if !scale.is_finite()
                    || !delta.is_finite()
                    || !end.is_finite()
                    || (voltage - end - delta).abs() > 1e-10 * scale.max(f64::MIN_POSITIVE)
                {
                    return None;
                }
                voltage = end;
            }
        }
        Some(full)
    }

    pub(crate) fn restore_sensors(&self, maps: &[Vec<Option<usize>>], full: &mut [f64]) {
        for (i, sensor) in self.sensors.iter().enumerate() {
            if let Some(s) = sensor {
                full[s.node] = s.terminal.map_or(0.0, |r| full[r]);
                full[maps[i][4].unwrap()] = s.sign * full[s.winding_current];
            }
        }
    }

    /// Apply the chain rule for the eliminated return column to every
    /// constitutive and storage row before stamping the compact matrix.
    pub(crate) fn project(&self, i: usize, e: &mut crate::model::Evaluation) {
        if let Some((output, Some(input))) = self.returns[i] {
            let n = e.residual.len();
            for jac in [&mut e.residual_jacobian, &mut e.charge_jacobian] {
                for row in jac.chunks_exact_mut(n) {
                    row[input] -= row[output];
                }
            }
        }
        if let Some(sensor) = self.sensors[i] {
            // Local 4 supplies the winding-current column only. Its voltage
            // constraint is identically satisfied by the merged nodes.
            e.residual[4] = 0.0;
            for jac in [&mut e.residual_jacobian, &mut e.charge_jacobian] {
                jac[28..35].fill(0.0);
                for row in jac.chunks_exact_mut(7) {
                    row[4] *= sensor.sign;
                }
            }
        }
    }

    pub(crate) fn stamp_path(
        &self,
        matrix: &mut Matrix,
        path: &Path,
        impedance: f64,
    ) -> Result<(), SolveError> {
        let current = self.rows[path.current].unwrap();
        matrix.add_solver(current, current, -impedance)?;
        for (node, sign) in path.terminals.into_iter().zip([1.0, -1.0]) {
            if let Some(node) = node {
                let row = self.rows[node].unwrap();
                matrix.add_solver(row, current, sign)?;
                matrix.add_solver(current, row, sign)?;
            }
        }
        Ok(())
    }
}
