//! Ordered pure-Rust circuit session with atomic accepted-boundary circuit edits.
//! No JNI/world activation is implied by this standalone G2/G1 transaction baseline.
use crate::{
    model::element::rejected,
    solver::{SolveError, cooperative, transient::{Accuracy, TransientCircuit, Trial}},
    state::AcceptedState,
};
use em_protocol::contract::{Accepted, Advance, Diagnostic, Revision, Status};
use std::{
    future::Future,
    pin::Pin,
    sync::atomic::{AtomicBool, Ordering},
    task::Poll,
    time::{Duration, Instant},
};

mod inputs;
mod protection;
mod snapshot;
#[cfg(test)]
mod tests;

type ParameterRequest = (
    Revision,
    f64,
    Vec<(
        em_protocol::contract::Id,
        crate::model::element::Element,
    )>,
);

// Unaccepted event-search work may survive a cooperative deadline. It is never
// exported as physical state and is invalidated by every circuit/input mutation.
struct ProtectionBracket {
    anchor: f64,
    low: f64,
    high: AcceptedState,
    seed: Option<AcceptedState>,
    triggers: Vec<usize>,
    // Only normal-accuracy trajectories move low/high. A single-step probe
    // proposes the next validation time and never becomes a trajectory seed.
    validation_time: Option<f64>,
    bisect: bool,
}

impl ProtectionBracket {
    fn estimate(&self, circuit: &TransientCircuit, low: &AcceptedState, high: &AcceptedState) -> Option<f64> {
        high.operating_point.branches.iter().enumerate().filter_map(|(i, point)| {
            let right = circuit.protection_margin(i, point)?;
            let left = circuit.protection_margin(i, &low.operating_point.branches[i])?;
            if left >= 0.0 || right < 0.0 { return None; }
            let fraction = -left / (right - left);
            Some(low.time_seconds + (high.time_seconds - low.time_seconds) * fraction)
        }).filter(|time| time.is_finite()).min_by(f64::total_cmp)
    }

    fn first(
        circuit: &TransientCircuit, seed: Option<&AcceptedState>, trial: Trial,
    ) -> Result<Result<Self, AcceptedState>, Diagnostic> {
        let anchor = circuit.accepted().time_seconds;
        let mut previous = seed.cloned();
        let states = trial.intermediate.into_iter().chain(std::iter::once(trial.end));
        for mut state in states {
            if let Some(seed) = seed {
                // Every substep result includes the whole window from its
                // trial start, so prepend only the original seed here.
                state.measurements.prepend_window(&seed.measurements)?;
                let mut sampler = seed.sampler.clone();
                state.sampler.commit_samples(&mut sampler);
            }
            let triggers = protection::triggered(circuit, &state);
            if !triggers.is_empty() {
                return Ok(Ok(Self {
                    anchor,
                    low: previous.as_ref().map_or(anchor, |s| s.time_seconds),
                    high: state,
                    seed: previous,
                    triggers, validation_time: None, bisect: false,
                }));
            }
            previous = Some(state);
        }
        Ok(Err(previous.expect("trajectory contains an endpoint")))
    }
}

fn cooperative_pause(cancelled: &AtomicBool, reason: &str) -> Diagnostic {
    Diagnostic {
        status: Status::Partial,
        reason: if cancelled.load(Ordering::Acquire) { "Cancelled" } else { reason }.into(),
    }
}

type TrialFuture = Pin<Box<dyn Future<Output = Result<Trial, SolveError>> + Send>>;

#[derive(Clone, Copy, PartialEq, Eq)]
enum TrialPurpose {
    Advance,
    ProtectionProbe,
    ProtectionValidation,
}

#[derive(Default)]
struct TrialResume {
    interval: Option<(f64, f64)>,
    preserve_window: bool,
}

#[derive(Default)]
struct TrialCursor {
    pending: Option<(f64, f64, TrialPurpose, TrialFuture)>,
}

impl TrialCursor {
    fn poll(
        &mut self, kernel: &TransientCircuit, seed: Option<&AcceptedState>, end: f64,
        accuracy: Option<Accuracy>, purpose: TrialPurpose, world_tick: bool, started: Instant, budget: Option<Duration>, cancelled: &AtomicBool,
    ) -> Poll<Result<Trial, SolveError>> {
        let anchor = seed.unwrap_or_else(|| kernel.accepted()).time_seconds;
        if !self.pending.as_ref().is_some_and(|p| p.0 == anchor && p.1 == end && p.2 == purpose) {
            let trial = kernel.trial_seed(seed);
            self.pending = Some((anchor, end, purpose, Box::pin(async move {
                if purpose == TrialPurpose::ProtectionProbe { return trial.protection_probe(end).await; }
                match accuracy {
                    Some(accuracy) => trial.refined_trial(end, accuracy, world_tick).await,
                    None => trial.trial_async(end).await.map(|state| Trial::single(state, 0.0)),
                }
            })));
        }
        let (_, _, _, future) = self.pending.as_mut().expect("installed trial");
        // Cancellation is observed between short synchronous polls, even when
        // the caller supplies a long or effectively unlimited deadline.
        let result = loop {
            let remaining = budget.map_or(Duration::MAX, |budget| budget.saturating_sub(started.elapsed()));
            if cancelled.load(Ordering::Acquire) || remaining.is_zero() {
                break Poll::Pending;
            }
            match cooperative::poll(future.as_mut(), Instant::now(), remaining.min(Duration::from_millis(2)), cancelled) {
                Poll::Pending => continue,
                ready => break ready,
            }
        };
        if result.is_ready() || cancelled.load(Ordering::Acquire) { self.pending = None; }
        result
    }
}

/// Private work for one immutable world input. Only the finished prefix is
/// published, under the caller's original transaction revision.
pub struct TickContinuation {
    candidate: CircuitSession,
    request: Advance,
    work: em_protocol::contract::AdvanceWork,
}

impl TickContinuation {
    pub fn new(candidate: CircuitSession, request: Advance) -> Self {
        Self { candidate, request, work: Default::default() }
    }

