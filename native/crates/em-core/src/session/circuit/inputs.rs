//! Atomic prescribed boundary updates. No topology compilation or observation reset.
use super::*;
use crate::model::element::Element;
use em_protocol::contract::{IntervalInputs, IntervalValue};
impl CircuitSession {
    pub fn set_interval(&mut self, input: IntervalInputs) -> Result<(), Diagnostic> {
        if self.interval.as_ref() == Some(&input) && self.revision == input.revision { return Ok(()); }
        let r = input.revision;
        if r.session != self.revision.session || r.topology != self.revision.topology
            || r.parameters != self.revision.parameters || self.revision.sequence.checked_add(1) != Some(r.sequence)
            || input.start != self.accepted().time_seconds || !input.end.is_finite() || input.end <= input.start
            || input.inputs.len() > em_protocol::wire::MAX_BRANCHES || !self.pending_events.is_empty()
        { return Err(rejected("Interval input revision, time or resource conflict")); }
        // A new game tick may replace the unaccepted suffix of a failed tick.
        // The exact accepted-time and revision checks above prevent replaying
        // accepted physics; pending world effects must still be acknowledged.
        let replacing_unaccepted = self.interval.as_ref().is_some_and(|old| input.start < old.end);
        let mut seen = std::collections::HashSet::new();
        let mut updates = Vec::with_capacity(input.inputs.len());
        for b in &input.inputs {
            if !seen.insert(b.branch) { return Err(rejected("Duplicate interval target")); }
            let i = *self.kernel.layout.branch_indices.get(&b.branch).ok_or_else(|| rejected("Unknown interval target"))?;
            let branch = &self.kernel.branches[i];
            if branch.positive.device != b.owner { return Err(rejected("Interval target instance mismatch")); }
            let mut element = branch.element.clone();
            match (&mut element, &b.value) {
                (Element::Audio(audio) | Element::RadioTransmitter { audio, .. }, IntervalValue::Audio { start, samples }) => {
                    if !start.is_finite() || *start > input.start || *start < 0.0 || input.end > *start + 0.05 + 1e-12 {
                        return Err(rejected("Acoustic block does not cover interval"));
                    }
                    audio.start = *start; audio.samples = samples.clone();
                },
                (Element::ShaftBoundary { radians_per_second, .. }, IntervalValue::ShaftSpeed(v)) => *radians_per_second = *v,
                (Element::AcShaft { omega, .. } | Element::FieldShaft { omega, .. } | Element::FieldAcShaft { omega, .. }, IntervalValue::ShaftSpeed(v)) => *omega = *v,
                _ => return Err(rejected("Interval value incompatible with target model")),
            }
            element.validate()?;
            updates.push((i, element));
        }
        // Shared rotor clocks must receive a consistent speed in the same atomic batch.
        let mut clocks = std::collections::HashMap::new();
        for (i, b) in self.kernel.branches.iter().enumerate() {
            let e = updates.iter().find(|(j,_)| *j == i).map_or(&b.element, |(_,e)| e);
            if matches!(e, Element::AcShaft { .. } | Element::FieldAcShaft { .. }) {
                let frequency = e.source_clock().unwrap().0;
                if clocks.insert(b.clock_id(), frequency).is_some_and(|old| old != frequency) {
                    return Err(rejected("Conflicting prescribed rotor speeds"));
                }
            }
        }
        for (i, element) in updates { self.kernel.branches[i].element = element; }
        if replacing_unaccepted { self.suggested_step = self.max_step_seconds; }
        self.revision = r; self.interval = Some(input); self.cached = None; self.localization = None;
        Ok(())
    }
}
