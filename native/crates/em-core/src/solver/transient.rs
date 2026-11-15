//! Backward Euler with immutable candidates, explicit storage and nonlinear Newton trials.
use crate::{
    model::{
        DeviceModel, ResidualUnit,
        element::{Element, rejected},
    },
    observation::{EnergyLedger, Measurements},
    solver::dc::{self, DcBranch},
    state::{AcceptedState, InitialConditions, InitialStorage},
};
use em_protocol::contract::Diagnostic;

/// Frozen topology owns its accepted state; clients cannot substitute a foreign history.
#[derive(Clone)]
pub struct TransientCircuit {
    pub(crate) branches: Vec<DcBranch>,
    pub(crate) layout: std::sync::Arc<crate::topology::Layout>,
    pub(crate) accepted: AcceptedState,
}

/// Dimensioned tolerances for step doubling. Output sampling is unrelated to these limits.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Accuracy {
    pub relative: f64,
    pub voltage: f64,
    pub current: f64,
    pub charge: f64,
    pub flux: f64,
    pub energy: f64,
    pub minimum_step: f64,
}

impl Accuracy {
    pub fn validate(&self) -> Result<(), Diagnostic> {
        if [
            self.relative,
            self.voltage,
            self.current,
            self.charge,
            self.flux,
            self.energy,
            self.minimum_step,
        ]
        .iter()
        .any(|v| !v.is_finite() || *v <= 0.0)
        {
            return Err(rejected(
                "Adaptive tolerances and minimum step must be finite and positive",
            ));
        }
        Ok(())
    }
}

impl TransientCircuit {
    /// None requests a DC operating point. Some requires every C/L initial value.
    /// Algebraically inconsistent/underdetermined initial constraints are rejected.
    pub fn new(
        branches: Vec<DcBranch>,
        initial: Option<&InitialConditions>,
    ) -> Result<Self, Diagnostic> {
        Self::initialize_at(branches, initial, 0.0, &std::collections::HashMap::new(), None)
    }

