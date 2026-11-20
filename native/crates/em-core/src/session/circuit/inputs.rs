//! Update prescribed inputs atomically while retaining compiled topology and observation history.
use super::*;
use crate::model::element::Element;
use em_protocol::contract::{IntervalInputs, IntervalValue};
impl CircuitSession {
    pub fn interval_inputs(&self) -> Option<&IntervalInputs> {
        self.interval.as_ref()
    }

    pub fn set_interval(&mut self, input: IntervalInputs) -> Result<(), Diagnostic> {
        if self.interval.as_ref() == Some(&input) && self.revision == input.revision { return Ok(()); }
        let r = input.revision;
        if r.session != self.revision.session || r.topology != self.revision.topology
            || r.parameters != self.revision.parameters || self.revision.sequence.checked_add(1) != Some(r.sequence)
            || input.start != self.accepted().time_seconds || !input.end.is_finite() || input.end <= input.start
            || input.inputs.len() > em_protocol::wire::MAX_BRANCHES || !self.pending_events.is_empty()
        { return Err(rejected("Interval input revision, time or resource conflict")); }
        // A new tick may replace the unaccepted suffix of a failed tick. Check accepted time,
        // revision and pending effects before accepting the input.
        let replacing_unaccepted = self.interval.as_ref().is_some_and(|old| input.start < old.end);
        let mut seen = std::collections::HashSet::new();
        let mut updates = Vec::with_capacity(input.inputs.len());
        let mut ac_shaft_interval = false;
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
            ac_shaft_interval |= matches!(element, Element::AcShaft { .. });
            if element != branch.element { updates.push((i, element)); }
        }
        // All windings sharing a rotor clock must receive the same speed in one batch.
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
        if !updates.is_empty() {
            self.kernel.plans = Default::default();
            self.kernel.harmonic = Default::default();
            let branches = std::sync::Arc::make_mut(&mut self.kernel.branches);
            for (i, element) in updates { branches[i].element = element; }
        }
        if replacing_unaccepted { self.suggested_step = self.max_step_seconds; }
        self.kernel.game_interval = ac_shaft_interval;
        self.revision = r; self.interval = Some(input); self.cached = None; self.localization = None; self.trial.pending = None;
        Ok(())
    }
}