    pub fn poll(&mut self, cancelled: &AtomicBool) -> Result<Option<Accepted>, Diagnostic> {
        let start = self.candidate.accepted().time_seconds;
        let base = self.candidate.revision;
        let slice = Advance { accepted_start: start, ..self.request };
        let mut result = self.candidate.advance_request(slice, cancelled, true, start > self.request.accepted_start)?;
        let total = &mut self.work;
        let work = result.work;
        total.accepted_steps = total.accepted_steps.saturating_add(work.accepted_steps);
        total.rejected_steps = total.rejected_steps.saturating_add(work.rejected_steps);
        total.discarded_steps = total.discarded_steps.saturating_add(work.discarded_steps);
        total.attempted_steps = total.attempted_steps.saturating_add(work.attempted_steps);
        total.localization_trials = total.localization_trials.saturating_add(work.localization_trials);
        if result.diagnostic.status == Status::Partial && self.candidate.pending_events.is_empty()
            && !cancelled.load(Ordering::Acquire) {
            self.candidate.cached = None;
            self.candidate.revision = base;
            return Ok(None);
        }
        // The wire counters are signed on the Java side and the three
        // disposition counts must sum exactly to attempted steps.
        let limit = i32::MAX as u32;
        total.attempted_steps = total.attempted_steps.min(limit);
        total.accepted_steps = total.accepted_steps.min(total.attempted_steps);
        total.rejected_steps = total.rejected_steps.min(total.attempted_steps - total.accepted_steps);
        total.discarded_steps = total.attempted_steps - total.accepted_steps - total.rejected_steps;
        total.localization_trials = total.localization_trials.min(limit);
        result.work = *total;
        result.start = self.request.accepted_start;
        self.candidate.cached = Some((self.request, result.clone()));
        Ok(Some(result))
    }

    pub fn into_candidate(self) -> CircuitSession { self.candidate }
}

pub struct CircuitSession {
    localization: Option<ProtectionBracket>,
    trial: TrialCursor,
    interval: Option<em_protocol::contract::IntervalInputs>,
    external_cached: Option<(Revision, f64, em_protocol::contract::Id, f64, f64)>,
    pending_events: Vec<em_protocol::contract::ProtectionEvent>,
    next_event: u64,
    event_cached: Option<(
        Revision,
        f64,
        Vec<em_protocol::contract::Id>,
        Vec<em_protocol::contract::Id>,
    )>,
    revision: Revision,
    kernel: TransientCircuit,
    max_step_seconds: f64,
    cached: Option<(Advance, Accepted)>,
    accuracy: Option<Accuracy>,
    suggested_step: f64,
    parameter_cached: Option<ParameterRequest>,
    topology_cached: Option<(Revision, f64, Vec<crate::solver::dc::DcBranch>, bool, bool)>,
    subscription_cached: Option<em_protocol::contract::WaveformSubscription>,
}

impl CircuitSession {
    /// Build every destination before the registry commits any ownership change.
    /// All branch IDs occur exactly once; this operation neither removes nor creates storage.
    pub fn repartition(
        sources: &[(&Self, Revision)],
        targets: &[(Revision, Vec<em_protocol::contract::Id>)],
        time: f64,
    ) -> Result<Vec<Self>, Diagnostic> {
        use std::collections::{HashMap, HashSet};
        if sources.is_empty() || targets.is_empty() { return Err(rejected("Empty repartition")); }
        let first = sources[0].0;
        for (source, revision) in sources {
            if !time.is_finite() || source.revision != *revision || source.accepted().time_seconds > time
                || !source.pending_events.is_empty() || source.localization.is_some()
                || source.accuracy != first.accuracy || source.max_step_seconds != first.max_step_seconds {
                return Err(rejected("Repartition requires idle sources, a nondecreasing time and matching solver settings"));
            }
        }
        let mut combined = first.kernel.clone();
        let mut combined_branches = Vec::new();
        combined.accepted.operating_point.branches.clear();
        combined.accepted.operating_point.voltages.clear();
        combined.accepted.operating_point.references.clear();
        combined.accepted.measurements.branches.clear();
        combined.accepted.source_phases.clear();
        combined.accepted.energy = Default::default();
        combined.accepted.sampler = Default::default();
        let mut owners = HashMap::new();
        for (source, _) in sources {
            for (i, branch) in source.kernel.branches.iter().enumerate() {
                if owners.insert(branch.id, combined_branches.len()).is_some() { return Err(rejected("Duplicate source branch")); }
                combined_branches.push(branch.clone());
                combined.accepted.operating_point.branches.push(source.accepted().operating_point.branches[i].clone());
                combined.accepted.measurements.branches.push(source.accepted().measurements.branches[i].clone());
            }
            for (port, value) in &source.accepted().operating_point.voltages {
                if combined.accepted.operating_point.voltages.insert(*port, *value).is_some() { return Err(rejected("Source domains share a port")); }
            }
            combined.accepted.operating_point.references.extend(&source.accepted().operating_point.references);
            combined.accepted.source_phases.extend(source.accepted().source_phases.iter().map(|(id,phase)| (*id,*phase)));
            let e = source.accepted().energy;
            let total = &mut combined.accepted.energy;
            total.initial_stored_joules += e.initial_stored_joules;total.stored_joules += e.stored_joules;
            total.source_supplied_joules += e.source_supplied_joules;total.source_absorbed_joules += e.source_absorbed_joules;
            total.dissipated_joules += e.dissipated_joules;total.numerical_damping_joules += e.numerical_damping_joules;
            total.parameter_work_joules += e.parameter_work_joules;total.removed_joules += e.removed_joules;
        }
        // Missing intervals are excluded, not integrated. Preserve charge, heat,
        // source phase and lifetime energy while rebasing the destination clock.
        combined.accepted.time_seconds = time;
        combined.accepted.measurements.reset_window(time);
        combined.layout = std::sync::Arc::new(crate::topology::Layout::compile(&combined_branches)?);
        combined.branches = std::sync::Arc::new(combined_branches);
        if !combined.accepted.energy.is_finite() { return Err(rejected("Non-finite repartition ledger")); }
        let mut used = HashSet::new();let mut sessions = HashSet::new();let mut port_owners = HashMap::new();
        let mut candidates = Vec::new();
        for (target_index, (revision, ids)) in targets.iter().enumerate() {
            if !sessions.insert(revision.session) || revision.topology != 0 || revision.parameters != 0 || revision.sequence != 0 {
                return Err(rejected("Repartition destination must have a fresh session identity"));
            }
            let mut branches = Vec::new();
            for id in ids {
                if !used.insert(*id) { return Err(rejected("Branch assigned to multiple destinations")); }
                let branch = combined.branches[*owners.get(id).ok_or_else(|| rejected("Unknown repartition branch"))?].clone();
                for port in branch.ports() {
                    if let Some(previous) = port_owners.insert(port.device, target_index) {
                        if previous != target_index { return Err(rejected("Repartition splits a device dependency")); }
                    }
                }
                branches.push(branch);
            }
            let mut kernel = combined.rebuild_boundary(branches, false, true)?;
            let stored = kernel.accepted.energy.stored_joules;
            // Split aggregate accounting in proportion to retained storage. Physical
            // branch states and lifetime measurements above are copied by identity,
            // never averaged. With no storage the first destination owns the ledger.
            let total = combined.accepted.energy;
            let weight = if total.stored_joules > 0.0 { stored / total.stored_joules }
                else if target_index == 0 { 1.0 } else { 0.0 };
            kernel.accepted.energy = crate::observation::EnergyLedger {
                initial_stored_joules: total.initial_stored_joules * weight,
                stored_joules: stored,
                source_supplied_joules: total.source_supplied_joules * weight,
                source_absorbed_joules: total.source_absorbed_joules * weight,
                dissipated_joules: total.dissipated_joules * weight,
                numerical_damping_joules: total.numerical_damping_joules * weight,
                parameter_work_joules: total.parameter_work_joules * weight,
                removed_joules: total.removed_joules * weight,
            };
            if !kernel.accepted.energy.is_finite() { return Err(rejected("Invalid destination ledger")); }
            let mut candidate = Self::new(*revision, kernel, first.max_step_seconds)?;
            candidate.accuracy = first.accuracy;candidate.suggested_step = first.suggested_step;
            candidates.push(candidate);
        }
        if used.len() != owners.len() { return Err(rejected("Repartition omits retained branches")); }
        let stored: f64 = candidates.iter().map(|s| s.accepted().energy.stored_joules).sum();
        if (stored - combined.accepted.energy.stored_joules).abs() > 1e-12 + 1e-9 * stored.abs() {
            return Err(rejected("Repartition changed total storage"));
        }
        Ok(candidates)
    }