    fn initialize_at(
        branches: Vec<DcBranch>,
        initial: Option<&InitialConditions>,
        time: f64,
        previous_phases: &std::collections::HashMap<em_protocol::contract::Id, f64>,
        reusable_layout: Option<std::sync::Arc<crate::topology::Layout>>,
    ) -> Result<Self, Diagnostic> {
        let layout = match reusable_layout { Some(layout) => layout, None => std::sync::Arc::new(crate::topology::Layout::compile(&branches)?), };
        let mut source_phases = std::collections::HashMap::new();
        let mut clocks = std::collections::HashMap::new();
        for b in &branches {
            if let Some((frequency, phase)) = b.element.source_clock() {
                let clock = b.clock_id();
                let description = (
                    b.positive.device,
                    frequency,
                    matches!(
                        b.element,
                        Element::AcShaft { .. } | Element::FieldAcShaft { .. }
                    ),
                );
                if let Some(old) = clocks.insert(clock, description)
                    && (old != description || !description.2)
                {
                    return Err(rejected(
                        "Conflicting source clock identity or shaft speeds",
                    ));
                }
                source_phases.insert(
                    clock,
                    previous_phases
                        .get(&clock)
                        .copied()
                        .unwrap_or_else(|| phase.rem_euclid(std::f64::consts::TAU)),
                );
            }
        }
        let point = if let Some(initial) = initial {
            let dynamic_count = branches.iter().filter(|b| b.element.is_dynamic()).count();
            if initial.len() != dynamic_count {
                return Err(rejected(
                    "Initial storage must identify exactly every dynamic branch",
                ));
            }
            // An inductor on an electrical cut edge cannot carry current: KCL
            // already imposes zero current. Repeating that constraint at startup
            // leaves its voltage undetermined. Its consistent derivative is zero,
            // hence zero inductor voltage. Nonzero initial flux is incompatible
            // with an open winding and must still be rejected, not erased.
            let mut adjacency = std::collections::HashMap::new();
            for branch in &branches {
                let ports: Vec<_> = branch.ports().collect();
                for (a, b) in branch.element.electrical_edges() {
                    adjacency.entry(ports[a]).or_insert_with(Vec::new).push((ports[b], branch.id));
                    adjacency.entry(ports[b]).or_insert_with(Vec::new).push((ports[a], branch.id));
                }
            }
            let mut open_inductors = std::collections::HashSet::new();
            for branch in &branches {
                if !matches!(branch.element, Element::Inductor { .. }) { continue; }
                let mut seen = std::collections::HashSet::from([branch.positive]);
                let mut pending = vec![branch.positive];
                while let Some(port) = pending.pop() {
                    if let Some(edges) = adjacency.get(&port) {
                        for &(next, edge) in edges {
                            if edge != branch.id && seen.insert(next) { pending.push(next); }
                        }
                    }
                    if seen.contains(&branch.negative) { break; }
                }
                if !seen.contains(&branch.negative) { open_inductors.insert(branch.id); }
            }
            let evaluate = |branch: &DcBranch, values: &[f64]| {
                let mut e = evaluate_at_phase(branch, values, time, &source_phases)?;
                match branch.element {
                    Element::Regulator(_) => {
                        let Some(InitialStorage::DetectorVoltageSquared(value)) =
                            initial.get(&branch.id)
                        else {
                            return Err(rejected("Missing regulator detector state"));
                        };
                        if !value.is_finite() || *value < 0.0 {
                            return Err(rejected("Invalid regulator detector state"));
                        }
                        e.residual[5] = values[5] - value;
                        e.residual_jacobian[30..36].fill(0.0);
                        e.residual_jacobian[35] = 1.0;
                    }
                    Element::ThermalResistor(_)
                    | Element::TappedWire(_)
                    | Element::Arc(_) => {
                        let (model, thermal_row) = branch.element.thermal_node().unwrap();
                        let theta = match initial.get(&branch.id) {
                            Some(InitialStorage::TemperatureKelvin(t)) => *t - model.ambient_kelvin,
                            Some(InitialStorage::TemperatureRiseKelvin(t)) => *t,
                            _ => return Err(rejected("Missing thermal node temperature")),
                        };
                        if !theta.is_finite() || theta < 0.0 {
                            return Err(rejected("Initial temperature below fixed ambient"));
                        }
                        e.residual[thermal_row] = values[thermal_row] - theta;
                        e.residual_units[thermal_row] = ResidualUnit::Kelvin;
                        let n = values.len();
                        e.residual_jacobian[thermal_row * n..(thermal_row + 1) * n].fill(0.0);
                        e.residual_jacobian[thermal_row * n + thermal_row] = 1.0;
                    }
                    Element::Windings(model) => {
                        let Some(InitialStorage::WindingFluxes { primary, secondary }) =
                            initial.get(&branch.id)
                        else {
                            return Err(rejected("Missing coupled winding fluxes"));
                        };
                        let (ip, is) = model.currents(*primary, *secondary);
                        e.residual[2] = values[2] - ip;
                        e.residual[4] = values[4] - is;
                        e.residual_units[2] = ResidualUnit::Ampere;
                        e.residual_units[4] = ResidualUnit::Ampere;
                        e.residual_jacobian[14..21].fill(0.0);
                        e.residual_jacobian[28..35].fill(0.0);
                        e.residual_jacobian[16] = 1.0;
                        e.residual_jacobian[32] = 1.0;
                    }
                    Element::Bipolar(_) | Element::Mosfet(_) => {
                        let (return_cap, output_cap) =
                            branch.element.control_capacitances().unwrap();
                        let Some(InitialStorage::PortCharges {
                            control_return,
                            control_output,
                        }) = initial.get(&branch.id)
                        else {
                            return Err(rejected("Missing control port charges"));
                        };
                        e.residual[2] = values[3] - values[0] - control_output / output_cap;
                        e.residual[4] = values[3] - values[1] - control_return / return_cap;
                        e.residual_units[2] = ResidualUnit::Volt;
                        e.residual_units[4] = ResidualUnit::Volt;
                        e.residual_jacobian[10..15].fill(0.0);
                        e.residual_jacobian[20..25].fill(0.0);
                        e.residual_jacobian[10] = -1.0;
                        e.residual_jacobian[13] = 1.0;
                        e.residual_jacobian[21] = -1.0;
                        e.residual_jacobian[23] = 1.0;
                    }
                    Element::Capacitor { farads } => {
                        let Some(InitialStorage::ChargeCoulombs(q)) = initial.get(&branch.id)
                        else {
                            return Err(rejected("Missing capacitor charge"));
                        };
                        e.residual[2] = values[0] - values[1] - q / farads;
                        e.residual_units[2] = ResidualUnit::Volt;
                        e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
                    }
                    Element::Inductor { henries } => {
                        let Some(InitialStorage::FluxWebers(flux)) = initial.get(&branch.id) else {
                            return Err(rejected("Missing inductor flux"));
                        };
                        if open_inductors.contains(&branch.id) {
                            if *flux != 0.0 {
                                return Err(rejected("Nonzero initial flux in an open inductor"));
                            }
                            e.residual[2] = values[0] - values[1];
                            e.residual_units[2] = ResidualUnit::Volt;
                            e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
                        } else {
                            e.residual[2] = values[2] - flux / henries;
                            e.residual_units[2] = ResidualUnit::Ampere;
                            e.residual_jacobian[6..9].copy_from_slice(&[0.0, 0.0, 1.0]);
                        }
                    }
                    Element::Diode {
                        junction_farads, ..
                    } if junction_farads > 0.0 => {
                        let Some(InitialStorage::ChargeCoulombs(q)) = initial.get(&branch.id)
                        else {
                            return Err(rejected("Missing diode junction charge"));
                        };
                        e.residual[2] = values[0] - values[1] - q / junction_farads;
                        e.residual_units[2] = ResidualUnit::Volt;
                        e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
                    }
                    _ => {}
                }
                Ok(e)
            };
            if branches.iter().any(|b| b.element.is_nonlinear()) {
                super::newton::solve_compiled(&branches, time, None, &layout, evaluate)?
            } else {
                dc::solve_compiled(&branches, time, &layout, |b| {
                    evaluate(b, &vec![0.0; b.element.unknown_count()])
                })?
            }
        } else {
            dc::solve(&branches)?
        };
        let stored_joules = point.branches.iter().map(|b| b.stored_joules).sum();
        let energy = EnergyLedger {
            initial_stored_joules: stored_joules,
            stored_joules,
            ..EnergyLedger::default()
        };
        if !energy.is_finite() {
            return Err(rejected("Non-finite initial storage"));
        }
        let measurements = Measurements::new(time, branches.len());
        Ok(Self {
            branches,
            layout,
            accepted: AcceptedState {
                time_seconds: time,
                operating_point: point,
                energy,
                measurements,
                sampler: Default::default(),
                source_phases,
            },
        })
    }

