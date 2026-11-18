//! Backward Euler with immutable candidates, explicit storage and nonlinear Newton trials.
use crate::{
    model::{
        DeviceModel,
        element::{Element, rejected},
    },
    observation::{EnergyLedger, Measurements},
    solver::dc::{self, DcBranch},
    state::{AcceptedState, InitialConditions},
};
use em_protocol::contract::Diagnostic;

mod islands;

/// Frozen topology owns its accepted state; clients cannot substitute a foreign history.
#[derive(Clone)]
pub struct TransientCircuit {
    pub(crate) branches: std::sync::Arc<Vec<DcBranch>>,
    pub(crate) layout: std::sync::Arc<crate::topology::Layout>,
    pub(crate) accepted: AcceptedState,
    pub(crate) plans: std::sync::Arc<std::sync::OnceLock<islands::Plans>>,
}

pub(crate) struct Trial {
    pub(crate) end: AcceptedState,
    pub(crate) intermediate: Vec<AcceptedState>,
    pub(crate) error: f64,
}

impl Trial {
    pub(crate) fn single(end: AcceptedState, error: f64) -> Self {
        Self { end, intermediate: Vec::new(), error }
    }
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

type OpenWindings = std::collections::HashMap<em_protocol::contract::Id,[bool;2]>;

impl TransientCircuit {
    /// None requests a DC operating point. Some requires every C/L initial value.
    /// Algebraically inconsistent/underdetermined initial constraints are rejected.
    pub fn new(
        branches: Vec<DcBranch>,
        initial: Option<&InitialConditions>,
    ) -> Result<Self, Diagnostic> {
        Self::initialize_at(branches, initial, 0.0, &std::collections::HashMap::new(), None, None)
    }

    fn open_inductors(branches: &[DcBranch]) -> Result<OpenWindings, Diagnostic> {
        let mut adjacency = std::collections::HashMap::new();
        for branch in branches {
            let ports: Vec<_> = branch.ports().collect();
            for (index,(a,b)) in branch.element.electrical_edges().into_iter().enumerate() {
                let edge=(branch.id,index);
                adjacency.entry(ports[a]).or_insert_with(Vec::new).push((ports[b],edge));
                adjacency.entry(ports[b]).or_insert_with(Vec::new).push((ports[a],edge));
            }
        }
        let mut open = OpenWindings::new();
        for branch in branches {
            if !matches!(branch.element, Element::Inductor { .. } | Element::Windings(_)) { continue; }
            let ports: Vec<_> = branch.ports().collect();
            let mut cut=[false;2];
            for (index,(a,b)) in branch.element.electrical_edges().into_iter().enumerate() {
                let mut seen=std::collections::HashSet::from([ports[a]]);
                let mut pending=vec![ports[a]];
                while let Some(port)=pending.pop() {
                    super::cooperative::check_tick_deadline()?;
                    if seen.contains(&ports[b]) {break;}
                    for &(next,edge) in adjacency.get(&port).into_iter().flatten() {
                        if edge!=(branch.id,index) && seen.insert(next) {pending.push(next);}
                    }
                }
                cut[index]=!seen.contains(&ports[b]);
            }
            if cut.iter().any(|open| *open) {open.insert(branch.id,cut);}
        }
        Ok(open)
    }