    /// Runtime work ceiling for an adaptive owner. Physical tolerances and
    /// accepted state remain snapshot-owned; fixed-step sessions retain theirs.
    pub fn adaptive_step_limit(&self) -> Option<f64> {
        self.accuracy.map(|_| self.max_step_seconds)
    }

    pub fn retain_adaptive_step_limit(&mut self, owner: Option<f64>) {
        if let (Some(seconds), Some(accuracy)) = (owner, self.accuracy) {
            // The owner ceiling was validated at session creation. Restored
            // physical accuracy must remain achievable even with another owner.
            self.max_step_seconds = seconds.max(accuracy.minimum_step);
            self.suggested_step = self.suggested_step.min(self.max_step_seconds);
            self.trial.pending = None;
            self.localization = None;
        }
    }

    pub fn new(
        revision: Revision,
        kernel: TransientCircuit,
        max_step_seconds: f64,
    ) -> Result<Self, Diagnostic> {
        if !max_step_seconds.is_finite() || max_step_seconds <= 0.0 {
            return Err(rejected("Maximum step must be positive and finite"));
        }
        Ok(Self {
            localization: None,
            trial: TrialCursor::default(),
            interval: None,
            external_cached: None,
            pending_events: Vec::new(),
            next_event: 1,
            event_cached: None,
            revision,
            kernel,
            max_step_seconds,
            cached: None,
            accuracy: None,
            suggested_step: max_step_seconds,
            parameter_cached: None,
            topology_cached: None,
            subscription_cached: None,
        })
    }

    pub fn new_adaptive(
        revision: Revision,
        kernel: TransientCircuit,
        max_step_seconds: f64,
        accuracy: Accuracy,
    ) -> Result<Self, Diagnostic> {
        accuracy.validate()?;
        if accuracy.minimum_step > max_step_seconds {
            return Err(rejected("Minimum step exceeds maximum step"));
        }
        let mut session = Self::new(revision, kernel, max_step_seconds)?;
        session.accuracy = Some(accuracy);
        Ok(session)
    }

    pub fn accepted(&self) -> &AcceptedState {
        self.kernel.accepted()
    }

    pub fn branches(&self) -> &[crate::solver::dc::DcBranch] { &self.kernel.branches }

    /// Exact all-or-nothing exchange with a bounded positive-voltage capacitor buffer.
    /// Queries never consume a sequence or alter physical state.
    pub fn exchange_energy(
        &mut self,
        revision: Revision,
        time: f64,
        id: em_protocol::contract::Id,
        delta: f64,
        maximum: f64,
        commit: bool,
    ) -> Result<bool, Diagnostic> {
        let request = (revision, time, id, delta, maximum);
        if commit && self.external_cached == Some(request) && self.revision == revision {
            return Ok(true);
        }
        if revision.session != self.revision.session
            || revision.topology != self.revision.topology
            || revision.parameters != self.revision.parameters
            || time != self.accepted().time_seconds
            || !delta.is_finite()
            || !maximum.is_finite()
            || maximum <= 0.0
            || revision.sequence
                != self
                    .revision
                    .sequence
                    .checked_add(u64::from(commit))
                    .ok_or_else(|| rejected("Sequence exhausted"))?
        {
            return Err(rejected(
                "External energy boundary revision/time/parameter conflict",
            ));
        }
        if !self.pending_events.is_empty() {
            return Err(rejected(
                "External energy boundary awaits protection confirmation",
            ));
        }
        let index = self
            .kernel
            .branches
            .iter()
            .position(|b| b.id == id)
            .ok_or_else(|| rejected("Unknown external buffer identity"))?;
        if !matches!(
            self.kernel.branches[index].element,
            crate::model::element::Element::Capacitor { .. }
        ) {
            return Err(rejected("External buffer must be a capacitor"));
        }
        let remaining = self.accepted().operating_point.branches[index].stored_joules + delta;
        if remaining < 0.0 || remaining > maximum || !remaining.is_finite() {
            return Ok(false);
        }
        if commit {
            let candidate = self.kernel.exchange_capacitor_energy(id, delta, maximum)?;
            self.kernel = candidate;
            self.revision = revision;
            self.cached = None;
            self.localization = None;
            self.trial.pending = None;
            self.parameter_cached = None;
            self.topology_cached = None;
            self.subscription_cached = None;
            self.event_cached = None;
            self.external_cached = Some(request);
        }
        Ok(true)
    }