    pub fn accepted(&self) -> &AcceptedState {
        &self.accepted
    }

    /// Builds a private parameter candidate at the accepted boundary, preserving q/lambda.
    /// Storage edits conserve q/lambda and account the resulting external boundary work.
    pub(crate) fn with_parameters(
        &self,
        changes: &[(em_protocol::contract::Id, Element)],
    ) -> Result<Self, Diagnostic> {
        let mut branches = self.branches.clone();
        let mut seen = std::collections::HashSet::new();
        for &(id, ref element) in changes {
            element.validate()?;
            if !seen.insert(id) {
                return Err(rejected("Duplicate parameter target"));
            }
            let branch = branches
                .iter_mut()
                .find(|b| b.id == id)
                .ok_or_else(|| rejected("Unknown parameter target"))?;
            if !branch.element.same_kind(&element) {
                return Err(rejected(
                    "Model replacement requires a topology transaction",
                ));
            }
            if matches!((&branch.element, element),
                (Element::SineVoltageSource { phase: old, .. }, Element::SineVoltageSource { phase: new, .. }) if old != new)
            {
                return Err(rejected(
                    "Changing initial phase requires an explicit phase reset command",
                ));
            }
            branch.element = element.clone();
        }
        self.rebuild_boundary(branches, true, false)
    }

    /// Migrate dynamic state by branch identity, never by matrix position.
    /// Removing nonzero storage requires an explicit export-boundary disposition.
    pub(crate) fn with_topology(
        &self,
        branches: Vec<DcBranch>,
        export_removed_energy: bool,
    ) -> Result<Self, Diagnostic> {
        self.rebuild_boundary(branches, false, export_removed_energy)
    }

    pub(crate) fn rebuild_boundary(
        &self,
        branches: Vec<DcBranch>,
        allow_parameter_work: bool,
        export_removed_energy: bool,
    ) -> Result<Self, Diagnostic> {
        self.rebuild_with_charge(branches, allow_parameter_work, export_removed_energy, None)
    }

    pub(crate) fn exchange_capacitor_energy(
        &self,
        id: em_protocol::contract::Id,
        delta: f64,
        maximum: f64,
    ) -> Result<Self, Diagnostic> {
        let index = self
            .branches
            .iter()
            .position(|b| b.id == id)
            .ok_or_else(|| rejected("Unknown external energy buffer"))?;
        let Element::Capacitor { farads } = self.branches[index].element else {
            return Err(rejected(
                "External energy exchange requires a capacitor buffer",
            ));
        };
        let point = &self.accepted.operating_point.branches[index];
        let next = point.stored_joules + delta;
        if !delta.is_finite()
            || !maximum.is_finite()
            || maximum <= 0.0
            || next < 0.0
            || next > maximum
            || point.voltage < 0.0
        {
            return Err(rejected("External energy buffer bounds exceeded"));
        }
        let charge = (2.0 * farads * next).sqrt();
        let mut candidate =
            self.rebuild_with_charge(self.branches.clone(), true, false, Some((id, charge)))?;
        candidate.accepted.energy.parameter_work_joules =
            self.accepted.energy.parameter_work_joules;
        if delta >= 0.0 {
            candidate.accepted.energy.source_supplied_joules += delta;
        } else {
            candidate.accepted.energy.source_absorbed_joules -= delta;
        }
        if !candidate.accepted.energy.is_finite() {
            return Err(rejected("Non-finite external energy ledger"));
        }
        Ok(candidate)
    }