    fn initialize_at(
        branches: Vec<DcBranch>,
        initial: Option<&InitialConditions>,
        time: f64,
        previous_phases: &std::collections::HashMap<em_protocol::contract::Id, f64>,
        open_inductors: Option<OpenWindings>,
        reusable_layout: Option<std::sync::Arc<crate::topology::Layout>>,
    ) -> Result<Self, Diagnostic> {
        let profile = super::progress::InitializationProfile::begin();
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
        let point_started = super::progress::InitializationProfile::timer();
        let point = if let Some(initial) = initial {
            let dynamic_count = branches.iter().filter(|b| b.element.is_dynamic()).count();
            if initial.len() != dynamic_count {
                return Err(rejected(
                    "Initial storage must identify exactly every dynamic branch",
                ));
            }
            let open_inductors = match open_inductors { Some(open) => open, None => Self::open_inductors(&branches)? };
            let evaluate = |branch: &DcBranch, values: &[f64]| {
                let mut e = evaluate_at_phase(branch, values, time, &source_phases)?;
                branch.element.constrain_storage(
                    initial.get(&branch.id), values, &mut e, open_inductors.get(&branch.id).copied().unwrap_or_default(),
                ).map_err(|mut error| {
                    error.reason = format!("{}; branch={:?}, ports={:?}/{:?}, model={:?}, initial={:?}",
                        error.reason, branch.id, branch.positive, branch.negative, branch.element, initial.get(&branch.id));
                    error
                })?;
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
            dc::equilibrium(&branches, &layout)?
        };
        if let (Some(profile), Some(started)) = (profile, point_started) {
            profile.report_point(started, branches.len());
        }
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
            branches: std::sync::Arc::new(branches),
            plans: Default::default(),
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

    pub(crate) fn trial_seed(&self, seed: Option<&AcceptedState>) -> Self {
        let seed = seed.unwrap_or(&self.accepted);
        let mut measurements = seed.measurements.clone();
        measurements.reset_window(seed.time_seconds);
        Self {
            branches: self.branches.clone(),
            plans: self.plans.clone(),
            layout: self.layout.clone(),
            accepted: AcceptedState {
                time_seconds: seed.time_seconds,
                operating_point: seed.operating_point.clone(),
                energy: seed.energy,
                measurements,
                sampler: seed.sampler.candidate(),
                source_phases: seed.source_phases.clone(),
            },
        }
    }

    /// Builds a private parameter candidate at the accepted boundary, preserving q/lambda.
    /// Storage edits conserve q/lambda and account the resulting external boundary work.
    pub(crate) fn with_parameters(
        &self,
        changes: &[(em_protocol::contract::Id, Element)],
    ) -> Result<Self, Diagnostic> {
        let mut branches = self.branches.as_ref().clone();
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
            self.rebuild_with_charge(self.branches.as_ref().clone(), true, false, Some((id, charge)))?;
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
        let can_reuse_layout = branches.len() == self.branches.len()
            && branches.iter().zip(self.branches.iter()).all(|(next, old)| {
                next.id == old.id
                    && next.ports().eq(old.ports())
                    && next.element.unknown_count() == old.element.unknown_count()
                    && next.element.internal_state() == old.element.internal_state()
                    && next.element.electrical_edges() == old.element.electrical_edges()
            });
        let layout = if can_reuse_layout {
            self.layout.clone()
        } else {
            std::sync::Arc::new(crate::topology::Layout::compile(&branches)?)
        };
        let open_inductors = Self::open_inductors(&branches)?;
        let mut opening_loss_joules = 0.0;
        let mut storage = InitialConditions::new();
        let mut expected_storage = 0.0;
        let mut changed_storage_parameter = false;
        for branch in &branches {
            let previous = self.layout.branch_indices.get(&branch.id).map(|&i| {
                (&self.branches[i].element, self.accepted.operating_point.branches[i].local_values.as_slice())
            });
            let charge = override_charge.filter(|(id, _)| *id == branch.id).map(|(_, value)| value);
            if let Some((initial, joules, changed)) =
                branch.element.transfer_storage(previous, allow_parameter_work, charge)?
            {
                if let Some(open)=open_inductors.get(&branch.id) {
                    // KCL forces zero current on a cut edge. Dissipate its retained
                    // magnetic energy at the edit boundary, in this candidate only.
                    use crate::state::InitialStorage::*;
                    let (retained, retained_joules)=match (&branch.element,initial) {
                        (Element::Inductor {..}, FluxWebers(_)) => (FluxWebers(0.0),0.0),
                        (Element::Windings(model),WindingFluxes {primary,secondary}) => {
                            let ip=if open[0] {0.0} else {primary/model.primary_henries};
                            let is=if open[1] {0.0} else {secondary/model.secondary_henries};
                            let (primary,secondary)=model.fluxes(ip,is);
                            (WindingFluxes {primary,secondary},model.energy(ip,is))
                        }
                        _ => return Err(rejected("Open winding storage mismatch")),
                    };
                    storage.insert(branch.id, retained);
                    expected_storage += retained_joules;
                    opening_loss_joules += (joules-retained_joules).max(0.0);
                } else {
                    storage.insert(branch.id, initial);
                    expected_storage += joules;
                }
                changed_storage_parameter |= changed;
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
            Some(open_inductors),
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
        candidate.accepted.energy.dissipated_joules += opening_loss_joules;
        if allow_parameter_work && changed_storage_parameter {
            candidate.accepted.energy.parameter_work_joules += next - previous + removed + opening_loss_joules;
        } else if (previous - removed - opening_loss_joules - next).abs() > 1e-12 + 1e-9 * previous.abs().max(next.abs())
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
    pub(crate) fn constant_algebraic(&self) -> bool {
        self.branches.iter().all(|b| {
            matches!(
                b.element,
                Element::Resistor { .. }
                    | Element::DcVoltageSource { .. }
                    | Element::Meter { .. }
            )
        })
    }

    fn constant_electrical(&self) -> bool {
        self.branches.iter().all(|b| match b.element {
            Element::Resistor { .. }
            | Element::DcVoltageSource { .. }
            | Element::Meter { .. } => true,
            Element::ThermalResistor(model) => model.coefficient_per_kelvin == 0.0,
            Element::TappedWire(model) => model.thermal.coefficient_per_kelvin == 0.0,
            _ => false,
        })
    }

    /// Full-step and two-half-step paths start from the same accepted state.
    /// Returns only the refined trajectory's ledger; neither path is committed.
    pub(crate) async fn refined_trial(
        &self,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<Trial, super::SolveError> {
        accuracy.validate()?;
        if self.accepted.sampler.channels.is_empty() && !self.constant_algebraic() {
            if let Some(candidate) = self.island_trial(end, accuracy).await? {
                return Ok(candidate);
            }
        }
        self.refine_local(end, accuracy).await
    }

    async fn refine_local(&self, end: f64, accuracy: Accuracy) -> Result<Trial, super::SolveError> {
        if self.constant_algebraic() && self.accepted.sampler.channels.is_empty() {
            // Constant power, voltage and current integrate exactly; there is no
            // dynamic truncation error or filter history to estimate by doubling.
            let seconds = end - self.accepted.time_seconds;
            if !seconds.is_finite() || seconds <= 0.0 {
                return Err(super::permanent("Step end must advance finite physical time"));
            }
            let mut candidate = self.accepted.clone();
            for ((model, point), moments) in self.branches.iter()
                .zip(&candidate.operating_point.branches).zip(&mut candidate.measurements.branches)
            {
                super::cooperative::checkpoint().await;
                moments.integrate(point, point, seconds)?;
                candidate.energy.dissipated_joules += seconds * point.dissipated_watts;
                if matches!(model.element, Element::DcVoltageSource { .. }) {
                    candidate.energy.source_supplied_joules += seconds * (-point.absorbed_watts).max(0.0);
                    candidate.energy.source_absorbed_joules += seconds * point.absorbed_watts.max(0.0);
                }
            }
            if !candidate.energy.is_finite() {
                return Err(rejected("Non-finite constant DC energy ledger").into());
            }
            candidate.time_seconds = end;
            super::progress::fast_trial();
            return Ok(Trial::single(candidate, 0.0));
        }
        let full = self.trial_async(end).await?;
        let middle = self.accepted.time_seconds + (end - self.accepted.time_seconds) * 0.5;
        if middle <= self.accepted.time_seconds || middle >= end {
            return Err(super::permanent("Step cannot be bisected at floating-point resolution"));
        }
        let half = self.trial_async(middle).await?;
        let refined = self.trial_from(&half, end).await?;
        let error = self.refinement_error(&full, &refined, accuracy).await?;
        if error > 1.0 {
            // A stiff, rapidly decaying mode can make one full backward-Euler
            // step a poor reference even when two half steps have settled.
            // Verify against four quarter steps before shrinking physical time
            // toward its representability limit. All state and energy error
            // checks still apply; only the verified finer path is returned.
            let first = self.accepted.time_seconds + (end - self.accepted.time_seconds) * 0.25;
            let third = middle + (end - middle) * 0.5;
            if first > self.accepted.time_seconds && first < middle && third > middle && third < end {
                let quarter = self.trial_async(first).await?;
                let second = self.trial_from(&quarter, middle).await?;
                let third = self.trial_from(&second, third).await?;
                let finer = self.trial_from(&third, end).await?;
                let finer_error = self.refinement_error(&refined, &finer, accuracy).await?;
                if finer_error <= 1.0 { return Ok(Trial { end: finer, intermediate: vec![quarter, second, third], error: finer_error }); }
            }
        }
        Ok(Trial { end: refined, intermediate: vec![half], error })
    }

    async fn refinement_error(&self, full: &AcceptedState, refined: &AcceptedState, accuracy: Accuracy) -> Result<f64, Diagnostic> {
        let mut error = 0.0_f64;
        let mut electrical_storage_joules = 0.0;
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
            super::cooperative::checkpoint().await;
            if branch.element.thermal_node().is_none() {
                electrical_storage_joules += old.stored_joules.max(fine.stored_joules);
            }
            let previous_error = error;
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
                    let row = branch.element.detector_row().expect("regulator detector row");
                    if [
                        fine.local_values[row],
                        coarse.local_values[row],
                        old.local_values[row],
                    ]
                    .iter()
                    .any(|value| *value < 0.0)
                    {
                        return Err(rejected("Negative RMS detector squared voltage"));
                    }
                    error = error.max(norm(
                        fine.local_values[row].sqrt(),
                        coarse.local_values[row].sqrt(),
                        old.local_values[row].sqrt(),
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
                    if let Element::Windings(model) = branch.element {
                        // The two storage rows are the negative winding fluxes.
                        // The error norm is invariant under that common sign.
                        let flux = |x: &[f64]| model.fluxes(x[2], x[4]);
                        let (fp, fs) = flux(&fine.local_values);
                        let (cp, cs) = flux(&coarse.local_values);
                        let (op, os) = flux(&old.local_values);
                        error = error.max(norm(fp, cp, op, accuracy.flux));
                        error = error.max(norm(fs, cs, os, accuracy.flux));
                    } else {
                        let old_q = branch.element.evaluate(&old.local_values, 0.0)?.charge;
                        let fine_q = branch.element.evaluate(&fine.local_values, 0.0)?.charge;
                        let coarse_q = branch.element.evaluate(&coarse.local_values, 0.0)?.charge;
                        for j in [2, 4] {
                            error = error.max(norm(fine_q[j], coarse_q[j], old_q[j], accuracy.charge));
                        }
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
            if error > previous_error { super::progress::error(branch.id, error); }
        }
        // Reversible work can cross zero while a circuit still holds substantial
        // magnetic or electric energy. Reference ledger errors to that active
        // inventory as well as the local transfer. Exclude accumulated thermal
        // storage and lifetime work, which can grow independently of this step.
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
            error = error.max(norm(
                fine - old,
                coarse - old,
                electrical_storage_joules,
                accuracy.energy,
            ));
        }
        if !error.is_finite() {
            return Err(rejected("Non-finite adaptive error estimate"));
        }
        super::progress::global_error(error);
        Ok(error)
    }

    /// Private candidate; callers may inspect/discard without changing accepted history.
    pub fn trial(&self, end: f64) -> Result<AcceptedState, Diagnostic> {
        super::cooperative::complete(self.trial_async(end)).map_err(Into::into)
    }

    pub(crate) async fn trial_async(&self, end: f64) -> Result<AcceptedState, super::SolveError> {
        self.trial_from(&self.accepted, end).await
    }

    async fn trial_from(&self, accepted: &AcceptedState, end: f64) -> Result<AcceptedState, super::SolveError> {
        let h = end - accepted.time_seconds;
        if !end.is_finite() || !h.is_finite() || h <= 0.0 {
            return Err(super::permanent("Step end must advance finite physical time"));
        }
        let old = &accepted.operating_point;
        let mut source_phases = accepted.source_phases.clone();
        let mut advanced_clocks = std::collections::HashSet::new();
        for branch in self.branches.iter() {
            super::cooperative::checkpoint().await;
            if let Some((hertz, _)) = branch.element.source_clock() {
                if !advanced_clocks.insert(branch.clock_id()) {
                    continue;
                }
                let cycles = hertz * h;
                if !cycles.is_finite() {
                    return Err(rejected("Non-finite source phase interval").into());
                }
                let phase = source_phases
                    .get_mut(&branch.clock_id())
                    .ok_or_else(|| super::permanent("Missing accepted source phase"))?;
                *phase = (*phase + std::f64::consts::TAU * cycles.fract())
                    .rem_euclid(std::f64::consts::TAU);
            }
        }
        // History is immutable for this entire trial, including Newton backtracking.
        // Evaluate accepted charges once rather than once per residual/Jacobian call.
        let constant_electrical = self.constant_electrical();
        let mut history_charge = Vec::new();
        if !constant_electrical {
            for (branch, previous) in self.branches.iter().zip(&old.branches) {
                super::cooperative::checkpoint().await;
                let charge = if let Element::Windings(model) = branch.element {
                    let x = &previous.local_values;
                    let (primary, secondary) = model.fluxes(x[2], x[4]);
                    vec![0.0, 0.0, -primary, 0.0, -secondary, 0.0, 0.0]
                } else {
                    evaluate_at_phase(
                        branch, &previous.local_values, accepted.time_seconds, &accepted.source_phases,
                    )?.charge
                };
                history_charge.push(charge);
            }
        }
        let evaluate = |branch: &DcBranch, values: &[f64]| {
            let history = &history_charge[self.layout.branch_indices[&branch.id]];
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
            super::progress::fast_trial();
            let mut point = old.clone();
            for (branch, value) in self.branches.iter().zip(&mut point.branches) {
                super::cooperative::checkpoint().await;
                if let Some((model, thermal_row)) = branch.element.thermal_node() {
                    // With zero temperature coefficient, the thermal row is
                    // one-way coupled to a constant electrical equilibrium.
                    // Solve exactly the same backward-Euler row locally.
                    let c = model.heat_capacity_joules_per_kelvin;
                    // Tapped wires sum segment heating; terminal power alone
                    // cannot represent their internal current distribution.
                    let mut cold = value.local_values.clone();
                    cold[thermal_row] = 0.0;
                    let heat = -branch.element.evaluate(&cold, end)?.residual[thermal_row];
                    value.local_values[thermal_row] = (c * value.local_values[thermal_row] + h * heat)
                        / (c + h * model.conductance_watts_per_kelvin);
                    let evaluation = branch.element.evaluate(&value.local_values, end)?;
                    value.stored_joules = evaluation.stored_joules;
                    value.dissipated_watts = evaluation.dissipated_watts;
                }
            }
            point
        } else if self.branches.iter().any(|b| {
            b.element.is_nonlinear() && b.element.fixed_resistance_thermal_row().is_none()
        }) {
            super::progress::newton_trial();
            super::newton::solve_compiled_async(&self.branches, end, Some(old), &self.layout, evaluate).await?
        } else {
            let mut values = dc::solve_compiled_values_async(&self.branches, &self.layout, |b| {
                evaluate(b, &vec![0.0; b.element.unknown_count()])
            })
            .await?;
            // The linear assembly evaluated at zero current/voltage omits
            // Joule heating. Reconstruct the affine backward-Euler heat row
            // using the solved electrical values before constructing the candidate point.
            for (i, branch) in self.branches.iter().enumerate() {
                super::cooperative::checkpoint().await;
                if let Some(row) = branch.element.fixed_resistance_thermal_row() {
                    let mut local = self.layout.local(i, &values);
                    local[row] = 0.0;
                    let e = evaluate(branch, &local)?;
                    let column = self.layout.mappings[i][row].expect("thermal unknown");
                    values[column] = -e.residual[row] / e.residual_jacobian[row * local.len() + row];
                }
            }
            dc::point(&self.branches, end, &self.layout, &values).await?
        };
        let mut energy = accepted.energy;
        energy.stored_joules = 0.0;
        for (branch, (before, after)) in self
            .branches
            .iter()
            .zip(old.branches.iter().zip(&point.branches))
        {
            super::cooperative::checkpoint().await;
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
            return Err(rejected("Non-finite candidate energy ledger").into());
        }
        let mut measurements = accepted.measurements.clone();
        measurements.integrate(&old.branches, &point.branches, h)?;
        for ((branch, point), moments) in self
            .branches
            .iter()
            .zip(&point.branches)
            .zip(&mut measurements.branches)
        {
            super::cooperative::checkpoint().await;
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
                return Err(rejected("Non-finite accepted shaft torque integral").into());
            }
        }
        let mut sampler = accepted.sampler.candidate();
        sampler.integrate_solver(accepted.time_seconds, end, &point)?;
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