    /// Reads only the most recently accepted request. Restored cursors never replay effects.
    pub fn observations(
        &self,
        revision: Revision,
    ) -> Result<em_protocol::contract::Observations, Diagnostic> {
        let Some((_, accepted)) = &self.cached else {
            return Err(rejected(
                "No publishable accepted request; advance after creation, edit or restore",
            ));
        };
        if revision != self.revision {
            return Err(rejected("Observation revision conflict"));
        }
        let state = self.accepted();
        let mut readings = if accepted.end == accepted.start {
            Vec::new()
        } else {
            if state.measurements.window_start != accepted.start {
                return Err(rejected(
                    "Observation window does not match accepted request",
                ));
            }
            state
                .measurements
                .readings(accepted.end, &state.operating_point.branches)?
        };
        for ((reading, branch), moments) in readings
            .iter_mut()
            .zip(self.kernel.branches.iter())
            .zip(&state.measurements.branches)
        {
            if matches!(
                branch.element,
                crate::model::element::Element::FieldShaft { .. }
                    | crate::model::element::Element::FieldAcShaft { .. }
            ) {
                reading.torque_newton_metres =
                    Some(moments.torque_seconds / (accepted.end - accepted.start));
            }
            if let crate::model::element::Element::AcShaft {
                omega, pole_pairs, ..
            } = branch.element
            {
                reading.torque_newton_metres =
                    Some(moments.torque_seconds / (accepted.end - accepted.start));
                if omega != 0.0 {
                    reading.frequency = Some((omega * pole_pairs / std::f64::consts::TAU).abs());
                }
            }
            if let crate::model::element::Element::ShaftBoundary {
                newton_metres_per_ampere: k,
                ..
            } = branch.element
            {
                let torque = k * reading.mean_current;
                if !torque.is_finite() {
                    return Err(rejected("Non-finite observed shaft torque"));
                }
                reading.torque_newton_metres = Some(torque);
            }
            if let Some((model, row)) = branch.element.thermal_node() {
                let point = state
                    .operating_point
                    .branches
                    .iter()
                    .find(|p| p.id == branch.id)
                    .unwrap();
                reading.temperature_kelvin = Some(model.ambient_kelvin + point.local_values[row]);
            }
            if let crate::model::element::Element::SineVoltageSource { peak, hertz, .. } =
                branch.element
                && peak > 0.0
                && hertz > 0.0
            {
                let cycles = hertz * (accepted.end - accepted.start);
                if cycles.is_finite() {
                    reading.frequency = Some(hertz);
                    reading.phase = Some(
                        (state.source_phases[&branch.id] - std::f64::consts::TAU * cycles.fract())
                            .rem_euclid(std::f64::consts::TAU),
                    );
                }
            }
        }
        Ok(em_protocol::contract::Observations {
            accepted: accepted.clone(),
            readings,
            events: self.pending_events.clone(),
            waveforms: if accepted.end == accepted.start {
                Vec::new()
            } else {
                state.sampler.waveforms()
            },
        })
    }

    pub fn subscribe_waveforms(
        &mut self,
        subscription: em_protocol::contract::WaveformSubscription,
    ) -> Result<(), Diagnostic> {
        if !self.pending_events.is_empty() {
            return Err(rejected("Protection event awaits world acknowledgement"));
        }
        if self.subscription_cached.as_ref() == Some(&subscription) {
            return Ok(());
        }
        let r = subscription.revision;
        if r.session != self.revision.session
            || r.topology != self.revision.topology
            || r.parameters != self.revision.parameters
            || r.sequence <= self.revision.sequence
        {
            return Err(rejected("Waveform subscription revision conflict"));
        }
        self.kernel.validate_probes(&subscription.channels)?;
        let candidate = crate::observation::waveform::Sampler::new(
            &subscription,
            self.accepted().time_seconds,
            &self.accepted().operating_point,
        )?;
        for old in &self.accepted().sampler.channels {
            if subscription
                .channels
                .iter()
                .any(|channel| channel.channel == old.channel.channel)
            {
                return Err(rejected(
                    "Rebinding a waveform subscription requires fresh channel identities",
                ));
            }
        }
        self.kernel.accepted.sampler = candidate;
        self.kernel.harmonic = Default::default();
        self.event_cached = None;
        self.revision = r;
        self.cached = None;
        self.localization = None;
        self.trial.pending = None;
        self.parameter_cached = None;
        self.topology_cached = None;
        self.subscription_cached = Some(subscription);
        Ok(())
    }

    /// Atomic accepted-boundary parameter transaction, sharing the advance sequence stream.
    pub fn set_parameters(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        changes: Vec<(
            em_protocol::contract::Id,
            crate::model::element::Element,
        )>,
    ) -> Result<(), Diagnostic> {
        if !self.pending_events.is_empty() {
            return Err(rejected("Protection event awaits world acknowledgement"));
        }
        if let Some((cached_revision, cached_time, cached_changes)) = &self.parameter_cached
            && *cached_revision == revision
            && *cached_time == accepted_time
            && *cached_changes == changes
        {
            return Ok(());
        }
        if revision.session != self.revision.session
            || revision.topology != self.revision.topology
            || Some(revision.parameters) != self.revision.parameters.checked_add(1)
            || revision.sequence <= self.revision.sequence
            || accepted_time != self.accepted().time_seconds
        {
            return Err(rejected(
                "Parameter transaction revision, sequence or time conflict",
            ));
        }
        let candidate = self.kernel.with_parameters(&changes)?;
        self.kernel = candidate;
        self.event_cached = None;
        self.revision = revision;
        self.cached = None;
        self.localization = None;
        self.trial.pending = None;
        self.suggested_step = self.max_step_seconds;
        self.parameter_cached = Some((revision, accepted_time, changes));
        self.topology_cached = None;
        self.subscription_cached = None;
        Ok(())
    }

