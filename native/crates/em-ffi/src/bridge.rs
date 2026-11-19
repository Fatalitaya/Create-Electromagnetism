//! Safe dispatch and bounded, non-reused handles. JNI is the only pointer boundary.
use em_core::{
    model::element::Element,
    session::circuit::{CircuitSession, TickContinuation},
    solver::{
        dc::DcBranch,
        transient::{Accuracy, TransientCircuit},
    },
    state::{AcceptedState, InitialConditions, InitialStorage},
};
use em_protocol::{
    contract::{Id, Status},
    wire::{self, Command, Reader, Writer},
};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
};

struct Entry {
    identity: Id,
    session: Mutex<CircuitSession>,
    continuation: Mutex<Option<(Vec<u8>, em_protocol::contract::Revision, TickContinuation)>>,
    tick_receipt: Mutex<Option<(Vec<u8>,Vec<u8>)>>,
    cancelled: AtomicBool,
    closed: AtomicBool,
}
#[derive(Default)]
struct Registry {
    next: u64,
    entries: HashMap<u64, Arc<Entry>>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

pub fn response(status: u32, reason: &str, payload: &[u8]) -> Vec<u8> {
    let mut w = Writer::default();
    w.u32(wire::MAGIC);
    w.u32(status);
    w.string(reason);
    w.0.extend(payload);
    w.0
}

/// Every request is decoded fully before acquiring/mutating a session.
pub fn dispatch(bytes: &[u8]) -> Vec<u8> {
    contain(|| execute(bytes))
}

fn contain(action: impl FnOnce() -> Result<Vec<u8>, String> + std::panic::UnwindSafe) -> Vec<u8> {
    match std::panic::catch_unwind(action) {
        Ok(Ok(payload)) => response(0, "", &payload),
        Ok(Err(reason)) => response(1, &reason, &[]),
        // Mutex poisoning prevents continued use of any session mutated during panic.
        Err(_) => response(
            2,
            "Recoverable native panic; affected locked session is quarantined",
            &[],
        ),
    }
}

fn execute(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let request = wire::decode(bytes)?;
    let mut w = Writer::default();
    match request.command {
        Command::Repartition(request) => {
            // Never wait for a session while owning the registry: destroy takes the opposite path.
            let registered = registry().lock().map_err(|_| "Registry quarantined")?;
            let mut sources = request.sources.clone();sources.sort_by_key(|(handle,_)| *handle);
            if sources.windows(2).any(|pair| pair[0].0 == pair[1].0) { return Err("Duplicate repartition source".into()); }
            let entries = sources.iter().map(|(handle,_)| registered.entries.get(handle).cloned().ok_or("Unknown repartition source"))
                .collect::<Result<Vec<_>,_>>()?;
            drop(registered);
            let guards = entries.iter().map(|e| e.session.lock().map_err(|_| "Session quarantined")).collect::<Result<Vec<_>,_>>()?;
            let mut registry = registry().lock().map_err(|_| "Registry quarantined")?;
            for ((handle,_),entry) in sources.iter().zip(&entries) {
                if entry.closed.load(Ordering::Acquire) || entry.cancelled.load(Ordering::Acquire)
                    || !registry.entries.get(handle).is_some_and(|current| Arc::ptr_eq(current,entry)) {
                    return Err("Repartition source changed ownership".into());
                }
            }
            if registry.entries.len() - sources.len() + request.targets.len() > 64 { return Err("Live session limit exceeded".into()); }
            for (revision,_) in &request.targets {
                if registry.entries.values().any(|entry| entry.identity == revision.session) { return Err("Destination identity already owned".into()); }
            }
            let inputs = guards.iter().zip(&sources).map(|(session,(_,revision))| (&**session,*revision)).collect::<Vec<_>>();
            let clock=inputs.iter().map(|(session,_)|session.accepted().time_seconds).fold(0.0,f64::max);
            let candidates = CircuitSession::repartition(&inputs,&request.targets,clock).map_err(|d| d.reason)?;
            let last = registry.next.checked_add(candidates.len() as u64).ok_or("Handle space exhausted")?;
            let first = registry.next + 1;
            for (handle,_) in &sources { registry.entries.remove(handle); }
            for entry in &entries { entry.closed.store(true,Ordering::Release); }
            w.u32(candidates.len() as u32);
            for (index,session) in candidates.into_iter().enumerate() {
                let handle = first + index as u64;let identity = session.revision().session;
                registry.entries.insert(handle,Arc::new(Entry { identity,session:Mutex::new(session),continuation:Mutex::default(),tick_receipt:Mutex::default(),cancelled:AtomicBool::new(false),closed:AtomicBool::new(false) }));
                w.u64(handle);
            }
            registry.next = last;
        }
        Command::Info => {
            w.string(em_protocol::MOD_VERSION);
            w.u64(1);
        }
        Command::Create {
            revision,
            max_step,
            accuracy,
            explicit_initial,
            branches,
        } => {
            let mut initial = InitialConditions::new();
            let mut inputs = Vec::with_capacity(branches.len());
            for b in branches {
                let mut parameters = vec![b.parameter];
                parameters.extend(b.auxiliary);
                let element =
                    Element::from_parameters(b.model, &parameters).map_err(|d| d.reason)?;
                if explicit_initial {
                    if matches!(element, Element::Regulator(_)) {
                        initial.insert(b.id, InitialStorage::DetectorVoltageSquared(b.initial));
                    } else if element.thermal_node().is_some() {
                        initial.insert(b.id, InitialStorage::TemperatureKelvin(b.initial));
                    } else if matches!(element, Element::Windings(_)) {
                        initial.insert(
                            b.id,
                            InitialStorage::WindingFluxes {
                                primary: b.initial,
                                secondary: b.initial_extra,
                            },
                        );
                    } else if element.control_capacitances().is_some() {
                        initial.insert(
                            b.id,
                            InitialStorage::PortCharges {
                                control_return: b.initial,
                                control_output: b.initial_extra,
                            },
                        );
                    } else if element.capacitance().is_some() {
                        initial.insert(b.id, InitialStorage::ChargeCoulombs(b.initial));
                    } else if matches!(element, Element::Inductor { .. }) {
                        initial.insert(b.id, InitialStorage::FluxWebers(b.initial));
                    } else if b.initial != 0.0 {
                        return Err("Unexpected storage on stateless branch".into());
                    }
                }
                inputs.push(DcBranch {
                    additional_ports: b.additional_ports,
                    id: b.id,
                    positive: b.positive,
                    negative: b.negative,
                    element,
                });
            }
            let kernel = TransientCircuit::new(inputs, explicit_initial.then_some(&initial))
                .map_err(|d| d.reason)?;
            let session = match accuracy {
                Some(
                    [
                        relative,
                        voltage,
                        current,
                        charge,
                        flux,
                        energy,
                        minimum_step,
                    ],
                ) => CircuitSession::new_adaptive(
                    revision,
                    kernel,
                    max_step,
                    Accuracy {
                        relative,
                        voltage,
                        current,
                        charge,
                        flux,
                        energy,
                        minimum_step,
                    },
                ),
                None => CircuitSession::new(revision, kernel, max_step),
            }
            .map_err(|d| d.reason)?;
            let mut registry = registry().lock().map_err(|_| "Registry quarantined")?;
            if registry.entries.len() >= 64 {
                return Err("Live session limit exceeded".into());
            }
            if registry
                .entries
                .values()
                .any(|entry| entry.identity == revision.session)
            {
                return Err("Session identity already has an active owner".into());
            }
            let handle = registry
                .next
                .checked_add(1)
                .ok_or("Handle space exhausted")?;
            registry.next = handle;
            registry.entries.insert(
                handle,
                Arc::new(Entry {
                    identity: revision.session,
                    session: Mutex::new(session),
                    continuation: Mutex::default(),
                    tick_receipt: Mutex::default(),
                    cancelled: AtomicBool::new(false),
                    closed: AtomicBool::new(false),
                }),
            );
            w.u64(handle);
        }
        command => {
            let entry = registry()
                .lock()
                .map_err(|_| "Registry quarantined")?
                .entries
                .get(&request.handle)
                .cloned()
                .ok_or("Unknown or expired handle")?;
            if entry.closed.load(Ordering::Acquire) {
                return Err("Closed session".into());
            }
            match command {
                Command::Cancel => {
                    entry.cancelled.store(true, Ordering::Release);
                }
                Command::Destroy => {
                    if entry
                        .closed
                        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                        .is_err()
                    {
                        return Err("Closed session".into());
                    }
                    entry.cancelled.store(true, Ordering::Release);
                    // Wait for any active operation, even a poisoned one, before removing ownership.
                    let _guard = entry.session.lock().unwrap_or_else(|e| e.into_inner());
                    registry()
                        .lock()
                        .map_err(|_| "Registry quarantined")?
                        .entries
                        .remove(&request.handle);
                }
                Command::SetInterval(_)
                | Command::Read
                | Command::ExchangeEnergy { .. }
                | Command::ReadObservations(_)
                | Command::SubscribeWaveforms(_)
                | Command::Tick { .. }
                | Command::Advance(_)
                | Command::AcknowledgeEvents { .. }
                | Command::Snapshot
                | Command::Restore(_)
                | Command::AnalyzeAc { .. }
                | Command::SetParameters { .. }
                | Command::ApplyTopology { .. } => {
                    let mut session = entry
                        .session
                        .lock()
                        .map_err(|_| "Session quarantined after panic")?;
                    if entry.closed.load(Ordering::Acquire) {
                        return Err("Closed session".into());
                    }
                    if let Command::Tick { revision, world_tick, offset, seconds, edits } = command {
                        let mut receipt=entry.tick_receipt.lock().map_err(|_| "Tick receipt quarantined")?;
                        if session.revision()==revision && let Some((request,response))=receipt.as_ref() && request==bytes {
                            return Ok(response.clone());
                        }
                        let start=world_tick as f64*0.05+offset;
                        if start+1e-9<session.accepted().time_seconds {
                            return Err("Tick transaction overlaps committed physics".into());
                        }
                        let mut slot = entry.continuation.lock().map_err(|_| "Tick continuation quarantined")?;
                        let mut continuation = if let Some((key, base, _)) = slot.as_ref() {
                            if key != bytes || *base != session.revision() {
                                return Err("Tick continuation belongs to different inputs or revision".into());
                            }
                            slot.take().unwrap().2
                        } else {
                            let mut candidate=session.tick_candidate(start).map_err(|d| d.reason)?;
                            for mut edit in edits {
                                match &mut edit {
                                    Command::ApplyTopology { accepted_time,.. } | Command::SetParameters { accepted_time,.. } => *accepted_time=start,
                                    Command::SetInterval(input) => {
                                        input.start=start;
                                        input.end=start+seconds;
                                        for value in &mut input.inputs {
                                            if let em_protocol::contract::IntervalValue::Audio { start: audio_start,.. }=&mut value.value {
                                                *audio_start=start-offset;
                                            }
                                        }
                                    }
                                    _ => {}
                                }
                                session_command(edit,&entry,&mut candidate)?;
                            }
                            let advance=em_protocol::contract::Advance {revision,accepted_start:start,
                                requested_end:start+seconds,max_steps:i32::MAX as u32,budget_nanos:10_000_000};
                            TickContinuation::new(candidate, advance)
                        };
                        let Some(result) = continuation.poll(&entry.cancelled).map_err(|d| d.reason)? else {
                            *slot = Some((bytes.to_vec(), session.revision(), continuation));
                            w.u32(0);
                            return Ok(w.0);
                        };
                        if entry.cancelled.load(Ordering::Acquire) {
                            w.u32(3);
                            w.string("Tick cancelled");
                            return Ok(w.0);
                        }
                        let candidate = continuation.into_candidate();
                        if result.diagnostic.status!=Status::Ready && candidate.pending_events().is_empty() {
                            w.u32(if result.diagnostic.status==Status::Partial {3} else {2});
                            w.string(&result.diagnostic.reason);
                            return Ok(w.0);
                        }
                        w.u32(1);
                        w.revision(result.revision);
                        w.f64(result.end-start);
                        write_work(&mut w,&result);
                        write_observations(&mut w,candidate.observations(revision).map_err(|d| d.reason)?);
                        if w.0.len()+16>wire::MAX_BYTES {return Err("Tick observations exceed response limit".into());}
                        *receipt=Some((bytes.to_vec(),w.0.clone()));
                        *session=candidate;
                        return Ok(w.0);
                    }
                    if !matches!(&command, Command::Snapshot | Command::Read | Command::ReadObservations(_) | Command::AnalyzeAc { .. }) {
                        entry.continuation.lock().map_err(|_| "Tick continuation quarantined")?.take();
                    }
                    return session_command(command,&entry,&mut session);
                }
                _ => unreachable!("handled creation/info"),
            }
        }
    }
    Ok(w.0)
}

fn session_command(command: Command, entry: &Entry, session: &mut CircuitSession) -> Result<Vec<u8>,String> {
    let mut w=Writer::default();
    if let Command::SetInterval(input) = command {
        if entry.cancelled.load(Ordering::Acquire) { return Err("Cancelled session".into()); }
        session.set_interval(input).map_err(|d| d.reason)?;
        return Ok(w.0);
    }
    if let Command::ExchangeEnergy {
        revision,
        branch,
        delta,
        maximum,
        commit,
    } = command
    {
        if entry.cancelled.load(Ordering::Acquire) {
            return Err("Cancelled session".into());
        }
        w.u32(u32::from(
            session
                .exchange_energy(
                    revision,
                    session.accepted().time_seconds,
                    branch,
                    delta,
                    maximum,
                    commit,
                )
                .map_err(|d| d.reason)?,
        ));
        return Ok(w.0);
    } else if let Command::AcknowledgeEvents {
        revision,
        ids,
        destroyed,
    } = command
    {
        session
            .acknowledge_destruction(revision, session.accepted().time_seconds, ids, destroyed)
            .map_err(|d| d.reason)?;
        return Ok(w.0);
    }
    if let Command::ReadObservations(revision) = command {
        let observations = session.observations(revision).map_err(|d| d.reason)?;
        write_accepted(&mut w, &observations.accepted);
        write_observations(&mut w, observations);
        return Ok(w.0);
    }
    if let Command::SubscribeWaveforms(subscription) = command {
        if entry.cancelled.load(Ordering::Acquire) {
            return Err("Cancelled session cannot change subscriptions".into());
        }
        session
            .subscribe_waveforms(subscription)
            .map_err(|d| d.reason)?;
        return Ok(w.0);
    }
    if let Command::ApplyTopology {
        revision,
        accepted_time,
        branches,
        export_removed_energy,
        parameter_edit,
    } = command
    {
        if entry.cancelled.load(Ordering::Acquire) {
            return Err("Cancelled session cannot accept topology edits".into());
        }
        let branches = branches
            .into_iter()
            .map(|b| {
                let mut parameters = vec![b.parameter];
                parameters.extend(b.auxiliary);
                Element::from_parameters(b.model, &parameters).map(
                    |element| DcBranch {
                        additional_ports: b.additional_ports,
                        id: b.id,
                        positive: b.positive,
                        negative: b.negative,
                        element,
                    },
                )
            })
            .collect::<Result<Vec<_>, _>>()
            .map_err(|d| d.reason)?;
        if parameter_edit {
            session.apply_boundary(
                revision,
                accepted_time,
                branches,
                export_removed_energy,
            )
        } else {
            session.apply_topology_with_disposition(
                revision,
                accepted_time,
                branches,
                export_removed_energy,
            )
        }
        .map_err(|d| d.reason)?;
        return Ok(w.0);
    }
    if let Command::SetParameters {
        revision,
        accepted_time,
        changes,
    } = command
    {
        if entry.cancelled.load(Ordering::Acquire) {
            return Err("Cancelled session cannot accept parameter edits".into());
        }
        let changes = changes
            .into_iter()
            .map(|(id, model, parameters)| {
                Element::from_parameters(model, &parameters)
                    .map(|element| (id, element))
            })
            .collect::<Result<Vec<_>, _>>()
            .map_err(|d| d.reason)?;
        session
            .set_parameters(revision, accepted_time, changes)
            .map_err(|d| d.reason)?;
        return Ok(w.0);
    }
    if let Command::AnalyzeAc { hertz, excitations } = command {
        let sources: Vec<_> = excitations
            .into_iter()
            .map(|(id, [real, imaginary])| {
                (id, em_core::solver::ac::Phasor { real, imaginary })
            })
            .collect();
        let result = session.analyze_ac(hertz, &sources).map_err(|d| d.reason)?;
        w.f64(result.hertz);
        w.u32(result.branches.len() as u32);
        for branch in result.branches {
            w.id(branch.id);
            for value in [
                branch.voltage.real,
                branch.voltage.imaginary,
                branch.current.real,
                branch.current.imaginary,
            ] {
                w.f64(value);
            }
        }
        return Ok(w.0);
    }
    if matches!(command, Command::Snapshot) {
        let snapshot = session.snapshot();
        w.u32((snapshot.len() + 8) as u32);
        w.u32(u32::from(entry.cancelled.load(Ordering::Acquire)));
        w.u32(snapshot.len() as u32);
        w.0.extend(snapshot);
        return Ok(w.0);
    }
    if let Command::Restore(bytes) = command {
        let mut reader = Reader::new(&bytes)?;
        let cancelled = match reader.u32()? {
            0 => false,
            1 => true,
            _ => return Err("Invalid snapshot cancellation flag".into()),
        };
        let snapshot = reader.blob()?;
        reader.finish()?;
        let runtime_step_limit = session.adaptive_step_limit();
        session.restore(&snapshot).map_err(|d| d.reason)?;
        session.retain_adaptive_step_limit(runtime_step_limit);
        // A concurrent cancellation can never be undone by restore.
        entry.cancelled.fetch_or(cancelled, Ordering::AcqRel);
        return Ok(w.0);
    }
    if let Command::Advance(advance) = command {
        let result = session
            .advance(advance, &entry.cancelled)
            .map_err(|d| d.reason)?;
        write_accepted(&mut w, &result);
    }
    write_state(&mut w, session.accepted());
    Ok(w.0)
}

fn write_observations(w: &mut Writer, observations: em_protocol::contract::Observations) {
    w.u32(observations.readings.len() as u32);
    for reading in observations.readings {
        w.id(reading.branch);
        for value in [
            reading.window_start,
            reading.window_end,
            reading.end_voltage,
            reading.end_current,
            reading.mean_voltage,
            reading.rms_voltage,
            reading.peak_voltage,
            reading.mean_current,
            reading.rms_current,
            reading.peak_current,
            reading.active_power,
            reading.absorbed_energy,
            reading.returned_energy,
            reading.dissipated_energy,
        ] {
            w.f64(value);
        }
        for value in [
            reading.frequency,
            reading.phase,
            reading.temperature_kelvin,
            reading.torque_newton_metres,
        ] {
            w.u32(u32::from(value.is_some()));
            if let Some(value) = value {
                w.f64(value);
            }
        }
        for value in [
            reading.total_absorbed_energy,
            reading.total_returned_energy,
            reading.total_dissipated_energy,
        ] {
            w.f64(value);
        }
    }
    w.u32(observations.waveforms.len() as u32);
    for waveform in observations.waveforms {
        w.id(waveform.channel);
        w.port(waveform.positive);
        w.port(waveform.negative);
        w.u64(waveform.dropped_samples);
        w.u32(waveform.samples.len() as u32);
        for sample in waveform.samples {
            w.f64(sample.time);
            w.u32(match sample.quality {
                em_protocol::contract::SampleQuality::Valid => 0,
                em_protocol::contract::SampleQuality::Reconstructed => 1,
                em_protocol::contract::SampleQuality::Missing => 2,
                em_protocol::contract::SampleQuality::Unconverged => 3,
                em_protocol::contract::SampleQuality::BandwidthLimit => 4,
            });
            w.u32(u32::from(sample.value.is_some()));
            if let Some(value) = sample.value {
                w.f64(value);
            }
        }
    }
    w.u32(observations.events.len() as u32);
    for event in observations.events {
        w.id(event.id);
        w.id(event.branch);
        w.f64(event.time);
        w.f64(event.temperature_kelvin);
        w.f64(event.stored_joules);
        w.u32(event.action);
    }
}

fn write_accepted(w: &mut Writer, result: &em_protocol::contract::Accepted) {
    w.revision(result.revision);
    w.f64(result.start);
    w.f64(result.end);
    w.f64(result.requested_end);
    write_work(w,result);
}

fn write_work(w: &mut Writer, result: &em_protocol::contract::Accepted) {
    w.u32(match result.diagnostic.status {
        Status::Ready => 0,
        Status::Partial => 1,
        Status::Rejected => 2,
        Status::Unsupported => 3,
        Status::Unavailable => 4,
        Status::Closed => 5,
    });
    w.string(&result.diagnostic.reason);
    for count in [
        result.work.attempted_steps,
        result.work.accepted_steps,
        result.work.rejected_steps,
        result.work.discarded_steps,
        result.work.localization_trials,
    ] {
        w.u32(count);
    }
}

fn write_state(w: &mut Writer, state: &AcceptedState) {
    w.f64(state.time_seconds);
    let e = state.energy;
    for value in [
        e.initial_stored_joules,
        e.stored_joules,
        e.source_supplied_joules,
        e.source_absorbed_joules,
        e.dissipated_joules,
        e.numerical_damping_joules,
        e.balance_residual_joules(),
    ] {
        w.f64(value);
    }
    w.u32(state.operating_point.branches.len() as u32);
    for branch in &state.operating_point.branches {
        w.id(branch.id);
        for value in [
            branch.voltage,
            branch.current,
            branch.absorbed_watts,
            branch.dissipated_watts,
            branch.stored_joules,
        ] {
            w.f64(value);
        }
    }
    w.f64(e.parameter_work_joules);
    w.f64(e.removed_joules);
}

#[cfg(test)]
mod tests {
    use super::*;
    use em_protocol::{
        contract::{Id, Revision},
        wire::Reader,
    };

