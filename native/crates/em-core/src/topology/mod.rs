//! Submit topology edits against a revision. Devices, ports and parallel branches retain stable
//! identities as matrix layouts change.

use crate::{
    model::{DeviceModel, element::rejected},
    solver::dc::DcBranch,
};
use em_protocol::contract::{Diagnostic, Id, PortId};
use std::collections::{HashMap, HashSet};

pub(crate) mod series;

pub(crate) struct Layout {
    pub series: Option<series::SeriesPorts>,
    stage_pattern: std::sync::OnceLock<crate::solver::sparse::Symbolic>,

    pub branch_indices: HashMap<em_protocol::contract::Id, usize>,
    pub rows: HashMap<PortId, Option<usize>>,
    pub components: HashMap<PortId, usize>,
    pub passive_secondary_components: HashMap<Id, usize>,
    pub references: Vec<PortId>,
    pub coordinates: Coordinates,
}

impl std::ops::Deref for Layout {
    type Target = Coordinates;
    fn deref(&self) -> &Coordinates { &self.coordinates }
}

pub(crate) struct Coordinates {
    pub symbolic: crate::solver::sparse::Symbolic,
    pub node_count: usize,
    pub mappings: Vec<Vec<Option<usize>>>,
    active_indices: Vec<Vec<(usize, usize)>>,
    current_inputs: Vec<bool>,
    pub size: usize,
    pub unknown_units: Vec<crate::model::UnknownUnit>,
}

impl Layout {
    pub fn compile(branches: &[DcBranch]) -> Result<Self, Diagnostic> {
        Self::compile_layout(branches, None, true)
    }

    pub(crate) fn compile_island(branches: &[DcBranch], independent: bool) -> Result<Self, Diagnostic> {
        // The small repeated RC, diode and winding islands reuse a representative
        // trajectory and its factors. A second port plan adds recovery work.
        Self::compile_layout(branches, None, !independent || branches.len() > 4)
    }