    /// Replace topology atomically at the accepted boundary with identity-based migration.
    pub fn apply_topology(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        branches: Vec<crate::solver::dc::DcBranch>,
    ) -> Result<(), Diagnostic> {
        self.apply_topology_with_disposition(revision, accepted_time, branches, false)
    }

    /// Export removed storage from the modeled system; it is never counted as heat.
    pub fn apply_topology_with_disposition(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        branches: Vec<crate::solver::dc::DcBranch>,
        export_removed_energy: bool,
    ) -> Result<(), Diagnostic> {
        self.apply_boundary_edit(
            revision,
            accepted_time,
            branches,
            export_removed_energy,
            false,
        )
    }
    /// Atomic combined topology and SI parameter edit at one accepted boundary.
    pub fn apply_boundary(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        branches: Vec<crate::solver::dc::DcBranch>,
        export_removed_energy: bool,
    ) -> Result<(), Diagnostic> {
        self.apply_boundary_edit(
            revision,
            accepted_time,
            branches,
            export_removed_energy,
            true,
        )
    }
    fn apply_boundary_edit(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        branches: Vec<crate::solver::dc::DcBranch>,
        export_removed_energy: bool,
        parameter_edit: bool,
    ) -> Result<(), Diagnostic> {
        if !self.pending_events.is_empty() {
            return Err(rejected("Protection event awaits world acknowledgement"));
        }
        if let Some((
            cached_revision,
            cached_time,
            cached_branches,
            cached_export,
            cached_parameters,
        )) = &self.topology_cached
            && *cached_revision == revision
            && *cached_time == accepted_time
            && *cached_branches == branches
            && *cached_export == export_removed_energy
            && *cached_parameters == parameter_edit
        {
            return Ok(());
        }
        if revision.session != self.revision.session
            || Some(revision.topology) != self.revision.topology.checked_add(1)
            || Some(revision.parameters)
                != self
                    .revision
                    .parameters
                    .checked_add(u64::from(parameter_edit))
            || revision.sequence <= self.revision.sequence
            || accepted_time != self.accepted().time_seconds
        {
            return Err(rejected(
                "Topology transaction revision, sequence or time conflict",
            ));
        }
        // Retained model parameters must be edited with the parameter transaction.
        if !parameter_edit
            && branches.iter().any(|b| {
                self.kernel
                    .branches
                    .iter()
                    .any(|old| old.id == b.id && old.element != b.element)
            })
        {
            return Err(rejected(
                "Topology transaction cannot edit retained parameters",
            ));
        }
        let candidate = self.kernel.rebuild_boundary(
            branches.clone(),
            parameter_edit,
            export_removed_energy,
        )?;
        self.kernel = candidate;
        self.event_cached = None;
        self.revision = revision;
        self.cached = None;
        self.localization = None;
        self.trial.pending = None;
        self.parameter_cached = None;
        self.suggested_step = self.max_step_seconds;
        self.topology_cached = Some((
            revision,
            accepted_time,
            branches,
            export_removed_energy,
            parameter_edit,
        ));
        self.subscription_cached = None;
        Ok(())
    }

    /// Read-only DC-biased frequency response; never replaces the transient state.
    pub fn analyze_ac(
        &self,
        hertz: f64,
        excitations: &[(em_protocol::contract::Id, crate::solver::ac::Phasor)],
    ) -> Result<crate::solver::ac::FrequencyResponse, Diagnostic> {
        crate::solver::ac::solve(&self.kernel.branches, hertz, excitations)
    }

    /// Latest request is cached; older sequences explicitly expire, never replay.
    /// Budgets suspend a candidate; cancellation discards it. Both return the accepted prefix.
    pub fn advance(
        &mut self,
        request: Advance,
        cancelled: &AtomicBool,
    ) -> Result<Accepted, Diagnostic> {
        self.advance_request(request, cancelled, false, false)
    }

    /// Resolve fast electrical transients inside a world-tick candidate, including
    /// the suffix after an acknowledged protection event changes the circuit.
    pub fn advance_tick(&mut self, request: Advance, cancelled: &AtomicBool) -> Result<Accepted, Diagnostic> {
        self.advance_request(request, cancelled, true, false)
    }

    fn advance_request(&mut self, request: Advance, cancelled: &AtomicBool, world_tick: bool, preserve_window: bool) -> Result<Accepted, Diagnostic> {
        let mut trial = std::mem::take(&mut self.trial);
        let continuation = trial.pending.as_ref().map(|(anchor, end, _, _)| (*anchor, *end));
        let result = self.advance_with_trial(request, cancelled, Some(TrialResume { interval: continuation, preserve_window }), world_tick, |kernel, seed, end, accuracy, purpose, started, budget| {
            trial.poll(kernel, seed, end, accuracy, purpose, world_tick, started, budget, cancelled)
        });
        if cancelled.load(Ordering::Acquire) {
            trial.pending = None;
            self.localization = None;
        }
        self.trial = trial;
        result
    }

    pub fn tick_candidate(&self, start: f64) -> Result<Self, Diagnostic> {
        if !self.pending_events.is_empty() { return Err(rejected("World effect acknowledgement pending")); }
        let mut kernel = self.kernel.clone();
        let old = kernel.accepted.time_seconds;
        // A skipped interval freezes storage. Advance prescribed source clocks and
        // restart observation windows without integrating any power over the gap.
        if (old-start).abs() > 1e-9 {
            kernel.harmonic = Default::default();
            let mut seen = std::collections::HashSet::new();
            for branch in kernel.branches.iter() {
                if let Some((hz, _)) = branch.element.source_clock()
                    && seen.insert(branch.clock_id())
                    && let Some(phase) = kernel.accepted.source_phases.get_mut(&branch.clock_id()) {
                    *phase=(*phase+std::f64::consts::TAU*((start-old)*hz).fract()).rem_euclid(std::f64::consts::TAU);
                }
            }
            for moments in &mut kernel.accepted.measurements.branches { moments.periodicity=Default::default(); }
            kernel.accepted.sampler.origin=start;
            kernel.accepted.sampler.next_index=1;
            kernel.accepted.sampler.reset_output();
        }
        kernel.accepted.time_seconds=start;
        kernel.accepted.measurements.reset_window(start);
        let mut candidate=Self::new(self.revision,kernel,self.max_step_seconds)?;
        candidate.next_event=self.next_event;
        candidate.accuracy=self.accuracy;
        candidate.suggested_step=self.suggested_step;
        Ok(candidate)
    }

