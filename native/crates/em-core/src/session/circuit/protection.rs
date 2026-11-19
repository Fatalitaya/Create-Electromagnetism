use super::*;
use crate::model::element::Element;
use em_protocol::contract::{Id, ProtectionEvent};

pub(super) fn triggered(kernel: &TransientCircuit, state: &AcceptedState) -> Vec<usize> {
    let mut indices: Vec<_> = state.operating_point.branches.iter()
        .enumerate()
        .filter_map(|(i, p)| kernel.protection_triggered(i, p).then_some(i))
        .collect();
    indices.sort_by_key(|&i| kernel.branches[i].id.0);
    indices
}

impl CircuitSession {
    pub fn pending_events(&self) -> &[ProtectionEvent] {
        &self.pending_events
    }

    pub(super) fn register_events(&mut self, indices: &[usize]) -> Result<(), Diagnostic> {
        let next = self
            .next_event
            .checked_add(indices.len() as u64)
            .ok_or_else(|| rejected("Protection event identity exhausted"))?;
        let mut events = Vec::with_capacity(indices.len());
        for (offset, &i) in indices.iter().enumerate() {
            let branch = &self.kernel.branches[i];
            let state = &self.kernel.accepted.operating_point.branches[i];
            let (action, temperature) =
                if let Some(active) = branch.element.relay_transition(state.voltage) {
                    (if active { 2 } else { 1 }, 0.0)
                } else if let Some((model, row)) = branch.element.thermal_node() {
                    (0, model.ambient_kelvin + state.local_values[row])
                } else {
                    return Err(rejected("Invalid native event target"));
                };
            events.push(ProtectionEvent {
                action,
                id: Id([
                    self.revision.session.0[0] ^ self.revision.session.0[1],
                    self.next_event + offset as u64,
                ]),
                branch: branch.id,
                time: self.accepted().time_seconds,
                temperature_kelvin: temperature,
                stored_joules: state.stored_joules,
            });
        }
        self.next_event = next;
        self.pending_events = events;
        Ok(())
    }

    /// World has applied every listed destruction. Atomically remove those thermal
    /// bodies and allocate their remaining heat to the destruction boundary.
    /// Failed algebraic reconstruction keeps all events and accepted state intact.
    pub fn acknowledge_events(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        ids: Vec<Id>,
    ) -> Result<(), Diagnostic> {
        self.acknowledge_destruction(revision, accepted_time, ids, Vec::new())
    }

    /// The world may remove a complete assembly at a protection boundary. Its
    /// remaining electrical and thermal storage is dissipated by destruction,
    /// independently of ordinary player-removal export accounting.
    pub fn acknowledge_destruction(
        &mut self,
        revision: Revision,
        accepted_time: f64,
        ids: Vec<Id>,
        destroyed: Vec<Id>,
    ) -> Result<(), Diagnostic> {
        if self.event_cached.as_ref()
            == Some(&(revision, accepted_time, ids.clone(), destroyed.clone()))
        {
            return Ok(());
        }
        if self.pending_events.is_empty()
            || ids != self.pending_events.iter().map(|e| e.id).collect::<Vec<_>>()
            || revision.session != self.revision.session
            || Some(revision.topology) != self.revision.topology.checked_add(1)
            || revision.parameters != self.revision.parameters
            || revision.sequence <= self.revision.sequence
            || accepted_time != self.accepted().time_seconds
        {
            return Err(rejected(
                "Protection acknowledgement identities, boundary or revision mismatch",
            ));
        }
        let mut removed = self
            .pending_events
            .iter()
            .filter(|e| e.action == 0)
            .map(|e| e.branch)
            .collect::<std::collections::HashSet<_>>();
        let mut explicit = std::collections::HashSet::new();
        for id in &destroyed {
            if !explicit.insert(*id) || !self.kernel.branches.iter().any(|b| b.id == *id) {
                return Err(rejected("Duplicate or unknown destroyed physical branch"));
            }
            removed.insert(*id);
        }
        let released = self
            .accepted()
            .operating_point
            .branches
            .iter()
            .filter(|b| removed.contains(&b.id))
            .map(|b| b.stored_joules)
            .sum::<f64>();
        let branches = self
            .kernel
            .branches
            .iter()
            .filter(|b| !removed.contains(&b.id))
            .cloned()
            .collect::<Vec<_>>();
        let layout = crate::topology::Layout::compile(&branches)?;
        let mut original = self.kernel.clone();
        for event in &self.pending_events {
            if event.action != 0 {
                let index = original
                    .branches
                    .iter()
                    .position(|b| b.id == event.branch)
                    .ok_or_else(|| rejected("Missing relay event target"))?;
                let Element::Relay { ref mut active, .. } = std::sync::Arc::make_mut(&mut original.branches)[index].element
                else {
                    return Err(rejected("Invalid relay event model"));
                };
                *active = event.action == 2;
            }
        }
        let branches = branches
            .into_iter()
            .map(|mut b| {
                if let Some(old) = original.branches.iter().find(|o| o.id == b.id) {
                    b.element = old.element.clone();
                }
                b
            })
            .collect::<Vec<_>>();
        // A destroyed endpoint cannot keep an active probe. Stop that channel at
        // the event boundary; never synthesize a post-destruction zero sample.
        original.accepted.sampler.channels.retain(|channel| {
            let a = layout.components.get(&channel.channel.positive);
            let b = layout.components.get(&channel.channel.negative);
            a.is_some() && a == b
        });
        let mut candidate = original.with_topology(branches, true)?;
        candidate.accepted.energy.removed_joules = self.accepted().energy.removed_joules;
        candidate.accepted.energy.dissipated_joules += released;
        if !candidate.accepted.energy.is_finite() {
            return Err(rejected("Non-finite protection energy boundary"));
        }
        self.kernel = candidate;
        self.revision = revision;
        self.pending_events.clear();
        self.cached = None; self.localization = None; self.trial.pending = None;
        self.parameter_cached = None;
        self.topology_cached = None;
        self.subscription_cached = None;
        self.event_cached = Some((revision, accepted_time, ids, destroyed));
        self.suggested_step = self.max_step_seconds;
        Ok(())
    }
}