    pub(crate) fn compile_layout(branches: &[DcBranch], reference: Option<PortId>, reduce: bool) -> Result<Self, Diagnostic> {
        use crate::solver::progress::InitializationProfile;
        let started = InitializationProfile::timer();
        if branches.len() > em_protocol::wire::MAX_BRANCHES {
            return Err(rejected("Circuit branch resource limit exceeded"));
        }
        let mut ids = HashSet::new();
        let mut nodes = Vec::new();
        let mut index = HashMap::new();
        for b in branches {
            b.element.validate()?;
            if b.additional_ports.len() + 2 != b.element.port_count() {
                return Err(rejected("Constitutive port count mismatch"));
            }
            if !ids.insert(b.id) || b.positive == b.negative {
                return Err(rejected("Duplicate branch identity or collapsed terminals"));
            }
            for port in b.ports() {
                if let std::collections::hash_map::Entry::Vacant(entry) = index.entry(port) {
                    entry.insert(nodes.len());
                    nodes.push(port);
                }
            }
        }
        let mut adjacency = vec![Vec::new(); nodes.len()];
        for b in branches {
            let ports = b.ports().collect::<Vec<_>>();
            for (p, q) in b.element.electrical_edges() {
                let a = index[&ports[p]];
                let z = index[&ports[q]];
                adjacency[a].push(z);
                adjacency[z].push(a);
            }
        }
        let mut seen = vec![false; nodes.len()];
        let mut references = Vec::new();
        let mut rows = vec![None; nodes.len()];
        let mut node_count = 0;
        let mut components = vec![0; nodes.len()];
        for root in reference.map(|p| index[&p]).into_iter().chain(0..nodes.len()) {
            if seen[root] {
                continue;
            }
            references.push(nodes[root]);
            let component = references.len() - 1;
            components[root] = component;
            seen[root] = true;
            let mut stack = vec![root];
            while let Some(node) = stack.pop() {
                for &next in &adjacency[node] {
                    if !seen[next] {
                        seen[next] = true;
                        components[next] = component;
                        rows[next] = Some(node_count);
                        node_count += 1;
                        stack.push(next);
                    }
                }
            }
        }
        for b in branches {
            if matches!(b.element, crate::model::element::Element::Meter { .. })
                && components[index[&b.positive]] != components[index[&b.negative]]
            {
                return Err(rejected(
                    "Wattmeter voltage reference belongs to a different floating island",
                ));
            }
        }
        // Only passive, galvanically isolated secondary circuits qualify for the
        // gameplay common-mode tolerance. Inspect every terminal, including sense inputs.
        use crate::model::element::Element;
        let component_of = |port: PortId| components[index[&port]];
        let mut passive = vec![true; references.len()];
        for b in branches {
            match b.element {
                Element::Windings(_) | Element::Resistor { .. } | Element::Capacitor { .. }
                | Element::Inductor { .. } => {}
                _ => {
                    for port in b.ports() { passive[component_of(port)] = false; }
                }
            }
        }
        let passive_secondary_components = branches.iter().filter_map(|b| {
            if !matches!(b.element, Element::Windings(_)) { return None; }
            let secondary = component_of(b.additional_ports[0]);
            (secondary != component_of(b.positive) && passive[secondary]).then_some((b.id, secondary))
        }).collect();
        let mut size = node_count;
        let mappings: Vec<Vec<Option<usize>>> = branches
            .iter()
            .map(|b| {
                let mut mapping = vec![
                    rows[index[&b.positive]],
                    rows[index[&b.negative]],
                    Some(size),
                ];
                size += 1;
                for port in &b.additional_ports {
                    mapping.push(rows[index[port]]);
                    mapping.push(Some(size));
                    size += 1;
                }
                while mapping.len() < b.element.unknown_count() {
                    mapping.push(Some(size));
                    size += 1;
                }
                mapping
            })
            .collect();
        let mut unknown_units = vec![crate::model::UnknownUnit::Volt; size];
        for (branch, mapping) in branches.iter().zip(&mappings) {
            let units = branch.element.unknown_units();
            if units.len() != mapping.len() {
                return Err(rejected("Constitutive unknown metadata mismatch"));
            }
            for (row, unit) in mapping.iter().zip(units) {
                if let Some(row) = row { unknown_units[*row] = unit; }
            }
        }
        // The local sparsity pattern covers initialization constraints and derivatives that vanish
        // at the current parameters.
        let active_indices: Vec<Vec<(usize, usize)>> = mappings.iter().map(|mapping|
            mapping.iter().enumerate().filter_map(|(local, row)| row.map(|row| (local, row))).collect()
        ).collect();
        let mut pattern = crate::solver::sparse::Matrix::new(size)?;
        for active in &active_indices {
            for &(_, row) in active {
                for &(_, col) in active { pattern.add(row, col, 1.0)?; }
            }
        }
        let ordering = InitializationProfile::timer();
        let mut symbolic = pattern.analyze()?;
        symbolic.plan_series(node_count, &mappings, branches);
        if let (Some(started), Some(ordering)) = (started, ordering) {
            InitializationProfile::report_layout(started, ordering, size);
        }
        Ok(Self {
            series: if reduce { series::SeriesPorts::compile(branches, &mappings, node_count, &unknown_units)? } else { None },
            stage_pattern: Default::default(),
            passive_secondary_components,
            branch_indices: branches.iter().enumerate().map(|(i, b)| (b.id, i)).collect(),
            components: nodes.iter().copied().zip(components).collect(),
            rows: nodes.into_iter().zip(rows).collect(),
            references,
            coordinates: Coordinates { symbolic, unknown_units, node_count, mappings, active_indices, size, current_inputs: Vec::new() },
        })
    }

    /// Include both collocation stages and derivatives that are zero at the first Newton iterate.
    pub(crate) fn coupled_pattern(&self) -> &crate::solver::sparse::Symbolic {
        self.stage_pattern.get_or_init(|| self.symbolic.coupled_stages())
    }

}

impl Coordinates {
    // An eliminated field sensor uses local slot 4 to read another port's
    // current. Its former voltage equation no longer has a row.
    pub(crate) fn has_equation(&self, branch: usize, local: usize) -> bool {
        local != 4 || !self.current_inputs.get(branch).copied().unwrap_or(false)
    }

    pub(crate) fn stamp(&self, branch: usize, values: &[f64], matrix: &mut crate::solver::sparse::Matrix)
        -> Result<(), crate::solver::SolveError>
    {
        let active = &self.active_indices[branch];
        let dimension = self.mappings[branch].len();
        for &(local_row, row) in active {
            let offset = local_row * dimension;
            for &(local_col, col) in active {
                let value = values[offset + local_col];
                if value != 0.0 { matrix.add_solver(row, col, value)?; }
            }
        }
        Ok(())
    }

    pub fn local(&self, index: usize, x: &[f64]) -> Vec<f64> {
        self.local_values(index, x).collect()
    }

    pub(crate) fn local_values<'a>(&'a self, index: usize, x: &'a [f64]) -> impl Iterator<Item = f64> + 'a {
        self.mappings[index]
            .iter()
            .map(move |row| row.map_or(0.0, |r| x[r]))
    }
}
