//! Checkpoint one accepted boundary with the physical and transaction state needed for restoration.
use super::*;
use crate::{
    model::{DeviceModel, element::Element},
    observation::{BranchMoments, EnergyLedger, Measurements},
    solver::dc::{BranchOperatingPoint, DcBranch, DcOperatingPoint},
};
use em_protocol::{
    contract::{Id, PortId},
    wire::{MAX_BRANCHES, Reader, Writer},
};
use std::collections::{HashMap, HashSet};

const MAGIC: u32 = 0x50534d45; // EMSP identifies a circuit snapshot.

impl CircuitSession {
    pub fn revision(&self) -> Revision {
        self.revision
    }

    /// Save accepted values and the latest sequence watermark. Cached responses expire on restore.
    pub fn snapshot(&self) -> Vec<u8> {
        let mut w = Writer::default();
        w.u32(MAGIC);
        w.string(em_protocol::MOD_VERSION);
        w.revision(self.revision);
        w.f64(self.max_step_seconds);
        w.f64(self.suggested_step);
        w.u32(u32::from(self.accuracy.is_some()));
        if let Some(a) = self.accuracy {
            for value in [
                a.relative,
                a.voltage,
                a.current,
                a.charge,
                a.flux,
                a.energy,
                a.minimum_step,
            ] {
                w.f64(value);
            }
        }
        let state = self.accepted();
        w.f64(state.time_seconds);
        let e = state.energy;
        for value in [
            e.initial_stored_joules,
            e.stored_joules,
            e.source_supplied_joules,
            e.source_absorbed_joules,
            e.dissipated_joules,
            e.numerical_damping_joules,
            e.parameter_work_joules,
            e.removed_joules,
        ] {
            w.f64(value);
        }
        w.u32(self.kernel.branches.len() as u32);
        for (b, p) in self
            .kernel
            .branches
            .iter()
            .zip(&state.operating_point.branches)
        {
            w.id(b.id);
            w.port(b.positive);
            w.port(b.negative);
            let (tag, parameters) = b.element.parameters();
            w.u32(tag);
            for value in parameters {
                w.f64(value);
            }
            for port in &b.additional_ports {
                w.port(*port);
            }
            for value in &p.local_values {
                w.f64(*value);
            }
        }
        w.f64(state.measurements.window_start);
        for moments in &state.measurements.branches {
            w.f64(moments.torque_seconds);
            for value in moments.fields() {
                w.f64(value);
            }
            for value in moments.periodicity.fields() {
                w.f64(value);
            }
        }
        state.sampler.write_snapshot(&mut w);
        for branch in self.kernel.branches.iter() {
            if branch.element.source_clock().is_some() {
                w.f64(state.source_phases[&branch.clock_id()]);
            }
        }
        w.u64(self.next_event);
        w.u32(self.pending_events.len() as u32);
        for event in &self.pending_events {
            w.id(event.id);
            w.id(event.branch);
            w.f64(event.time);
            w.f64(event.temperature_kelvin);
            w.f64(event.stored_joules);
            w.u32(event.action);
        }
        w.u32(u32::from(self.interval.is_some()));
        if let Some(input) = &self.interval { em_protocol::wire::write_interval(&mut w, input); }
        w.0
    }

    /// Decode and validate the entire snapshot before publishing restored state.
    pub fn from_snapshot(bytes: &[u8]) -> Result<Self, Diagnostic> {
        decode(bytes).map_err(|s| rejected(&s))
    }

    /// Replay a saved adaptive session with tighter physical tolerances at the same boundary.
    pub fn replay_snapshot(bytes: &[u8], level: u32) -> Result<Self, Diagnostic> {
        let mut session = Self::from_snapshot(bytes)?;
        let accuracy = session.accuracy.as_mut().ok_or_else(|| rejected("Expected adaptive checkpoint"))?;
        let factor = 0.25_f64.powf(f64::from(level));
        for value in [&mut accuracy.relative, &mut accuracy.voltage, &mut accuracy.current,
            &mut accuracy.charge, &mut accuracy.flux, &mut accuracy.energy] {
            *value *= factor;
        }
        accuracy.validate()?;
        Ok(session)
    }