    fn rebuild_with_charge(
        &self,
        mut branches: Vec<DcBranch>,
        allow_parameter_work: bool,
        export_removed_energy: bool,
        override_charge: Option<(em_protocol::contract::Id, f64)>,
    ) -> Result<Self, Diagnostic> {
        for branch in &mut branches {
            if let Element::Relay { ref mut active, .. } = branch.element
                && let Some(old) = self.layout.branch_indices.get(&branch.id).map(|&i| &self.branches[i])
                && let Element::Relay {
                    active: previous, ..
                } = old.element
            {
                *active = previous;
            }
        }
        for branch in &branches { branch.element.validate()?; }
        let layout = if branches.len() == self.branches.len() && branches.iter().zip(&self.branches).all(|(a,b)| a.id == b.id && a.ports().eq(b.ports()) && a.element.unknown_count() == b.element.unknown_count() && a.element.electrical_edges() == b.element.electrical_edges()) {
            self.layout.clone()
        } else { std::sync::Arc::new(crate::topology::Layout::compile(&branches)?) };
        let mut storage = InitialConditions::new();
        let mut expected_storage = 0.0;
        let mut changed_storage_parameter = false;
        for branch in &branches {
            let previous = self.layout.branch_indices.get(&branch.id).copied();
            if matches!(branch.element, Element::Regulator(_)) {
                let value = if let Some(i) = previous {
                    if !matches!(self.branches[i].element, Element::Regulator(_)) {
                        return Err(rejected("Retained regulator changed model"));
                    }
                    self.accepted.operating_point.branches[i].local_values[5]
                } else {
                    0.0
                };
                storage.insert(branch.id, InitialStorage::DetectorVoltageSquared(value));
                continue;
            }
            if let Some((model, _)) = branch.element.thermal_node() {
                let theta = if let Some(i) = previous {
                    let Some((old, old_thermal_row)) = self.branches[i].element.thermal_node()
                    else {
                        return Err(rejected("Retained thermal branch changed model"));
                    };
                    if old.ambient_kelvin != model.ambient_kelvin {
                        return Err(rejected(
                            "Fixed ambient bath cannot be changed on a retained thermal node",
                        ));
                    }
                    let changed = old.heat_capacity_joules_per_kelvin
                        != model.heat_capacity_joules_per_kelvin;
                    if changed && !allow_parameter_work {
                        return Err(rejected("Topology cannot change thermal capacity"));
                    }
                    changed_storage_parameter |= changed;
                    self.accepted.operating_point.branches[i].local_values[old_thermal_row]
                } else {
                    0.0
                };
                storage.insert(branch.id, InitialStorage::TemperatureRiseKelvin(theta));
                expected_storage += model.heat_capacity_joules_per_kelvin * theta;
                continue;
            }
            if let Element::Windings(model) = branch.element {
                let (primary, secondary) = if let Some(i) = previous {
                    let Element::Windings(old) = self.branches[i].element else {
                        return Err(rejected("Retained winding changed model"));
                    };
                    let changed = old.primary_henries != model.primary_henries
                        || old.secondary_henries != model.secondary_henries
                        || old.mutual_henries != model.mutual_henries;
                    if changed && !allow_parameter_work {
                        return Err(rejected(
                            "Topology cannot change winding storage parameters",
                        ));
                    }
                    changed_storage_parameter |= changed;
                    let x = &self.accepted.operating_point.branches[i].local_values;
                    old.fluxes(x[2], x[4])
                } else {
                    (0.0, 0.0)
                };
                storage.insert(
                    branch.id,
                    InitialStorage::WindingFluxes { primary, secondary },
                );
                let (ip, is) = model.currents(primary, secondary);
                expected_storage += model.energy(ip, is);
                continue;
            }
            if let Some((return_cap, output_cap)) = branch.element.control_capacitances() {
                let (qbe, qbc) = if let Some(i) = previous {
                    let old = &self.branches[i];
                    if !old.element.same_kind(&branch.element) {
                        return Err(rejected("Retained branch changed model or polarity"));
                    }
                    let (old_return, old_output) = old.element.control_capacitances().unwrap();
                    let changed = old_return != return_cap || old_output != output_cap;
                    if changed && !allow_parameter_work {
                        return Err(rejected("Topology cannot change port storage parameters"));
                    }
                    changed_storage_parameter |= changed;
                    let x = &self.accepted.operating_point.branches[i].local_values;
                    (old_return * (x[3] - x[1]), old_output * (x[3] - x[0]))
                } else {
                    (0.0, 0.0)
                };
                storage.insert(
                    branch.id,
                    InitialStorage::PortCharges {
                        control_return: qbe,
                        control_output: qbc,
                    },
                );
                expected_storage += 0.5 * qbe * (qbe / return_cap) + 0.5 * qbc * (qbc / output_cap);
                continue;
            }
            let (mut charge, flux) = if let Some(i) = previous {
                let old = &self.branches[i];
                changed_storage_parameter |= old.element.capacitance()
                    != branch.element.capacitance()
                    || matches!((&old.element, &branch.element),
                        (Element::Inductor { henries: a }, Element::Inductor { henries: b }) if a != b);
                if !old.element.same_kind(&branch.element)
                    || (!allow_parameter_work
                        && (old.element.capacitance() != branch.element.capacitance()
                            || matches!((&old.element, &branch.element),
                        (Element::Inductor { henries: a }, Element::Inductor { henries: b }) if a != b)))
                {
                    return Err(rejected(
                        "Retained branch changed model or storage parameters",
                    ));
                }
                let point = &self.accepted.operating_point.branches[i];
                (
                    old.element.capacitance().unwrap_or(0.0) * point.voltage,
                    if let Element::Inductor { henries } = old.element {
                        henries * point.current
                    } else {
                        0.0
                    },
                )
            } else {
                (0.0, 0.0)
            };
            if let Some((id, value)) = override_charge
                && branch.id == id
            {
                charge = value;
                changed_storage_parameter = true;
            }
            if let Some(c) = branch.element.capacitance() {
                storage.insert(branch.id, InitialStorage::ChargeCoulombs(charge));
                expected_storage += 0.5 * charge * (charge / c);
            } else if let Element::Inductor { henries } = branch.element {
                storage.insert(branch.id, InitialStorage::FluxWebers(flux));
                expected_storage += 0.5 * flux * (flux / henries);
            } else if charge != 0.0 {
                return Err(rejected(
                    "Cannot reduce a charged junction capacitance to zero",
                ));
            }
        }
        let mut removed = 0.0;
        for old in &self.accepted.operating_point.branches {
            if !layout.branch_indices.contains_key(&old.id) && old.stored_joules != 0.0 {
                if !export_removed_energy {
                    return Err(rejected(
                        "Removing stored energy requires an explicit destination",
                    ));
                }
                removed += old.stored_joules;
            }
        }
        let mut candidate = Self::initialize_at(
            branches,
            Some(&storage),
            self.accepted.time_seconds,
            &self.accepted.source_phases,
            Some(layout),
        )?;
        let previous = self.accepted.energy.stored_joules;
        let next = candidate.accepted.energy.stored_joules;
        if !expected_storage.is_finite()
            || !removed.is_finite()
            || (expected_storage - next).abs()
                > 1e-12 + 1e-9 * expected_storage.abs().max(next.abs())
        {
            return Err(rejected(
                "Circuit edit failed charge/flux storage consistency",
            ));
        }
        candidate.accepted.energy = self.accepted.energy;
        candidate.accepted.energy.stored_joules = next;
        candidate.accepted.energy.removed_joules += removed;
        if allow_parameter_work && changed_storage_parameter {
            candidate.accepted.energy.parameter_work_joules += next - previous + removed;
        } else if (previous - removed - next).abs() > 1e-12 + 1e-9 * previous.abs().max(next.abs())
        {
            return Err(rejected(
                "Topology edit changed retained storage without external work",
            ));
        }
        if !candidate.accepted.energy.is_finite() {
            return Err(rejected("Non-finite circuit edit energy ledger"));
        }
        for (i, branch) in candidate.branches.iter().enumerate() {
            if let Some(old) = self.layout.branch_indices.get(&branch.id).copied() {
                candidate.accepted.measurements.branches[i] =
                    self.accepted.measurements.branches[old].clone();
                // A boundary edit can change waveform/phase even on an unchanged branch.
                candidate.accepted.measurements.branches[i].periodicity = Default::default();
            }
        }
        candidate
            .accepted
            .measurements
            .reset_window(self.accepted.time_seconds);
        candidate.validate_probes(
            &self
                .accepted
                .sampler
                .channels
                .iter()
                .map(|s| s.channel.clone())
                .collect::<Vec<_>>(),
        )?;
        candidate.accepted.sampler = self.accepted.sampler.clone();
        candidate.accepted.sampler.reset_output();
        Ok(candidate)
    }

