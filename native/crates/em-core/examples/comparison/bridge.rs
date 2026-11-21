//! Shared replay driver compiled against each native solver by compare-baked.py.
use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::transient::{Accuracy, TransientCircuit},
};
use em_protocol::contract::{
    Advance, BranchInterval, IntervalInputs, IntervalValue, Observations, Status,
};
use std::{ffi::OsString, path::PathBuf, sync::atomic::AtomicBool, time::Instant};

pub fn prescribe(session: &mut CircuitSession, rpm: f64) {
    let start = session.accepted().time_seconds;
    let end = start + 0.05;
    let mut revision = session.revision();
    revision.sequence += 1;
    let inputs: Vec<_> = session
        .branches()
        .iter()
        .filter(|b| matches!(b.element, Element::AcShaft { .. }))
        .map(|b| BranchInterval {
            branch: b.id,
            owner: b.positive.device,
            value: IntervalValue::ShaftSpeed(rpm * std::f64::consts::TAU / 60.0),
        })
        .collect();
    assert_eq!(inputs.len(), 3, "expected three alternator phases");
    session
        .set_interval(IntervalInputs {
            revision,
            start,
            end,
            inputs,
        })
        .unwrap();
}

fn window(
    session: &mut CircuitSession,
    rpm: f64,
) -> (Observations, em_protocol::contract::AdvanceWork) {
    prescribe(session, rpm);
    let start = session.accepted().time_seconds;
    let end = start + 0.05;
    let mut revision = session.revision();
    revision.sequence += 1;
    let result = session
        .advance_tick(
            Advance {
                revision,
                accepted_start: start,
                requested_end: end,
                max_steps: i32::MAX as u32,
                budget_nanos: i64::MAX as u64,
            },
            &AtomicBool::new(false),
        )
        .unwrap();
    assert_eq!(
        result.diagnostic.status,
        Status::Ready,
        "open bridge at {rpm} RPM: {:?}",
        result.diagnostic
    );
    assert_eq!(result.end, end, "incomplete open-bridge tick");
    let observations = session.observations(revision).unwrap();
    assert!(
        observations.events.is_empty(),
        "unexpected open-bridge protection event"
    );
    (observations, result.work)
}

pub fn run(
    mut args: impl Iterator<Item = OsString>,
    restore: impl Fn(&[u8], u32) -> CircuitSession,
    fallbacks: impl Fn() -> u64,
) {
    let action = args.next().expect("prepare or measure");
    let path = PathBuf::from(args.next().expect("checkpoint path"));
    if action == "prepare" {
        let fixture = CircuitSession::from_snapshot(include_bytes!(
            "../fixtures/three-phase-bridge-open.bin"
        ))
        .unwrap();
        let mut branches = fixture.branches().to_vec();
        for branch in &mut branches {
            if let Element::AcShaft { omega, .. } = &mut branch.element {
                *omega = 0.0;
            }
        }
        // Use the captured wiring and start at rest, so no 256 RPM charge survives into the seed.
        let kernel = TransientCircuit::new(branches, None).unwrap();
        let accuracy = Accuracy {
            relative: 1e-3,
            voltage: 1e-6,
            current: 1e-9,
            charge: 1e-15,
            flux: 1e-12,
            energy: 1e-9,
            minimum_step: 1e-12,
        };
        let mut session =
            CircuitSession::new_adaptive(fixture.revision(), kernel, 0.05, accuracy).unwrap();
        window(&mut session, 1.0);
        std::fs::write(path, session.snapshot()).unwrap();
        return;
    }
    assert_eq!(action, "measure");
    let ticks: usize = args.next().unwrap().to_str().unwrap().parse().unwrap();
    let level = args
        .next()
        .map_or(0, |s| s.to_str().unwrap().parse().unwrap());
    let mut session = restore(&std::fs::read(path).unwrap(), level);
    for tick in 0..ticks {
        let timer = Instant::now();
        let (observations, work) = window(&mut session, 256.0);
        let nanos = timer.elapsed().as_nanos();
        let observed: std::collections::HashSet<_> = observations
            .readings
            .iter()
            .map(|reading| reading.branch)
            .collect();
        assert_eq!(observations.readings.len(), session.branches().len());
        assert!(
            session
                .branches()
                .iter()
                .all(|branch| observed.contains(&branch.id))
        );
        println!("window {tick} {nanos}");
        eprintln!(
            "tick={tick} accepted={} rejected={} newton={} fallbacks={}",
            work.accepted_steps,
            work.rejected_steps,
            em_core::solver::last_advance_profile().newton_iterations,
            fallbacks()
        );
        for reading in observations.readings {
            let [hi, lo] = reading.branch.0;
            let values = [
                reading.mean_voltage,
                reading.rms_voltage,
                reading.peak_voltage,
                reading.mean_current,
                reading.rms_current,
                reading.peak_current,
                reading.absorbed_energy,
                reading.returned_energy,
                reading.dissipated_energy,
                reading.torque_newton_metres.unwrap_or(0.0),
            ];
            println!("branch {tick} {hi}.{lo} {values:?}");
        }
    }
}