    /// Restore only to an empty session with the same identity, preventing rollback of live energy.
    pub fn restore(&mut self, bytes: &[u8]) -> Result<(), Diagnostic> {
        if self.revision.sequence != 0
            || self.accepted().time_seconds != 0.0
            || self.cached.is_some()
        {
            return Err(rejected("Restore requires a pristine session"));
        }
        let replacement = Self::from_snapshot(bytes)?;
        if replacement.revision.session != self.revision.session {
            return Err(rejected("Snapshot session identity mismatch"));
        }
        *self = replacement;
        Ok(())
    }
}

fn close(actual: f64, expected: f64, scale: f64) -> bool {
    close_with_tolerance(actual, expected, scale, 1e-10)
}

fn close_with_tolerance(actual: f64, expected: f64, scale: f64, absolute: f64) -> bool {
    actual.is_finite()
        && expected.is_finite()
        && scale.is_finite()
        && (actual - expected).abs() <= absolute + 1e-8 * scale
}

fn decode(bytes: &[u8]) -> Result<CircuitSession, String> {
    let mut r = Reader::new(bytes)?;
    if r.u32()? != MAGIC || r.string()? != em_protocol::MOD_VERSION {
        return Err("Snapshot format or mod metadata mismatch".into());
    }
    let revision = r.revision()?;
    let max_step_seconds = r.f64()?;
    let suggested_step = r.f64()?;
    let accuracy = match r.u32()? {
        0 => None,
        1 => Some(Accuracy {
            relative: r.f64()?,
            voltage: r.f64()?,
            current: r.f64()?,
            charge: r.f64()?,
            flux: r.f64()?,
            energy: r.f64()?,
            minimum_step: r.f64()?,
        }),
        _ => return Err("Invalid snapshot integrator mode".into()),
    };
    if suggested_step <= 0.0 || suggested_step > max_step_seconds {
        return Err("Invalid snapshot suggested step".into());
    }
    if let Some(a) = accuracy {
        a.validate().map_err(|d| d.reason)?;
        if a.minimum_step > suggested_step {
            return Err("Invalid snapshot minimum step".into());
        }
    }
    let time_seconds = r.f64()?;
    if max_step_seconds <= 0.0 || time_seconds < 0.0 {
        return Err("Invalid snapshot time/step/sequence".into());
    }
    let energy = EnergyLedger {
        initial_stored_joules: r.f64()?,
        stored_joules: r.f64()?,
        source_supplied_joules: r.f64()?,
        source_absorbed_joules: r.f64()?,
        dissipated_joules: r.f64()?,
        numerical_damping_joules: r.f64()?,
        parameter_work_joules: r.f64()?,
        removed_joules: r.f64()?,
    };
    let ledger = [
        energy.initial_stored_joules,
        energy.stored_joules,
        energy.source_supplied_joules,
        energy.source_absorbed_joules,
        energy.dissipated_joules,
        energy.numerical_damping_joules,
        energy.removed_joules,
    ];
    let scale: f64 = ledger.iter().sum::<f64>() + energy.parameter_work_joules.abs();
    if ledger.iter().any(|v| *v < 0.0)
        || !energy.is_finite()
        || !close(energy.balance_residual_joules(), 0.0, scale)
    {
        return Err("Invalid snapshot energy ledger".into());
    }
    if time_seconds == 0.0
        && (energy.dissipated_joules != 0.0 || energy.numerical_damping_joules != 0.0)
    {
        return Err("Zero-time snapshot contains committed interval energy".into());
    }
    let count = r.u32()? as usize;
    if count > MAX_BRANCHES {
        return Err("Snapshot branch limit exceeded".into());
    }
    let mut ids = HashSet::<Id>::new();
    let mut nodes = Vec::new();
    let mut voltages = HashMap::new();
    let mut adjacency: HashMap<PortId, Vec<PortId>> = HashMap::new();
    let mut kcl: HashMap<PortId, (f64, f64)> = HashMap::new();
    let mut branches = Vec::with_capacity(count);
    let mut points = Vec::with_capacity(count);
    let mut stored = 0.0;
    let mut power_balance_watts = 0.0;
    for _ in 0..count {
        let id = r.id()?;
        let positive = r.port()?;
        let negative = r.port()?;
        if !ids.insert(id) || positive == negative {
            return Err("Duplicate/collapsed snapshot branch".into());
        }
        let tag = r.u32()?;
        let parameter = r.f64()?;
        let mut parameters = vec![parameter];
        let extra = match tag {
            15 => 1,
            16 | 17 | 29 => 2,
            5 => 2,
            6 | 18 | 30 => 3,
            7..=10 => 5,
            19 => 6,
            20 => 38,
            21 => 8,
            22 => 3,
            23 => 6,
            24 => 803,
            25 => 2,
            26 => 805,
            27 => 1,
            28 => 3,
            11 | 12 => 4,
            13 => 5,
            _ => 0,
        };
        for _ in 0..extra {
            parameters.push(r.f64()?);
        }
        let element = Element::from_parameters(tag, &parameters).map_err(|d| d.reason)?;
        element.validate().map_err(|d| d.reason)?;
        let mut additional_ports = Vec::new();
        for _ in 2..element.port_count() {
            additional_ports.push(r.port()?);
        }
        let va = r.f64()?;
        let vb = r.f64()?;
        let current = r.f64()?;
        let mut local_values = vec![va, vb, current];
        let mut terminals = vec![(positive, va, current), (negative, vb, -current)];
        for &port in &additional_ports {
            let voltage = r.f64()?;
            let amperes = r.f64()?;
            local_values.extend([voltage, amperes]);
            terminals[1].2 -= amperes;
            terminals.push((port, voltage, amperes));
        }
        while local_values.len() < element.unknown_count() {
            local_values.push(r.f64()?);
        }
        for &(port, value, amperes) in &terminals {
            if let Some(previous) = voltages.insert(port, value) {
                if previous != value {
                    return Err("Conflicting node voltages".into());
                }
            } else {
                nodes.push(port);
            }
            let (sum, magnitude) = kcl.entry(port).or_default();
            *sum += amperes;
            *magnitude += amperes.abs();
        }
        let ports = std::iter::once(positive)
            .chain(std::iter::once(negative))
            .chain(additional_ports.iter().copied())
            .collect::<Vec<_>>();
        for (a, b) in element.electrical_edges() {
            adjacency.entry(ports[a]).or_default().push(ports[b]);
            adjacency.entry(ports[b]).or_default().push(ports[a]);
        }
        if let Some(state) = element.negative_internal_state(&local_values) {
            return Err(match state.unit {
                crate::model::UnknownUnit::Kelvin => "Snapshot thermal state is below its fixed ambient bath",
                _ => "Snapshot RMS detector squared voltage is negative",
            }.into());
        }
        let e = element
            .evaluate(&local_values, if tag == 6 { 0.0 } else { time_seconds })
            .map_err(|d| d.reason)?;
        let dimension = element.unknown_count();
        let terminal_values = 2 * element.port_count() - 1;
        for row in std::iter::once(2)
            .chain((4..terminal_values).step_by(2))
            .chain(terminal_values..dimension)
        {
            if element.source_clock().is_some()
                || e.charge_jacobian[row * dimension..(row + 1) * dimension]
                    .iter()
                    .any(|q| *q != 0.0)
            {
                continue;
            }
            let scale = e.residual_jacobian[row * dimension..(row + 1) * dimension]
                .iter()
                .zip(&local_values)
                .map(|(j, x)| (j * x).abs())
                .sum();
            let absolute = match e.residual_units[row] {
                crate::model::ResidualUnit::Ampere => 1e-12,
                crate::model::ResidualUnit::Volt => 1e-10,
                crate::model::ResidualUnit::VoltSquared => 1e-20,
                crate::model::ResidualUnit::Watt => 1e-9,
                crate::model::ResidualUnit::Kelvin => 1e-8,
            };
            // Branch current is an independent MNA unknown. Its absolute tolerance contributes
            // R·atol_i to the resistive voltage equation.
            let absolute = match (&element, row) {
                (Element::Resistor { ohms }, 2) => absolute + ohms * 1e-12,
                (Element::ThermalResistor(_), 2) => {
                    absolute + e.residual_jacobian[row * dimension + 2].abs() * 1e-12
                }
                _ => absolute,
            };
            if !close_with_tolerance(e.residual[row], 0.0, scale, absolute) {
                return Err(format!("Snapshot violates algebraic branch constraint: branch={id:?}, model={tag}, row={row}, residual={:e}, tolerance={:e}",
                    e.residual[row], absolute + 1e-8 * scale));
            }
        }
        let voltage = va - vb;
        let absorbed_watts = voltage * current
            + local_values[3..2 * element.port_count() - 1]
                .as_chunks::<2>()
                .0
                .iter()
                .map(|pair| (pair[0] - vb) * pair[1])
                .sum::<f64>();
        stored += e.stored_joules;
        power_balance_watts += absorbed_watts;
        if !absorbed_watts.is_finite() || !power_balance_watts.is_finite() {
            return Err("Non-finite snapshot power".into());
        }
        branches.push(DcBranch {
            additional_ports,
            id,
            positive,
            negative,
            element: element.clone(),
        });
        points.push(BranchOperatingPoint {
            id,
            voltage,
            current,
            absorbed_watts,
            observed_watts: if matches!(element, Element::Meter { .. }) {
                (va - vb) * current
            } else {
                absorbed_watts
            },
            dissipated_watts: e.dissipated_watts,
            stored_joules: e.stored_joules,
            local_values,
        });
    }
    let window_start = r.f64()?;
    if window_start < 0.0 || window_start > time_seconds {
        return Err("Invalid observation window boundary".into());
    }
    let mut moments = Vec::with_capacity(count);
    for _ in 0..count {
        let mut fields = [0.0; 12];
        let torque = r.f64()?;
        for value in &mut fields {
            *value = r.f64()?;
        }
        let mut moment = BranchMoments::from_fields(fields, time_seconds - window_start)
            .map_err(|d| d.reason)?;
        moment.torque_seconds = torque;
        let mut periodic = [0.0; 7];
        for value in &mut periodic {
            *value = r.f64()?;
        }
        moment.periodicity = crate::observation::periodicity::Periodicity::from_fields(periodic)
            .map_err(|d| d.reason)?;
        moments.push(moment);
    }
    let measurements = Measurements {
        window_start,
        branches: moments,
    };
    if !close(
        stored,
        energy.stored_joules,
        stored.abs() + energy.stored_joules,
    ) {
        return Err("Stored energy does not match snapshot physical state".into());
    }
    for (sum, magnitude) in kcl.values() {
        if !close_with_tolerance(*sum, 0.0, *magnitude, 1e-12) {
            return Err("Snapshot violates KCL".into());
        }
    }
    let mut visited = HashSet::new();
    let mut references = Vec::new();
    for root in nodes {
        if visited.contains(&root) {
            continue;
        }
        if voltages[&root] != 0.0 {
            return Err("Snapshot component reference must be zero".into());
        }
        references.push(root);
        let mut stack = vec![root];
        visited.insert(root);
        while let Some(node) = stack.pop() {
            for next in adjacency.get(&node).into_iter().flatten() {
                if visited.insert(*next) {
                    stack.push(*next);
                }
            }
        }
    }
    crate::topology::Layout::compile(&branches).map_err(|d| d.reason)?;
    let operating_point = DcOperatingPoint {
        voltages,
        references,
        branches: points,
        power_balance_watts,
    };
    let sampler = crate::observation::waveform::Sampler::read_snapshot(
        &mut r,
        revision,
        time_seconds,
        &operating_point,
    )?;
    let mut source_phases = HashMap::new();
    for (branch, point) in branches.iter().zip(&operating_point.branches) {
        if branch.element.source_clock().is_some() {
            let phase = r.f64()?;
            if !(0.0..std::f64::consts::TAU).contains(&phase) {
                return Err("Invalid snapshot source clock".into());
            }
            let phases = HashMap::from([(branch.clock_id(), phase)]);
            let candidate = crate::solver::transient::evaluate_at_phase(
                branch,
                &point.local_values,
                time_seconds,
                &phases,
            )
            .map_err(|d| d.reason)?;
            let dimension = branch.element.unknown_count();
            for row in (2..2 * branch.element.port_count() - 1).step_by(2) {
                let scale = candidate.residual_jacobian[row * dimension..(row + 1) * dimension]
                    .iter()
                    .zip(&point.local_values)
                    .map(|(j, x)| (j * x).abs())
                    .sum();
                if !close_with_tolerance(candidate.residual[row], 0.0, scale, 1e-10) {
                    return Err("Inconsistent accepted source-clock state".into());
                }
            }
            if let Some(old) = source_phases.insert(branch.clock_id(), phase)
                && old != phase
            {
                return Err("Inconsistent shared rotor phase".into());
            }
        }
    }
    let next_event = r.u64()?;
    let count = r.u32()? as usize;
    if next_event == 0 || count > branches.len() {
        return Err("Invalid protection event cursor/count".into());
    }
    let mut pending_events = Vec::with_capacity(count);
    let mut event_ids = HashSet::new();
    let mut event_branches = HashSet::new();
    for _ in 0..count {
        let event = em_protocol::contract::ProtectionEvent {
            id: r.id()?,
            branch: r.id()?,
            time: r.f64()?,
            temperature_kelvin: r.f64()?,
            stored_joules: r.f64()?,
            action: r.u32()?,
        };
        let index = branches
            .iter()
            .position(|b| b.id == event.branch)
            .ok_or("Missing pending protection target")?;
        let point = &operating_point.branches[index];
        let valid_action = match &branches[index].element {
            model if model.thermal_node().is_some() => {
                let (thermal, row) = model.thermal_node().unwrap();
                event.action == 0
                    && event.temperature_kelvin == thermal.ambient_kelvin + point.local_values[row]
                    && thermal
                        .trip_kelvin
                        .is_some_and(|trip| event.temperature_kelvin >= trip)
            }
            model @ Element::Relay { .. } => {
                event.temperature_kelvin == 0.0
                    && model
                        .relay_transition(point.voltage)
                        .is_some_and(|active| event.action == if active { 2 } else { 1 })
            }
            _ => false,
        };
        if event.time != time_seconds
            || event.id.0[0] != (revision.session.0[0] ^ revision.session.0[1])
            || event.id.0[1] == 0
            || event.id.0[1] >= next_event
            || !event_ids.insert(event.id)
            || !event_branches.insert(event.branch)
            || event.stored_joules != point.stored_joules
            || !valid_action
        {
            return Err("Inconsistent pending protection event".into());
        }
        pending_events.push(event);
    }
    let interval = match r.u32()? {
        0 => None,
        1 => {
            let input = em_protocol::wire::read_interval(&mut r)?;
            if input.start > time_seconds || input.end < time_seconds || input.end <= input.start
                || input.revision.session != revision.session || input.revision.sequence > revision.sequence {
                return Err("Invalid saved input interval".into());
            }
            Some(input)
        },
        _ => return Err("Invalid input interval presence".into()),
    };
    r.finish()?;
    let accepted = AcceptedState {
        time_seconds,
        energy,
        measurements,
        operating_point,
        sampler,
        source_phases,
    };
    let layout =
        std::sync::Arc::new(crate::topology::Layout::compile(&branches).map_err(|d| d.reason)?);
    let kernel = TransientCircuit {
        game_interval: interval.as_ref().is_some_and(|input| input.inputs.iter().any(|change| {
            matches!(change.value, em_protocol::contract::IntervalValue::ShaftSpeed(_))
                && layout.branch_indices.get(&change.branch).is_some_and(|&i| matches!(branches[i].element, Element::AcShaft { .. }))
        })),
        harmonic: Default::default(),
        linear_plan: Default::default(),
        newton_plan: Default::default(),
        plans: Default::default(),
        branches: std::sync::Arc::new(branches),
        layout,
        accepted,
    };
    kernel
        .validate_probes(
            &kernel
                .accepted()
                .sampler
                .channels
                .iter()
                .map(|s| s.channel.clone())
                .collect::<Vec<_>>(),
        )
        .map_err(|d| d.reason)?;
    Ok(CircuitSession {
        localization: None,
        trial: TrialCursor::default(),
        interval,
        external_cached: None,
        next_event,
        pending_events,
        event_cached: None,
        revision,
        kernel,
        max_step_seconds,
        cached: None,
        parameter_cached: None,
        topology_cached: None,
        subscription_cached: None,
        accuracy,
        suggested_step,
    })
}