    pub(crate) fn validate_probes(
        &self,
        channels: &[em_protocol::contract::WaveformChannel],
    ) -> Result<(), Diagnostic> {
        let layout = &self.layout;
        for channel in channels {
            let a = layout
                .components
                .get(&channel.positive)
                .ok_or_else(|| rejected("Unknown positive waveform terminal"))?;
            let b = layout
                .components
                .get(&channel.negative)
                .ok_or_else(|| rejected("Unknown negative waveform terminal"))?;
            if a != b {
                return Err(rejected(
                    "Waveform voltage across floating islands is undefined",
                ));
            }
        }
        Ok(())
    }

    /// Prevent full/half-step sampling from aliasing a fast source into constant DC.
    pub(crate) fn source_step_limit(&self) -> f64 {
        let impulse = self
            .branches
            .iter()
            .filter_map(|branch| {
                if let Element::RadioTransmitter { ref audio, .. } = branch.element {
                    let now = self.accepted.time_seconds;
                    if now < audio.start {
                        return Some(audio.start - now);
                    }
                    if now < audio.start + 800.0 * audio.step {
                        return Some(audio.step / 4.0);
                    }
                }
                if let Element::Audio(ref model) = branch.element {
                    let now = self.accepted.time_seconds;
                    if now < model.start {
                        return Some(model.start - now);
                    }
                    if now < model.start + 800.0 * model.step {
                        return Some(model.step / 4.0);
                    }
                    return None;
                }
                if let Element::Impulse {
                    start,
                    rise,
                    fall,
                    duration,
                    ..
                } = branch.element
                {
                    let now = self.accepted.time_seconds;
                    if now < start {
                        Some(start - now)
                    } else if now < start + duration {
                        Some(
                            (if now < start + 20.0 * fall {
                                rise / 8.0
                            } else {
                                duration / 256.0
                            })
                            .min(start + duration - now),
                        )
                    } else {
                        None
                    }
                } else {
                    None
                }
            })
            .fold(f64::INFINITY, f64::min);
        self.branches
            .iter()
            .filter_map(|b| {
                b.element.source_clock().and_then(|(frequency, _)| {
                    if frequency != 0.0 {
                        Some(1.0 / (frequency.abs() * 32.0))
                    } else {
                        None
                    }
                })
            })
            .fold(impulse, f64::min)
    }