    fn header(operation: u32, handle: u64) -> Writer {
        let mut w = Writer::default();
        w.u32(wire::MAGIC);
        w.string(em_protocol::MOD_VERSION);
        w.u32(operation);
        w.u64(handle);
        w
    }
    fn create() -> u64 {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        let mut w = header(1, 0);
        w.revision(Revision {
            session: Id([10, NEXT.fetch_add(1, Ordering::Relaxed)]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        });
        w.f64(0.01);
        w.u32(0);
        w.u32(0);
        let payload = execute(&w.0).unwrap();
        Reader::new(&payload).unwrap().u64().unwrap()
    }

    #[test]
    fn panic_is_contained_and_poisoned_session_cannot_be_reused() {
        let handle = create();
        let entry = registry().lock().unwrap().entries[&handle].clone();
        let result = contain(|| {
            let _guard = entry.session.lock().unwrap();
            panic!("injected recoverable panic while owning session");
        });
        assert_eq!(u32::from_le_bytes(result[4..8].try_into().unwrap()), 2);
        assert!(
            execute(&header(3, handle).0)
                .unwrap_err()
                .contains("quarantined")
        );
        execute(&header(4, handle).0).unwrap();
        assert!(execute(&header(3, handle).0).is_err());
    }

    #[test]
    fn destroy_waits_for_active_owner_and_future_lookup_is_stale() {
        let handle = create();
        let entry = registry().lock().unwrap().entries[&handle].clone();
        let owner = entry.session.lock().unwrap();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let task = std::thread::spawn(move || {
            let result = execute(&header(4, handle).0);
            done_tx.send(result).unwrap();
        });
        let start = std::time::Instant::now();
        while !entry.closed.load(Ordering::Acquire) {
            assert!(start.elapsed() < std::time::Duration::from_secs(5));
            std::thread::yield_now();
        }
        assert!(done_rx.try_recv().is_err());
        assert!(entry.cancelled.load(Ordering::Acquire));
        drop(owner);
        done_rx
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap()
            .unwrap();
        task.join().unwrap();
        assert!(execute(&header(3, handle).0).is_err());
    }
}
