use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{dc::DcBranch, transient::TransientCircuit},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;

fn revision(sequence: u64) -> Revision {
    Revision {
        session: Id([2, 8]),
        topology: 1,
        parameters: 1,
        sequence,
    }
}
fn session(source: Element, load: Element, step: f64) -> CircuitSession {
    let port = |terminal| PortId {
        device: DeviceId {
            instance: Id([3, 1]),
            generation: 1,
        },
        terminal,
    };
    let branches = [source, load]
        .into_iter()
        .enumerate()
        .map(|(i, element)| DcBranch {
            additional_ports: Vec::new(),
            id: Id([1, i as u64]),
            positive: port(0),
            negative: port(1),
            element,
        })
        .collect();
    CircuitSession::new(
        revision(0),
        TransientCircuit::new(branches, None).unwrap(),
        step,
    )
    .unwrap()
}
fn advance(s: &mut CircuitSession, sequence: u64, end: f64, steps: u32, cancel: bool) -> Advance {
    let request = Advance {
        revision: revision(sequence),
        accepted_start: s.accepted().time_seconds,
        requested_end: end,
        max_steps: steps,
        budget_nanos: 30_000_000_000,
    };
    s.advance(request, &AtomicBool::new(cancel)).unwrap();
    request
}
fn close(a: f64, b: f64, tolerance: f64) {
    assert!((a - b).abs() <= tolerance, "{a} != {b}");
}
fn sine() -> Element {
    Element::SineVoltageSource {
        offset: 0.0,
        peak: 10.0,
        hertz: 10.0,
        phase: 0.0,
    }
}

#[test]
fn dc_partial_window_is_not_stretched_and_duplicate_read_does_not_consume_energy() {
    let mut s = session(
        Element::DcVoltageSource { volts: 10.0 },
        Element::Resistor { ohms: 5.0 },
        0.001,
    );
    assert!(s.observations(revision(0)).is_err());
    let request = advance(&mut s, 1, 0.05, 2, false);
    let observed = s.observations(revision(1)).unwrap();
    let load = &observed.readings[1];
    close(load.window_end, 0.002, 1e-15);
    close(load.mean_voltage, 10.0, 1e-12);
    close(load.rms_voltage, 10.0, 1e-12);
    close(load.peak_voltage, 10.0, 1e-12);
    close(load.rms_current, 2.0, 1e-12);
    close(load.active_power, 20.0, 1e-12);
    close(load.absorbed_energy, 0.04, 1e-12);
    close(observed.readings[0].returned_energy, 0.04, 1e-12);
    assert!(load.frequency.is_none() && load.phase.is_none());
    let snapshot = s.snapshot();
    s.advance(request, &AtomicBool::new(false)).unwrap();
    s.observations(revision(1)).unwrap();
    assert_eq!(snapshot, s.snapshot());
    assert!(s.observations(revision(0)).is_err());
    advance(&mut s, 2, 0.003, 1, false);
    let second = s.observations(revision(2)).unwrap();
    close(second.readings[1].absorbed_energy, 0.02, 1e-12);
    close(
        s.accepted().measurements.branches[1].total_absorbed_joules,
        0.06,
        1e-12,
    );
}

#[test]
fn sinusoidal_rms_and_power_use_integrals_not_product_of_means() {
    let mut s = session(sine(), Element::Resistor { ohms: 5.0 }, 0.0001);
    advance(&mut s, 1, 0.1, 10001, false);
    let observed = s.observations(revision(1)).unwrap();
    let load = &observed.readings[1];
    close(load.mean_voltage, 0.0, 1e-10);
    close(load.mean_current, 0.0, 1e-10);
    close(load.rms_voltage, 10.0 / 2.0_f64.sqrt(), 1e-10);
    close(load.peak_voltage, 10.0, 1e-10);
    close(load.active_power, 10.0, 1e-10);
    close(load.absorbed_energy, 1.0, 1e-10);
    close(
        load.dissipated_energy,
        s.accepted().energy.dissipated_joules,
        1e-12,
    );
}

