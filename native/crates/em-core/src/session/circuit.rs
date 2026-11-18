//! Ordered pure-Rust circuit session with atomic accepted-boundary circuit edits.
//! No JNI/world activation is implied by this standalone G2/G1 transaction baseline.
use crate::{
    model::element::rejected,
    solver::transient::{Accuracy, TransientCircuit},
    state::AcceptedState,
};
use em_protocol::contract::{Accepted, Advance, Diagnostic, Revision, Status};
use std::{
    sync::atomic::{AtomicBool, Ordering},
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
    high: f64,
}

pub struct CircuitSession {
    localization: Option<ProtectionBracket>,
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
            .zip(&self.kernel.branches)
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
        self.event_cached = None;
        self.revision = r;
        self.cached = None;
        self.localization = None;
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
    /// Cancellation/budgets discard only the candidate and return the accepted prefix.
    pub fn advance(
        &mut self,
        request: Advance,
        cancelled: &AtomicBool,
    ) -> Result<Accepted, Diagnostic> {
        self.advance_with_trial(request, cancelled, |kernel, end, accuracy, _| {
            match accuracy {
                Some(a) => kernel.refined_trial(end, a),
                None => kernel.trial(end).map(|candidate| (candidate, 0.0)),
            }
        })
    }

    // One trial boundary for normal advancement and event localization. Tests
    // can exercise solver/accuracy failures without relying on floating-point accidents.
    fn advance_with_trial(
        &mut self,
        request: Advance,
        cancelled: &AtomicBool,
        mut trial_step: impl FnMut(&TransientCircuit, f64, Option<Accuracy>, bool)
            -> Result<(AcceptedState, f64), Diagnostic>,
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
        if self.pending_events.is_empty() {
            let triggered = protection::triggered(&self.kernel, self.accepted());
            if !triggered.is_empty() {
                self.register_events(&triggered)?;
            }
        }
        if self.interval.as_ref().is_some_and(|input| request.requested_end > input.end) {
            return Err(rejected("Advance exceeds supplied input interval"));
        }
        let _trace = crate::solver::progress::begin(request);
        let start = Instant::now();
        let budget = Duration::from_nanos(request.budget_nanos);
        let mut advancing = self.kernel.clone();
        advancing
            .accepted
            .measurements
            .reset_window(request.accepted_start);
        advancing.accepted.sampler.reset_output();
        let mut steps = 0;
        let mut work = em_protocol::contract::AdvanceWork::default();
        let mut diagnostic = Diagnostic {
            status: Status::Ready,
            reason: "Requested interval accepted".into(),
        };
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
            } else if steps >= request.max_steps {
                Some("Step budget exhausted")
            } else if start.elapsed() >= budget {
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
            let h = self
                .suggested_step
                .min(self.max_step_seconds)
                .min(advancing.source_step_limit())
                .min(advancing.accepted().sampler.step_limit());
            let boundary = request.requested_end.min(advancing.accepted().sampler.next_time());
            let anchor = advancing.accepted().time_seconds;
            let mut end = (anchor + h).min(boundary);
            // Do not leave a rounding-sized tail that cannot be bisected by
            // the error estimator. Split the remaining interval earlier,
            // keeping both trials within the input and sampling boundaries.
            if end < boundary && boundary - end <= 4.0 * f64::EPSILON * boundary.abs() {
                let middle = anchor + (boundary - anchor) * 0.5;
                if middle > anchor && middle < end { end = middle; }
            }
            let saved = self.localization.take().filter(|bracket|
                bracket.anchor == advancing.accepted().time_seconds
                    && bracket.high <= request.requested_end);
            // Re-evaluate the retained upper bound from the current observation
            // window. Reusing its old candidate would replay measurement energy
            // and waveform samples from the preceding advance request.
            let end = saved.as_ref().map_or(end, |bracket| bracket.high);
            let anchor = advancing.accepted().time_seconds;
            if !end.is_finite() || end <= anchor {
                diagnostic = Diagnostic { status: Status::Partial,
                    reason: "Step end cannot advance at floating-point resolution".into() };
                break;
            }
            crate::solver::progress::trial(anchor, end, work);
            let trial = trial_step(&advancing, end, self.accuracy, false);
            steps += 1; // Includes rejects; adaptive attempts use at most seven bounded DAE solves.
            work.attempted_steps += 1;
            let (mut candidate, error) = match trial {
                Ok(candidate) => candidate,
                Err(error) => {
                    crate::solver::progress::failure(&error.reason);
                    work.rejected_steps += 1;
                    if let Some(accuracy) = self.accuracy
                        && crate::solver::retry_with_smaller_step(&error)
                        && let Some(next) = reduced_step(anchor, end,
                            (end - anchor) * 0.5, accuracy.minimum_step)
                    {
                        self.suggested_step = next;
                        continue;
                    }
                    diagnostic = Diagnostic {
                        status: Status::Partial,
                        reason: error.reason,
                    };
                    break;
                }
            };
            if cancelled.load(Ordering::Acquire) || start.elapsed() >= budget {
                self.localization = saved;
                diagnostic = Diagnostic {
                    status: Status::Partial,
                    reason: "Candidate discarded after cancellation/deadline".into(),
                };
                break;
            }
            if let Some(accuracy) = self.accuracy {
                let actual_step = end - advancing.accepted().time_seconds;
                let factor = if error == 0.0 {
                    2.0
                } else {
                    (0.9 / error.sqrt()).clamp(0.2, 2.0)
                };
                let next = (actual_step * factor)
                    .max(accuracy.minimum_step)
                    .min(self.max_step_seconds);
                if error > 1.0 {
                    crate::solver::progress::failure("Adaptive error exceeds tolerance");
                    work.rejected_steps += 1;
                    let Some(next) = reduced_step(anchor, end, next, accuracy.minimum_step) else {
                        diagnostic = Diagnostic {
                            status: Status::Partial,
                            reason: "Accuracy cannot be met at minimum step or floating-point time resolution".into(),
                        };
                        break;
                    };
                    self.suggested_step = next;
                    continue;
                }
                self.suggested_step = next;
            }
            let mut triggers = protection::triggered(&advancing, &candidate);
            if !triggers.is_empty() {
                let mut low = saved.as_ref().map_or(advancing.accepted().time_seconds, |b| b.low);
                let mut high = saved.as_ref().map_or(candidate.time_seconds, |b| b.high);
                for _ in 0..52 {
                    if high - low <= 1e-10_f64.max(high.abs() * 1e-12) {
                        break;
                    }
                    if steps >= request.max_steps
                        || cancelled.load(Ordering::Acquire)
                        || start.elapsed() >= budget
                    {
                        self.localization = Some(ProtectionBracket {
                            anchor: advancing.accepted().time_seconds, low, high,
                        });
                        diagnostic = Diagnostic {
                            status: Status::Partial,
                            reason: "Protection localization suspended at cooperative boundary".into(),
                        };
                        break 'advance;
                    }
                    let middle = low + (high - low) * 0.5;
                    if middle <= low || middle >= high {
                        diagnostic = Diagnostic { status: Status::Partial,
                            reason: "Protection bracket cannot be bisected at floating-point resolution".into() };
                        break 'advance;
                    }
                    crate::solver::progress::trial(anchor, middle, work);
                    let trial = trial_step(&advancing, middle, self.accuracy, true);
                    steps += 1;
                    work.localization_trials += 1;
                    let failure = match &trial {
                        Err(error) => Some((error.reason.clone(), crate::solver::retry_with_smaller_step(error))),
                        Ok((_, error)) if *error > 1.0 =>
                            Some(("Protection localization accuracy failed".into(), true)),
                        _ => None,
                    };
                    if let Some((reason, retryable)) = failure {
                        crate::solver::progress::failure(&reason);
                        work.rejected_steps += 1;
                        // Retry from accepted history; no event or candidate from
                        // this bracket has been committed. Rediscover the crossing.
                        let actual_step = end - advancing.accepted().time_seconds;
                        if retryable && let Some(accuracy) = self.accuracy
                            && let Some(next) = reduced_step(anchor, end, actual_step * 0.5, accuracy.minimum_step)
                        {
                            self.suggested_step = next;
                            continue 'advance;
                        }
                        diagnostic = Diagnostic { status: Status::Partial, reason };
                        break 'advance;
                    }
                    let (localized, _) = trial.expect("successful localization trial");
                    let crossed = protection::triggered(&advancing, &localized);
                    if crossed.is_empty() {
                        low = middle;
                    } else {
                        high = middle;
                        candidate = localized;
                        triggers = crossed;
                    }
                }
                if cancelled.load(Ordering::Acquire) || start.elapsed() >= budget {
                    self.localization = Some(ProtectionBracket {
                        anchor: advancing.accepted().time_seconds, low, high,
                    });
                    diagnostic = Diagnostic {
                        status: Status::Partial,
                        reason: "Protection localization suspended at cooperative boundary".into(),
                    };
                    break;
                }
                if self.next_event.checked_add(triggers.len() as u64).is_none() {
                    diagnostic = Diagnostic {
                        status: Status::Partial,
                        reason: "Protection event identity exhausted".into(),
                    };
                    break;
                }
                work.accepted_steps += 1;
                advancing.commit_owned_candidate(candidate);
                self.kernel = advancing.clone();
                self.register_events(&triggers)?;
                diagnostic = Diagnostic {
                    status: Status::Partial,
                    reason: "Protection threshold reached; world acknowledgement required".into(),
                };
                break;
            }
            work.accepted_steps += 1;
            advancing.commit_owned_candidate(candidate);
        }
        if advancing.accepted().time_seconds > request.accepted_start {
            self.kernel = advancing;
        }
        work.discarded_steps = work.attempted_steps - work.accepted_steps - work.rejected_steps;
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
    (next.is_finite() && next_end > start && next_end < end).then_some(next)
}