    fn advance_with_trial(
        &mut self,
        request: Advance,
        cancelled: &AtomicBool,
        continuation: Option<TrialResume>,
        world_tick: bool,
        mut trial_step: impl FnMut(&TransientCircuit, Option<&AcceptedState>, f64, Option<Accuracy>, TrialPurpose, Instant, Option<Duration>)
            -> Poll<Result<Trial, SolveError>>,
    ) -> Result<Accepted, Diagnostic> {
        request.validate()?;
        if request.revision.session != self.revision.session
            || request.revision.topology != self.revision.topology
            || request.revision.parameters != self.revision.parameters
        {
            return Err(rejected("Session/topology/parameter revision conflict"));
        }
        if let Some((previous, result)) = &self.cached
            && request.revision.sequence == previous.revision.sequence
        {
            if request.accepted_start == previous.accepted_start
                && request.requested_end == previous.requested_end
                && request.max_steps == previous.max_steps
                && request.budget_nanos == previous.budget_nanos
            {
                return Ok(result.clone());
            }
            return Err(rejected("Sequence reused with different request contents"));
        }
        if request.revision.sequence <= self.revision.sequence {
            return Err(rejected("Expired or out-of-order request sequence"));
        }
        if request.accepted_start != self.accepted().time_seconds {
            return Err(rejected(
                "Requested start differs from accepted physical time",
            ));
        }
        if self.interval.as_ref().is_some_and(|input| request.requested_end > input.end) {
            return Err(rejected("Advance exceeds supplied input interval"));
        }
        if self.pending_events.is_empty() {
            let triggered = protection::triggered(&self.kernel, self.accepted());
            if !triggered.is_empty() {
                self.register_events(&triggered)?;
            }
        }
        let _trace = crate::solver::progress::begin(request);
        if let Some(plans) = self.kernel.plans.get() { plans.report(self.kernel.layout.size); }
        let start = Instant::now();
        let unlimited = request.is_unlimited();
        let budget = (!unlimited).then(|| Duration::from_nanos(request.budget_nanos));
        let out_of_time = || budget.is_some_and(|budget| start.elapsed() >= budget);
        let out_of_steps = |steps| !unlimited && steps >= request.max_steps;
        let TrialResume { interval: mut continuation, preserve_window } = continuation.unwrap_or_default();
        let mut advancing = if preserve_window { self.kernel.clone() } else { self.kernel.trial_seed(None) };
        let mut steps = 0_u32;
        let mut work = em_protocol::contract::AdvanceWork::default();
        let mut diagnostic = Diagnostic {
            status: Status::Ready,
            reason: "Requested interval accepted".into(),
        };
        let mut allow_exact_interval = true;
        'advance: while advancing.accepted().time_seconds < request.requested_end {
            if !self.pending_events.is_empty() {
                diagnostic = Diagnostic {
                    status: Status::Partial,
                    reason: "Protection event awaits world acknowledgement".into(),
                };
                break;
            }
            let reason = if cancelled.load(Ordering::Acquire) {
                Some("Cancelled")
            } else if out_of_steps(steps) {
                Some("Step budget exhausted")
            } else if out_of_time() {
                Some("Cooperative time budget exhausted")
            } else {
                None
            };
            if let Some(reason) = reason {
                diagnostic = Diagnostic {
                    status: Status::Partial,
                    reason: reason.into(),
                };
                break;
            }
            let exact_interval = allow_exact_interval && self.accuracy.is_some()
                && advancing.accepted().sampler.channels.is_empty()
                && (advancing.constant_algebraic() || advancing.exact_rc_plan());
            let h = if exact_interval {
                f64::INFINITY
            } else if allow_exact_interval && self.accuracy.is_some_and(Accuracy::gameplay)
                && advancing.accepted().sampler.channels.is_empty() && advancing.harmonic_ready()
            {
                self.max_step_seconds.min(0.05)
            } else {
                self.suggested_step
                    .min(self.max_step_seconds)
                    .min(advancing.source_step_limit())
                    // Adaptive trials compare all four filter states. Fixed-step
                    // sessions additionally resolve the input between output samples.
                    .min(if self.accuracy.is_some() { f64::INFINITY }
                         else { advancing.accepted().sampler.step_limit() })
            };
            advancing.accepted.sampler.align_boundary(request.requested_end);
            let boundary = request.requested_end.min(advancing.accepted().sampler.next_time());
            let anchor = advancing.accepted().time_seconds;
            let mut end = (anchor + h).min(boundary);
            if end == anchor && h > 0.0 && boundary > anchor && (world_tick || advancing.game_interval)
                && self.accuracy.is_some_and(Accuracy::gameplay)
                && anchor.next_up() - anchor <= self.max_step_seconds
            {
                end = anchor.next_up();
            }
            // Do not leave a rounding-sized tail that cannot be bisected by
            // the error estimator. Split the remaining interval earlier,
            // keeping both trials within the input and sampling boundaries.
            if end < boundary && boundary - end <= 4.0 * f64::EPSILON * boundary.abs() {
                let middle = anchor + (boundary - anchor) * 0.5;
                if middle > anchor && middle < end { end = middle; }
            }
            if let Some((trial_anchor, trial_end)) = continuation.take()
                && trial_anchor == anchor && trial_end <= boundary
            {
                end = trial_end;
            }
            let saved = self.localization.take().filter(|bracket|
                bracket.anchor == anchor && bracket.high.time_seconds <= request.requested_end);
            let (candidate, bracket) = if let Some(saved) = saved {
                // This attempt represents an earlier upper candidate whose
                // localization resumes without repeating its numerical solve.
                work.attempted_steps = work.attempted_steps.saturating_add(1);
                (None, Some(saved))
            } else {
                if !end.is_finite() || end <= anchor {
                    diagnostic = Diagnostic { status: Status::Rejected,
                        reason: "Step end cannot advance at floating-point resolution".into() };
                    break;
                }
                let limit = if end == boundary {
                    if boundary == advancing.accepted().sampler.next_time() { 2 } else { 4 }
                } else if h == self.max_step_seconds { 1 }
                else if h == self.suggested_step { 0 } else { 3 };
                crate::solver::progress::step_limit(limit);
                crate::solver::progress::trial(anchor, end, work);
                let trial = trial_step(&advancing, None, end, self.accuracy, TrialPurpose::Advance, start, budget);
                steps = steps.saturating_add(1);
                work.attempted_steps = work.attempted_steps.saturating_add(1);
                let trial = match trial {
                    Poll::Pending => {
                        diagnostic = cooperative_pause(cancelled, "Cooperative time budget exhausted");
                        break;
                    }
                    Poll::Ready(Ok(trial)) => trial,
                    Poll::Ready(Err(error)) => {
                        let retryable = error.retryable();
                        let reason = error.diagnostic().reason.clone();
                        crate::solver::progress::failure(&reason);
                        work.rejected_steps = work.rejected_steps.saturating_add(1);
                        if let Some(accuracy) = self.accuracy
                            && retryable
                            && let Some(next) = reduced_step(anchor, end, (end - anchor) * 0.5, accuracy.minimum_step)
                        {
                            allow_exact_interval = false;
                            self.suggested_step = next;
                            continue;
                        }
                        diagnostic = Diagnostic { status: Status::Rejected, reason };
                        break;
                    }
                };
                if cancelled.load(Ordering::Acquire) {
                    diagnostic = cooperative_pause(cancelled, "Cooperative time budget exhausted");
                    break;
                }
                if let Some(accuracy) = self.accuracy {
                    let actual_step = end - anchor;
                    let factor = if trial.error == 0.0 {
                        2.0
                    } else {
                        (0.9 / trial.error.sqrt()).clamp(0.2, 2.0)
                    };
                    let next = (actual_step * factor)
                        .max(accuracy.minimum_step)
                        .min(self.max_step_seconds);
                    if trial.error > 1.0 {
                        crate::solver::progress::failure("Adaptive error exceeds tolerance");
                        work.rejected_steps = work.rejected_steps.saturating_add(1);
                        let Some(next) = reduced_step(anchor, end, next, accuracy.minimum_step) else {
                            diagnostic = Diagnostic {
                                status: Status::Rejected,
                                reason: "Accuracy cannot be met at minimum step or floating-point time resolution".into(),
                            };
                            break;
                        };
                        self.suggested_step = next;
                        continue;
                    }
                    self.suggested_step = next;
                }
                match ProtectionBracket::first(&advancing, None, trial) {
                    Ok(Ok(bracket)) => (None, Some(bracket)),
                    Ok(Err(candidate)) => (Some(candidate), None),
                    Err(error) => {
                        diagnostic = error;
                        work.rejected_steps = work.rejected_steps.saturating_add(1);
                        break;
                    }
                }
            };
            if let Some(mut crossing) = bracket {
                let upper = crossing.high.time_seconds;
                loop {
                    let high = crossing.high.time_seconds;
                    let tolerance = 1e-10_f64.max(high.abs() * 1e-12);
                    let committing = high - crossing.low <= tolerance;
                    if out_of_steps(steps) || cancelled.load(Ordering::Acquire) || out_of_time() {
                        self.localization = Some(crossing);
                        diagnostic = cooperative_pause(cancelled, "Protection localization suspended at cooperative boundary");
                        break 'advance;
                    }
                    let midpoint = crossing.low + (high - crossing.low) * 0.5;
                    let seed = crossing.seed.as_ref().unwrap_or(advancing.accepted());
                    static BISECTION: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
                    let reference = *BISECTION.get_or_init(|| std::env::var("EM_PROTECTION_SEARCH").as_deref() == Ok("bisection"));
                    let probe = !reference && !committing && self.accuracy.is_some() && !crossing.bisect && crossing.validation_time.is_none();
                    let interior = |t: f64| {
                        if high - crossing.low <= 4.0 * tolerance { midpoint }
                        else { t.clamp(crossing.low + tolerance, high - tolerance) }
                    };
                    let middle = if committing { high } else if probe {
                        interior(crossing.estimate(&advancing, seed, &crossing.high).unwrap_or(midpoint))
                    } else { crossing.validation_time.unwrap_or(midpoint) };
                    if !committing && (middle <= crossing.low || middle >= high) {
                        diagnostic = Diagnostic { status: Status::Rejected,
                            reason: "Protection bracket cannot be bisected at floating-point resolution".into() };
                        break 'advance;
                    }
                    crate::solver::progress::trial(anchor, middle, work);
                    let timing = crate::solver::progress::localization_start();
                    let purpose = if probe { TrialPurpose::ProtectionProbe } else { TrialPurpose::ProtectionValidation };
                    let trial = trial_step(&advancing, crossing.seed.as_ref(), middle, self.accuracy, purpose, start, budget);
                    crate::solver::progress::localization_end(timing, probe, committing, trial.is_ready(),
                        matches!(&trial, Poll::Ready(Err(_))) || matches!(&trial, Poll::Ready(Ok(t)) if t.error > 1.0));
                    steps = steps.saturating_add(1);
                    let trial = match trial {
                        Poll::Pending => {
                            self.localization = Some(crossing);
                            diagnostic = cooperative_pause(cancelled, "Protection localization suspended at cooperative boundary");
                            break 'advance;
                        }
                        Poll::Ready(trial) => {
                            work.localization_trials = work.localization_trials.saturating_add(1);
                            trial
                        }
                    };
                    if probe {
                        if let Ok(approximation) = &trial && approximation.error <= 1.0 {
                            let point = &approximation.end;
                            let prediction = if protection::triggered(&advancing, point).is_empty() {
                                crossing.estimate(&advancing, point, &crossing.high)
                            } else {
                                crossing.estimate(&advancing, seed, point)
                            };
                            crossing.validation_time = Some(interior(prediction.unwrap_or(middle)));
                            continue;
                        }
                        if trial.as_ref().is_ok_and(|trial| trial.error > 1.0)
                            || trial.as_ref().is_err_and(|error| error.retryable()) {
                            // An inconclusive estimate leaves both verified endpoints intact.
                            crossing.bisect = true;
                            continue;
                        }
                    }
                    let failure = match &trial {
                        Err(error) => Some((error.diagnostic().reason.clone(), error.retryable())),
                        Ok(trial) if !trial.error.is_finite() || trial.error > 1.0 => Some(("Protection localization accuracy failed".into(), true)),
                        _ => None,
                    };
                    if let Some((reason, retryable)) = failure {
                        crate::solver::progress::failure(&reason);
                        work.rejected_steps = work.rejected_steps.saturating_add(1);
                        if retryable && let Some(accuracy) = self.accuracy
                            && let Some(next) = reduced_step(anchor, upper, (upper - anchor) * 0.5, accuracy.minimum_step)
                        {
                            self.suggested_step = next;
                            allow_exact_interval = false;
                            continue 'advance;
                        }
                        diagnostic = Diagnostic { status: Status::Rejected, reason };
                        break 'advance;
                    }
                    let old_width = high - crossing.low;
                    crossing.validation_time = None;
                    let trial = trial.expect("successful localization trial");
                    match ProtectionBracket::first(&advancing, crossing.seed.as_ref(), trial) {
                        Ok(Ok(mut localized)) => {
                            if crossing.low < localized.high.time_seconds {
                                localized.low = localized.low.max(crossing.low);
                            }
                            let verified = committing && localized.high.time_seconds == high;
                            crossing = localized;
                            if verified { break; }
                        }
                        Ok(Err(_)) if committing => {
                            // The refined trajectory disagreed with the stored upper
                            // sample. Reintegrate a shorter window from accepted state.
                            work.rejected_steps = work.rejected_steps.saturating_add(1);
                            let minimum = self.accuracy.map_or(0.0, |a| a.minimum_step);
                            if let Some(next) = reduced_step(anchor, upper, (upper - anchor) * 0.5, minimum) {
                                self.suggested_step = next;
                                allow_exact_interval = false;
                                continue 'advance;
                            }
                            diagnostic = Diagnostic { status: Status::Rejected,
                                reason: "Cannot verify protection crossing at minimum step".into() };
                            break 'advance;
                        }
                        Ok(Err(_)) => crossing.low = middle,
                        Err(error) => {
                            diagnostic = error;
                            work.rejected_steps = work.rejected_steps.saturating_add(1);
                            break 'advance;
                        }
                    }
                    crossing.bisect = crossing.high.time_seconds - crossing.low > old_width * 0.75;
                }
                if cancelled.load(Ordering::Acquire) {
                    diagnostic = cooperative_pause(cancelled, "Protection localization suspended");
                    break;
                }
                let ProtectionBracket { mut high, triggers, .. } = crossing;
                if self.next_event.checked_add(triggers.len() as u64).is_none() {
                    diagnostic = Diagnostic {
                        status: Status::Rejected,
                        reason: "Protection event identity exhausted".into(),
                    };
                    break;
                }
                if let Err(error) = high.measurements.prepend_window(&advancing.accepted().measurements) {
                    diagnostic = error;
                    work.rejected_steps = work.rejected_steps.saturating_add(1);
                    break;
                }
                work.accepted_steps = work.accepted_steps.saturating_add(1);
                advancing.commit_owned_candidate(high);
                self.kernel = advancing.clone();
                self.register_events(&triggers)?;
                diagnostic = Diagnostic {
                    status: Status::Partial,
                    reason: "Protection threshold reached; world acknowledgement required".into(),
                };
                break;
            }
            let mut candidate = candidate.expect("uninterrupted candidate");
            if let Err(error) = candidate.measurements.prepend_window(&advancing.accepted().measurements) {
                diagnostic = error;
                work.rejected_steps = work.rejected_steps.saturating_add(1);
                break;
            }
            work.accepted_steps = work.accepted_steps.saturating_add(1);
            advancing.commit_owned_candidate(candidate);
        }
        if advancing.accepted().time_seconds > request.accepted_start {
            self.kernel = advancing;
        }
        if unlimited {
            // Keep telemetry representable by Java's signed counters even
            // when more steps fit in the physical interval.
            let limit = i32::MAX as u32;
            work.attempted_steps = work.attempted_steps.min(limit);
            work.accepted_steps = work.accepted_steps.min(work.attempted_steps);
            work.rejected_steps = work.rejected_steps.min(work.attempted_steps - work.accepted_steps);
            work.localization_trials = work.localization_trials.min(limit);
        }
        work.discarded_steps = work.attempted_steps.saturating_sub(work.accepted_steps).saturating_sub(work.rejected_steps);
        crate::solver::progress::complete(self.accepted().time_seconds, work, &diagnostic);
        let result = Accepted {
            revision: request.revision,
            start: request.accepted_start,
            end: self.accepted().time_seconds,
            requested_end: request.requested_end,
            diagnostic,
            work,
        };
        self.event_cached = None;
        self.revision = request.revision;
        self.parameter_cached = None;
        self.topology_cached = None;
        self.subscription_cached = None;
        self.cached = Some((request, result.clone()));
        Ok(result)
    }
}

/// Compare representable endpoints, not just step sizes: at t=1, adding
/// 1e-12 produces an actual step slightly greater than 1e-12. Retrying that
/// same minimum step must terminate instead of solving the same candidate.
fn reduced_step(start: f64, end: f64, proposed: f64, minimum: f64) -> Option<f64> {
    let next = proposed.max(minimum);
    let next_end = start + next;
    if next_end > start && next_end < end {
        return Some(next);
    }
    // A smaller duration can round back to the rejected endpoint. Try the
    // preceding representable time if it still satisfies the configured floor.
    let previous = end.next_down();
    (next.is_finite() && next_end == end && previous > start
        && previous >= start + minimum).then_some((previous - start).max(minimum))
}