    /// Exact algebraic equilibrium between boundary transactions. Explicitly
    /// enumerate models so a future time-dependent/dynamic model cannot opt in
    /// accidentally. Parameter/topology transactions always rebuild this point.
    fn constant_algebraic(&self) -> bool {
        self.branches.iter().all(|b| {
            matches!(
                b.element,
                Element::Resistor { .. }
                    | Element::DcVoltageSource { .. }
                    | Element::Meter { .. }
                    | Element::ShaftBoundary { .. }
            )
        })
    }

    fn constant_electrical(&self) -> bool {
        self.branches.iter().all(|b| match b.element {
            Element::Resistor { .. }
            | Element::DcVoltageSource { .. }
            | Element::Meter { .. }
            | Element::ShaftBoundary { .. } => true,
            Element::ThermalResistor(model) => model.coefficient_per_kelvin == 0.0,
            _ => false,
        })
    }

    /// Full-step and two-half-step paths start from the same accepted state.
    /// Returns only the refined trajectory's ledger; neither path is committed.
    pub(crate) fn refined_trial(
        &self,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<(AcceptedState, f64), Diagnostic> {
        accuracy.validate()?;
        if self.constant_algebraic() && self.accepted.sampler.channels.is_empty() {
            // Constant power, voltage and current integrate exactly; there is no
            // dynamic truncation error or filter history to estimate by doubling.
            return self.trial(end).map(|candidate| (candidate, 0.0));
        }
        let full = self.trial(end)?;
        let middle = self.accepted.time_seconds + (end - self.accepted.time_seconds) * 0.5;
        if middle <= self.accepted.time_seconds || middle >= end {
            return Err(rejected(
                "Step cannot be bisected at floating-point resolution",
            ));
        }
        let half = self.trial(middle)?;
        let refined = self.trial_from(&half, end)?;
        let mut error = 0.0_f64;
        let norm = |a: f64, b: f64, old: f64, absolute: f64| {
            let difference = (a - b).abs();
            let scale = absolute + accuracy.relative * a.abs().max(old.abs());
            if !difference.is_finite() || !scale.is_finite() {
                f64::INFINITY
            } else {
                difference / scale
            }
        };
        for (((branch, old), coarse), fine) in self
            .branches
            .iter()
            .zip(&self.accepted.operating_point.branches)
            .zip(&full.operating_point.branches)
            .zip(&refined.operating_point.branches)
        {
            error = error.max(norm(
                fine.voltage,
                coarse.voltage,
                old.voltage,
                accuracy.voltage,
            ));
            error = error.max(norm(
                fine.current,
                coarse.current,
                old.current,
                accuracy.current,
            ));
            match branch.element {
                Element::Regulator(_) => {
                    if [
                        fine.local_values[5],
                        coarse.local_values[5],
                        old.local_values[5],
                    ]
                    .iter()
                    .any(|value| *value < 0.0)
                    {
                        return Err(rejected("Negative RMS detector squared voltage"));
                    }
                    error = error.max(norm(
                        fine.local_values[5].sqrt(),
                        coarse.local_values[5].sqrt(),
                        old.local_values[5].sqrt(),
                        accuracy.voltage,
                    ));
                }
                Element::ThermalResistor(_)
                | Element::TappedWire(_)
                | Element::Arc(_) => {
                    let (model, row) = branch.element.thermal_node().unwrap();
                    error = error.max(norm(
                        model.heat_capacity_joules_per_kelvin * fine.local_values[row],
                        model.heat_capacity_joules_per_kelvin * coarse.local_values[row],
                        model.heat_capacity_joules_per_kelvin * old.local_values[row],
                        accuracy.energy,
                    ));
                }
                Element::Bipolar(_)
                | Element::Mosfet(_)
                | Element::Windings(_) => {
                    for j in 3..fine.local_values.len() {
                        error = error.max(norm(
                            fine.local_values[j],
                            coarse.local_values[j],
                            old.local_values[j],
                            if j % 2 == 0 {
                                accuracy.current
                            } else {
                                accuracy.voltage
                            },
                        ));
                    }
                    let old_q = branch.element.evaluate(&old.local_values, 0.0)?.charge;
                    let fine_q = branch.element.evaluate(&fine.local_values, 0.0)?.charge;
                    let coarse_q = branch.element.evaluate(&coarse.local_values, 0.0)?.charge;
                    for j in [2, 4] {
                        error = error.max(norm(
                            fine_q[j],
                            coarse_q[j],
                            old_q[j],
                            if matches!(branch.element, Element::Windings(_)) {
                                accuracy.flux
                            } else {
                                accuracy.charge
                            },
                        ));
                    }
                }
                Element::Capacitor { farads }
                | Element::Diode {
                    junction_farads: farads,
                    ..
                } => {
                    error = error.max(norm(
                        farads * fine.voltage,
                        farads * coarse.voltage,
                        farads * old.voltage,
                        accuracy.charge,
                    ))
                }
                Element::Inductor { henries } => {
                    error = error.max(norm(
                        henries * fine.current,
                        henries * coarse.current,
                        henries * old.current,
                        accuracy.flux,
                    ))
                }
                _ => {}
            }
        }
        let before = self.accepted.energy;
        for (coarse, fine, old) in [
            (
                full.energy.source_supplied_joules,
                refined.energy.source_supplied_joules,
                before.source_supplied_joules,
            ),
            (
                full.energy.source_absorbed_joules,
                refined.energy.source_absorbed_joules,
                before.source_absorbed_joules,
            ),
            (
                full.energy.dissipated_joules,
                refined.energy.dissipated_joules,
                before.dissipated_joules,
            ),
        ] {
            error = error.max(norm(fine - old, coarse - old, 0.0, accuracy.energy));
        }
        if !error.is_finite() {
            return Err(rejected("Non-finite adaptive error estimate"));
        }
        Ok((refined, error))
    }

    /// Private candidate; callers may inspect/discard without changing accepted history.
    pub fn trial(&self, end: f64) -> Result<AcceptedState, Diagnostic> {
        self.trial_from(&self.accepted, end)
    }

    fn trial_from(&self, accepted: &AcceptedState, end: f64) -> Result<AcceptedState, Diagnostic> {
        let h = end - accepted.time_seconds;
        if !end.is_finite() || !h.is_finite() || h <= 0.0 {
            return Err(rejected("Step end must advance finite physical time"));
        }
        let old = &accepted.operating_point;
        let mut source_phases = accepted.source_phases.clone();
        let mut advanced_clocks = std::collections::HashSet::new();
        for branch in &self.branches {
            if let Some((hertz, _)) = branch.element.source_clock() {
                if !advanced_clocks.insert(branch.clock_id()) {
                    continue;
                }
                let cycles = hertz * h;
                if !cycles.is_finite() {
                    return Err(rejected("Non-finite source phase interval"));
                }
                let phase = source_phases
                    .get_mut(&branch.clock_id())
                    .ok_or_else(|| rejected("Missing accepted source phase"))?;
                *phase = (*phase + std::f64::consts::TAU * cycles.fract())
                    .rem_euclid(std::f64::consts::TAU);
            }
        }
        let previous_by_id: std::collections::HashMap<_, _> =
            old.branches.iter().map(|b| (b.id, b)).collect();
        // History is immutable for this entire trial, including Newton backtracking.
        // Evaluate accepted charges once rather than once per residual/Jacobian call.
        let constant_electrical = self.constant_electrical();
        let history_charge = self
            .branches
            .iter()
            .filter(|_| !constant_electrical)
            .map(|branch| {
                let previous = previous_by_id[&branch.id];
                evaluate_at_phase(
                    branch,
                    &previous.local_values,
                    accepted.time_seconds,
                    &accepted.source_phases,
                )
                .map(|evaluation| (branch.id, evaluation.charge))
            })
            .collect::<Result<std::collections::HashMap<_, _>, _>>()?;
        let evaluate = |branch: &DcBranch, values: &[f64]| {
            let history = &history_charge[&branch.id];
            let mut e = evaluate_at_phase(branch, values, end, &source_phases)?;
            for ((residual, charge), previous) in e.residual.iter_mut().zip(&e.charge).zip(history)
            {
                *residual += (charge - previous) / h;
            }
            for j in 0..e.residual_jacobian.len() {
                e.residual_jacobian[j] += e.charge_jacobian[j] / h;
            }
            Ok(e)
        };
        let point = if constant_electrical {
            let mut point = old.clone();
            for (branch, value) in self.branches.iter().zip(&mut point.branches) {
                if let Element::ThermalResistor(model) = branch.element {
                    // With zero temperature coefficient, the thermal row is
                    // one-way coupled to a constant electrical equilibrium.
                    // Solve exactly the same backward-Euler row locally.
                    let c = model.heat_capacity_joules_per_kelvin;
                    let heat = model.ambient_ohms * value.current * value.current;
                    value.local_values[3] = (c * value.local_values[3] + h * heat)
                        / (c + h * model.conductance_watts_per_kelvin);
                    let evaluation = model.evaluate(&value.local_values)?;
                    value.stored_joules = evaluation.stored_joules;
                    value.dissipated_watts = evaluation.dissipated_watts;
                }
            }
            point
        } else if self.branches.iter().any(|b| b.element.is_nonlinear()) {
            super::newton::solve_compiled(&self.branches, end, Some(old), &self.layout, evaluate)?
        } else {
            dc::solve_compiled(&self.branches, end, &self.layout, |b| {
                evaluate(b, &vec![0.0; b.element.unknown_count()])
            })?
        };
        let mut energy = accepted.energy;
        energy.stored_joules = 0.0;
        for (branch, (before, after)) in self
            .branches
            .iter()
            .zip(old.branches.iter().zip(&point.branches))
        {
            energy.stored_joules += after.stored_joules;
            energy.dissipated_joules += h * after.dissipated_watts;
            match branch.element {
                Element::Windings(model) => {
                    energy.numerical_damping_joules += model.energy(
                        after.local_values[2] - before.local_values[2],
                        after.local_values[4] - before.local_values[4],
                    );
                }
                Element::Bipolar(_) | Element::Mosfet(_) => {
                    let (return_cap, output_cap) = branch.element.control_capacitances().unwrap();
                    let a = &before.local_values;
                    let b = &after.local_values;
                    energy.numerical_damping_joules +=
                        0.5 * return_cap * ((b[3] - b[1]) - (a[3] - a[1])).powi(2)
                            + 0.5 * output_cap * ((b[3] - b[0]) - (a[3] - a[0])).powi(2);
                }
                Element::DcVoltageSource { .. }
                | Element::SineVoltageSource { .. }
                | Element::ShaftBoundary { .. }
                | Element::AcShaft { .. } => {
                    // Shaft work is an external power boundary, with exactly the
                    // same signed energy as its electrical port. Per-branch lifetime
                    // observations distinguish mechanical work from supply-source work.
                    energy.source_supplied_joules += h * (-after.absorbed_watts).max(0.0);
                    energy.source_absorbed_joules += h * after.absorbed_watts.max(0.0);
                }
                Element::FieldShaft { .. } | Element::FieldAcShaft { .. } => {
                    energy.source_supplied_joules += h * (-after.absorbed_watts).max(0.0);
                    energy.source_absorbed_joules += h * after.absorbed_watts.max(0.0);
                }
                Element::Impulse { .. } | Element::Audio(_) => {
                    let external = after.absorbed_watts - after.dissipated_watts;
                    energy.source_supplied_joules += h * (-external).max(0.0);
                    energy.source_absorbed_joules += h * external.max(0.0);
                }
                Element::Capacitor { farads }
                | Element::Diode {
                    junction_farads: farads,
                    ..
                } => {
                    energy.numerical_damping_joules +=
                        0.5 * farads * (after.voltage - before.voltage).powi(2)
                }
                Element::Inductor { henries } => {
                    energy.numerical_damping_joules +=
                        0.5 * henries * (after.current - before.current).powi(2)
                }
                _ => {}
            }
        }
        if !energy.is_finite() {
            return Err(rejected("Non-finite candidate energy ledger"));
        }
        let mut measurements = accepted.measurements.clone();
        measurements.integrate(&old.branches, &point.branches, h)?;
        for ((branch, point), moments) in self
            .branches
            .iter()
            .zip(&point.branches)
            .zip(&mut measurements.branches)
        {
            let torque = match branch.element {
                Element::AcShaft { k, phase, .. } => {
                    k * (source_phases[&branch.clock_id()] + phase).sin() * point.current
                }
                Element::FieldShaft { k, .. } => k * point.local_values[4] * point.current,
                Element::FieldAcShaft { k, phase, .. } => {
                    k * (source_phases[&branch.clock_id()] + phase).sin()
                        * point.local_values[4]
                        * point.current
                }
                _ => 0.0,
            };
            moments.torque_seconds += torque * h;
            if !moments.torque_seconds.is_finite() {
                return Err(rejected("Non-finite accepted shaft torque integral"));
            }
        }
        let mut sampler = accepted.sampler.candidate();
        sampler.integrate(accepted.time_seconds, end, &point)?;
        Ok(AcceptedState {
            time_seconds: end,
            operating_point: point,
            energy,
            measurements,
            sampler,
            source_phases,
        })
    }

    /// Recomputes and commits only a successful candidate; failure leaves all state unchanged.
    pub fn step(&mut self, end: f64) -> Result<&AcceptedState, Diagnostic> {
        let candidate = self.trial(end)?;
        self.commit_owned_candidate(candidate);
        Ok(&self.accepted)
    }

    pub(crate) fn commit_owned_candidate(&mut self, mut candidate: AcceptedState) {
        candidate.sampler.commit_samples(&mut self.accepted.sampler);
        self.accepted = candidate;
    }
}

pub(crate) fn evaluate_at_phase(
    branch: &DcBranch,
    values: &[f64],
    time: f64,
    phases: &std::collections::HashMap<em_protocol::contract::Id, f64>,
) -> Result<crate::model::Evaluation, Diagnostic> {
    if branch.element.source_clock().is_some() {
        let phase = *phases
            .get(&branch.clock_id())
            .ok_or_else(|| rejected("Missing accepted source clock"))?;
        if let Element::FieldAcShaft {
            k,
            omega,
            phase: offset,
            ..
        } = branch.element
        {
            return Element::FieldShaft {
                k: k * (phase + offset).sin(),
                omega,
            }
            .evaluate(values, time);
        }
        if let Element::Mixer { ratio, .. } = branch.element {
            return Element::Mixer {
                hertz: 0.0,
                phase,
                ratio,
            }
            .evaluate(values, 0.0);
        }
        if let Element::RadioTransmitter { ref audio, .. } = branch.element {
            return Element::Mixer {
                hertz: 0.0,
                phase,
                ratio: 0.5 * (1.0 + audio.voltage(time).clamp(-1.0, 1.0)),
            }
            .evaluate(values, 0.0);
        }
        Element::DcVoltageSource {
            volts: branch.element.source_voltage(phase).unwrap(),
        }
        .evaluate(values, 0.0)
    } else {
        branch.element.evaluate(values, time)
    }
}
