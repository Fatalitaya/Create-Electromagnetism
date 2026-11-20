use super::{DcBranch, Diagnostic, Element, InitialConditions, OpenWindings, rejected};
use crate::{solver::newton::InitialVoltageRow, state::InitialStorage, topology::Layout};
use em_protocol::contract::{Id, PortId};
use std::collections::HashMap;

#[derive(Default)]
struct Region {
    row: Option<usize>,
    net_current: f64,
    absolute_current: f64,
    inverse_inductance: f64,
    coefficients: Vec<(usize, f64)>,
}

#[derive(Default)]
pub(super) struct CutsetInitialization {
    pub voltage_rows: Vec<InitialVoltageRow>,
    pub transferred_fluxes: Vec<(Id, f64)>,
    pub opening_loss_joules: f64,
}

/// Inductors with prescribed flux can form a cutset. Regional KCL constrains the prescribed
/// currents; differentiating it supplies a boundary voltage constraint.
pub(super) fn prepare(
    branches: &[DcBranch],
    layout: &Layout,
    initial: &InitialConditions,
    open: &OpenWindings,
    topology_changed: bool,
) -> Result<CutsetInitialization, Diagnostic> {
    let mut result = CutsetInitialization::default();
    let held =
        |b: &DcBranch| matches!(b.element, Element::Inductor { .. }) && !open.contains_key(&b.id);
    if !branches.iter().any(held) {
        return Ok(result);
    }
    let mut neighbors = HashMap::<PortId, Vec<PortId>>::new();
    for branch in branches.iter().filter(|b| !held(b)) {
        let ports: Vec<_> = branch.ports().collect();
        for (a, b) in branch.element.electrical_edges() {
            neighbors.entry(ports[a]).or_default().push(ports[b]);
            neighbors.entry(ports[b]).or_default().push(ports[a]);
        }
    }

    let mut membership = HashMap::new();
    let mut regions = Vec::new();
    // Traverse from existing reference nodes so each region retains its voltage reference.
    for port in layout
        .references
        .iter()
        .copied()
        .chain(branches.iter().flat_map(DcBranch::ports))
    {
        if membership.contains_key(&port) {
            continue;
        }
        let id = regions.len();
        let mut row = layout.rows[&port];
        membership.insert(port, id);
        let mut stack = vec![port];
        while let Some(node) = stack.pop() {
            row = row.min(layout.rows[&node]);
            for &other in neighbors.get(&node).into_iter().flatten() {
                if let std::collections::hash_map::Entry::Vacant(entry) = membership.entry(other) {
                    entry.insert(id);
                    stack.push(other);
                }
            }
        }
        regions.push(Region {
            row,
            ..Region::default()
        });
    }

    let mut crossings = Vec::new();
    for branch in branches.iter().filter(|b| held(b)) {
        let positive = membership[&branch.positive];
        let negative = membership[&branch.negative];
        if positive == negative {
            continue;
        }
        let Element::Inductor { henries } = branch.element else {
            unreachable!()
        };
        let Some(InitialStorage::FluxWebers(flux)) = initial.get(&branch.id) else {
            return Err(rejected("Missing inductor flux"));
        };
        let current = flux / henries;
        crossings.push((branch.id, positive, negative, henries, *flux));
        for (region, sign) in [(positive, 1.0), (negative, -1.0)] {
            let r = &mut regions[region];
            r.net_current += sign * current;
            r.absolute_current += current.abs();
            r.inverse_inductance += 1.0 / henries;
            for (port, weight) in [(branch.positive, sign), (branch.negative, -sign)] {
                if let Some(column) = layout.rows[&port] {
                    r.coefficients.push((column, weight / henries));
                }
            }
        }
    }

    if regions
        .iter()
        .any(|r| !r.net_current.is_finite() || !r.inverse_inductance.is_finite())
    {
        return Err(rejected("Non-finite inductor cutset"));
    }
    if regions
        .iter()
        .any(|r| r.net_current.abs() > 1e-12 + 1e-9 * r.absolute_current)
    {
        if !topology_changed {
            return Err(rejected(
                "Initial inductor currents violate Kirchhoff's current law across a cutset",
            ));
        }
        // Opening a current-carrying neutral imposes a new KCL condition. Nodal voltage impulses
        // adjust cut fluxes by potential differences while conserving loop flux.
        let mut rhs = Vec::new();
        let rows: Vec<_> = regions
            .iter()
            .map(|r| {
                r.row.filter(|_| r.inverse_inductance > 0.0).map(|_| {
                    let row = rhs.len();
                    rhs.push(r.net_current);
                    row
                })
            })
            .collect();
        let mut matrix = crate::solver::sparse::Matrix::new(rhs.len())?;
        for &(_, positive, negative, henries, _) in &crossings {
            for (a, b) in [(positive, negative), (negative, positive)] {
                if let Some(row) = rows[a] {
                    matrix.add(row, row, 1.0 / henries)?;
                    if let Some(col) = rows[b] {
                        matrix.add(row, col, -1.0 / henries)?;
                    }
                }
            }
        }
        let impulse = matrix.solve(&rhs)?;
        for region in &mut regions {
            region.net_current = 0.0;
            region.absolute_current = 0.0;
        }
        for (id, positive, negative, henries, flux) in crossings {
            let change = rows[negative].map_or(0.0, |i| impulse[i])
                - rows[positive].map_or(0.0, |i| impulse[i]);
            let retained = flux + change;
            let current = retained / henries;
            for (region, sign) in [(positive, 1.0), (negative, -1.0)] {
                regions[region].net_current += sign * current;
                regions[region].absolute_current += current.abs();
            }
            result.transferred_fluxes.push((id, retained));
            result.opening_loss_joules += 0.5 * change * (change / henries);
        }
        if regions.iter().any(|r| {
            !r.net_current.is_finite() || r.net_current.abs() > 1e-12 + 1e-9 * r.absolute_current
        }) {
            return Err(rejected(
                "Inconsistent inductor currents after topology transfer",
            ));
        }
    }
    for r in regions {
        if let Some(row) = r.row
            && r.inverse_inductance > 0.0
        {
            // Convert to volts for comparison with the Newton residual tolerance.
            let coefficients = r
                .coefficients
                .into_iter()
                .map(|(column, weight)| (column, weight / r.inverse_inductance))
                .collect();
            result
                .voltage_rows
                .push(InitialVoltageRow { row, coefficients });
        }
    }
    Ok(result)
}
