use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{dc::DcBranch, transient::TransientCircuit},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;

fn revision(parameters: u64, sequence: u64) -> Revision {
    Revision {
        session: Id([1, 1]),
        topology: 1,
        parameters,
        sequence,
    }
}
fn source(hertz: f64, phase: f64) -> Element {
    Element::SineVoltageSource {
        offset: 0.0,
        peak: 10.0,
        hertz,
        phase,
    }
}
fn advance(s: &mut CircuitSession, parameters: u64, sequence: u64, end: f64) {
    let result = s
        .advance(
            Advance {
                revision: revision(parameters, sequence),
                accepted_start: s.accepted().time_seconds,
                requested_end: end,
                max_steps: 10000,
                budget_nanos: 30_000_000_000,
            },
            &AtomicBool::new(false),
        )
        .unwrap();
    assert_eq!(result.end, end, "{}", result.diagnostic.reason);
}
fn session() -> CircuitSession {
    let port = |terminal| PortId {
        device: DeviceId {
            instance: Id([4, 1]),
            generation: 0,
        },
        terminal,
    };
    let branches = [source(10.0, 0.3), Element::Resistor { ohms: 5.0 }]
        .into_iter()
        .enumerate()
        .map(|(i, element)| DcBranch {
            additional_ports: Vec::new(),
            id: Id([2, i as u64]),
            positive: port(0),
            negative: port(1),
            element,
        })
        .collect();
    CircuitSession::new(
        revision(1, 0),
        TransientCircuit::new(branches, None).unwrap(),
        0.0001,
    )
    .unwrap()
}
fn close(a: f64, b: f64) {
    assert!((a - b).abs() < 1e-10, "{a} != {b}");
}

#[test]
fn retuning_frequency_preserves_phase_energy_and_continues_after_restore() {
    let mut s = session();
    advance(&mut s, 1, 1, 0.037);
    let before = s.accepted().clone();
    let changes = vec![(Id([2, 0]), source(25.0, 0.3))];
    s.set_parameters(revision(2, 2), 0.037, changes.clone())
        .unwrap();
    close(
        s.accepted().operating_point.branches[0].voltage,
        before.operating_point.branches[0].voltage,
    );
    assert_eq!(s.accepted().source_phases, before.source_phases);
    assert_eq!(s.accepted().energy, before.energy);
    let checkpoint = s.snapshot();
    s.set_parameters(revision(2, 2), 0.037, changes).unwrap();
    assert_eq!(s.snapshot(), checkpoint);
    let mut restored = CircuitSession::from_snapshot(&checkpoint).unwrap();
    advance(&mut s, 2, 3, 0.047);
    advance(&mut restored, 2, 3, 0.047);
    assert_eq!(s.snapshot(), restored.snapshot());
    close(
        s.accepted().operating_point.branches[0].voltage,
        10.0 * (0.3 + std::f64::consts::TAU * (10.0 * 0.037 + 25.0 * 0.01)).sin(),
    );
    let observations = s.observations(revision(2, 3)).unwrap();
    close(observations.readings[0].frequency.unwrap(), 25.0);
    close(
        observations.readings[0].phase.unwrap(),
        before.source_phases[&Id([2, 0])],
    );
    assert!(observations.readings[1].frequency.is_none());
    let saved = s.snapshot();
    assert!(
        s.set_parameters(revision(3, 4), 0.047, vec![(Id([2, 0]), source(40.0, 0.0))])
            .is_err()
    );
    assert_eq!(s.snapshot(), saved);
}

#[test]
fn zero_frequency_freezes_the_accepted_phase_and_bad_phase_snapshot_rejects() {
    let mut s = session();
    advance(&mut s, 1, 1, 0.037);
    s.set_parameters(revision(2, 2), 0.037, vec![(Id([2, 0]), source(0.0, 0.3))])
        .unwrap();
    let voltage = s.accepted().operating_point.branches[0].voltage;
    let phase = s.accepted().source_phases.clone();
    advance(&mut s, 2, 3, 0.1);
    close(s.accepted().operating_point.branches[0].voltage, voltage);
    assert_eq!(s.accepted().source_phases, phase);
    assert!(
        s.observations(revision(2, 3)).unwrap().readings[0]
            .frequency
            .is_none()
    );
    let mut checkpoint = s.snapshot();
    let last = checkpoint.len() - 20;
    checkpoint[last..last + 8].copy_from_slice(&(-1.0_f64).to_le_bytes());
    assert!(CircuitSession::from_snapshot(&checkpoint).is_err());
}