#[test]
fn noninteger_window_converges_to_analytic_sine_integrals() {
    let end = 0.037;
    let omega = std::f64::consts::TAU * 10.0;
    let mean = 10.0 * (1.0 - (omega * end).cos()) / (omega * end);
    let mean_square = 100.0 * (0.5 - (2.0 * omega * end).sin() / (4.0 * omega * end));
    let mut errors = Vec::new();
    for step in [0.0001, 0.00005] {
        let mut s = session(sine(), Element::Resistor { ohms: 5.0 }, step);
        advance(&mut s, 1, end, 10001, false);
        let r = &s.observations(revision(1)).unwrap().readings[1];
        errors.push((r.mean_voltage - mean).abs() + (r.rms_voltage - mean_square.sqrt()).abs());
        close(r.active_power, mean_square / 5.0, 0.02);
    }
    assert!(errors[1] < 0.55 * errors[0]);
}

#[test]
fn reactive_bidirectional_energy_restore_and_cancel_preserve_accepted_observations() {
    let mut s = session(sine(), Element::Capacitor { farads: 0.01 }, 0.0001);
    advance(&mut s, 1, 0.1, 10001, false);
    let r = &s.observations(revision(1)).unwrap().readings[1];
    assert!(r.absorbed_energy > 0.9 && r.returned_energy > 0.9);
    close(r.dissipated_energy, 0.0, 1e-15);
    close(
        r.absorbed_energy - r.returned_energy,
        s.accepted().energy.stored_joules + s.accepted().energy.numerical_damping_joules,
        1e-10,
    );
    let checkpoint = s.snapshot();
    let mut restored = CircuitSession::from_snapshot(&checkpoint).unwrap();
    assert_eq!(checkpoint, restored.snapshot());
    assert!(restored.observations(revision(1)).is_err());
    let before = restored.accepted().clone();
    advance(&mut restored, 2, 0.2, 10000, true);
    assert!(
        restored
            .observations(revision(2))
            .unwrap()
            .readings
            .is_empty()
    );
    assert_eq!(before, *restored.accepted());
    advance(&mut s, 3, 0.15, 10001, false);
    advance(&mut restored, 3, 0.15, 10001, false);
    assert_eq!(s.snapshot(), restored.snapshot());
    let mut corrupt = checkpoint;
    // Final observation state precedes the empty sampler, source phase and event cursor.
    let last = corrupt.len() - 32;
    corrupt[last..last + 8].copy_from_slice(&(-1.0_f64).to_le_bytes());
    assert!(CircuitSession::from_snapshot(&corrupt).is_err());
}

#[test]
fn measured_frequency_survives_windows_restore_and_cancel_but_reacquires_after_edit() {
    let mut s = session(sine(), Element::Resistor { ohms: 5.0 }, 0.0001);
    advance(&mut s, 1, 0.25, 10000, false);
    assert!(
        s.observations(revision(1)).unwrap().readings[1]
            .frequency
            .is_none()
    );
    advance(&mut s, 2, 0.45, 10000, false);
    let readings = s.observations(revision(2)).unwrap().readings;
    close(readings[1].frequency.unwrap(), 10.0, 1e-6);
    close(readings[1].phase.unwrap(), std::f64::consts::PI, 1e-5);
    let snapshot = s.snapshot();
    let mut restored = CircuitSession::from_snapshot(&snapshot).unwrap();
    assert_eq!(snapshot, restored.snapshot());
    advance(&mut restored, 3, 0.5, 1000, true);
    assert_eq!(*s.accepted(), *restored.accepted());
    advance(&mut restored, 4, 0.55, 10000, false);
    close(
        restored.observations(revision(4)).unwrap().readings[1]
            .frequency
            .unwrap(),
        10.0,
        1e-6,
    );
    let source_id = restored.accepted().operating_point.branches[0].id;
    restored
        .set_parameters(
            Revision {
                parameters: 2,
                sequence: 5,
                ..revision(5)
            },
            0.55,
            vec![(
                source_id,
                Element::SineVoltageSource {
                    offset: 10.0,
                    peak: 0.0,
                    hertz: 0.0,
                    phase: 0.0,
                },
            )],
        )
        .unwrap();
    assert!(
        restored.accepted().measurements.branches[1]
            .periodicity
            .reading(0.01)
            .is_none()
    );
}
