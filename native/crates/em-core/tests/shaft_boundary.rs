use em_core::{
    model::{DeviceModel, element::Element},
    session::circuit::CircuitSession,
    solver::{
        ac::{self, Phasor},
        dc::DcBranch,
        transient::TransientCircuit,
    },
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;
fn port(terminal: u32) -> PortId {
    PortId {
        device: DeviceId {
            instance: Id([0, 1]),
            generation: 0,
        },
        terminal,
    }
}
fn branch(id: u64, a: u32, b: u32, element: Element) -> DcBranch {
    DcBranch {
        id: Id([1, id]),
        positive: port(a),
        negative: port(b),
        additional_ports: vec![],
        element,
    }
}
fn shaft(omega: f64) -> Element {
    Element::ShaftBoundary {
        newton_metres_per_ampere: 2.0,
        radians_per_second: omega,
    }
}
fn circuit(omega: f64) -> Vec<DcBranch> {
    vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 12.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 2.0 }),
        branch(3, 2, 0, shaft(omega)),
    ]
}
fn near(a: f64, b: f64) {
    assert!((a - b).abs() < 1e-8, "{a} != {b}");
}
#[test]
fn stalled_forward_reverse_and_regenerative_shaft_work_are_reciprocal() {
    let revision = Revision {
        session: Id([9, 15]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    for omega in [0.0, 3.0, 9.0, -3.0] {
        let mut session = CircuitSession::new(
            revision,
            TransientCircuit::new(circuit(omega), None).unwrap(),
            0.001,
        )
        .unwrap();
        let request = Advance {
            revision: Revision {
                sequence: 1,
                ..revision
            },
            accepted_start: 0.0,
            requested_end: 0.1,
            max_steps: 1000,
            budget_nanos: 10_000_000_000,
        };
        session.advance(request, &AtomicBool::new(false)).unwrap();
        let observation = session.observations(session.revision()).unwrap();
        let motor = &observation.readings[2];
        let current = (12.0 - 2.0 * omega) / 2.0;
        near(motor.mean_current, current);
        near(motor.torque_newton_metres.unwrap(), 2.0 * current);
        near(
            motor.active_power,
            motor.torque_newton_metres.unwrap() * omega,
        );
        near(
            motor.total_absorbed_energy - motor.total_returned_energy,
            0.1 * 2.0 * omega * current,
        );
        near(session.accepted().energy.balance_residual_joules(), 0.0);
        if omega == 0.0 {
            near(motor.active_power, 0.0);
            near(motor.torque_newton_metres.unwrap(), 12.0);
        }
        if omega == 9.0 {
            assert!(observation.readings[0].total_absorbed_energy > 0.0);
            assert!(motor.total_returned_energy > 0.0);
        }
        let saved = session.snapshot();
        let mut restored = CircuitSession::from_snapshot(&saved).unwrap();
        assert_eq!(saved, restored.snapshot());
        restored
            .advance(
                Advance {
                    revision: Revision {
                        sequence: 2,
                        ..revision
                    },
                    accepted_start: 0.1,
                    requested_end: 0.2,
                    ..request
                },
                &AtomicBool::new(false),
            )
            .unwrap();
        let restored_observation = restored.observations(restored.revision()).unwrap();
        near(
            restored_observation.readings[2]
                .torque_newton_metres
                .unwrap(),
            motor.torque_newton_metres.unwrap(),
        );
        near(
            restored_observation.readings[2].total_absorbed_energy,
            2.0 * motor.total_absorbed_energy,
        );
        near(
            restored_observation.readings[2].total_returned_energy,
            2.0 * motor.total_returned_energy,
        );
        session.advance(request, &AtomicBool::new(false)).unwrap();
        assert_eq!(saved, session.snapshot());
        let response = ac::solve(
            &circuit(omega),
            50.0,
            &[(
                Id([1, 1]),
                Phasor {
                    real: 1.0,
                    imaginary: 0.0,
                },
            )],
        )
        .unwrap();
        near(response.branches[2].voltage.real, 0.0);
        near(response.branches[2].current.real, 0.5);
    }
}
#[test]
fn velocity_edits_rebuild_constraints_without_fabricating_impulse_work() {
    let r = Revision {
        session: Id([9, 16]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s =
        CircuitSession::new(r, TransientCircuit::new(circuit(0.0), None).unwrap(), 0.001).unwrap();
    let before = s.accepted().energy;
    s.set_parameters(
        Revision {
            parameters: 2,
            sequence: 1,
            ..r
        },
        0.0,
        vec![(Id([1, 3]), shaft(9.0))],
    )
    .unwrap();
    near(s.accepted().operating_point.branches[2].current, -3.0);
    assert_eq!(before, s.accepted().energy);
    let saved = s.snapshot();
    assert!(
        s.set_parameters(
            Revision {
                parameters: 3,
                sequence: 2,
                ..r
            },
            0.0,
            vec![(Id([1, 3]), shaft(f64::NAN))]
        )
        .is_err()
    );
    assert_eq!(saved, s.snapshot());
    for model in [
        Element::ShaftBoundary {
            newton_metres_per_ampere: 0.0,
            radians_per_second: 0.0,
        },
        shaft(1e7),
    ] {
        assert!(model.validate().is_err());
    }
    assert!(shaft(0.0).evaluate(&[0.0, 0.0, f64::MAX], 0.0).is_err());
}

#[test]
fn winding_transient_and_speed_reversal_preserve_flux_and_mechanical_work() {
    use em_core::state::{InitialConditions, InitialStorage};
    let r = Revision {
        session: Id([9, 17]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut branches = circuit(3.0);
    branches[1].negative = port(3);
    branches.push(branch(4, 3, 2, Element::Inductor { henries: 0.5 }));
    let initial = InitialConditions::from([(Id([1, 4]), InitialStorage::FluxWebers(0.0))]);
    let mut s = CircuitSession::new(
        r,
        TransientCircuit::new(branches, Some(&initial)).unwrap(),
        0.0001,
    )
    .unwrap();
    let request = Advance {
        revision: Revision { sequence: 1, ..r },
        accepted_start: 0.0,
        requested_end: 0.1,
        max_steps: 2000,
        budget_nanos: 10_000_000_000,
    };
    s.advance(request, &AtomicBool::new(false)).unwrap();
    let current = s.accepted().operating_point.branches[2].current;
    assert!((current - 3.0 * (1.0 - (-0.4_f64).exp())).abs() < 0.0003);
    let reading = &s.observations(s.revision()).unwrap().readings[2];
    let work = 18.0 * (0.1 - 0.25 * (1.0 - (-0.4_f64).exp()));
    assert!((reading.total_absorbed_energy - work).abs() < 0.0004);
    near(s.accepted().energy.balance_residual_joules(), 0.0);
    let storage = s.accepted().energy.stored_joules;
    s.set_parameters(
        Revision {
            parameters: 2,
            sequence: 2,
            ..r
        },
        0.1,
        vec![(Id([1, 3]), shaft(9.0))],
    )
    .unwrap();
    near(s.accepted().operating_point.branches[2].current, current);
    near(s.accepted().energy.stored_joules, storage);
    near(s.accepted().energy.parameter_work_joules, 0.0);
    let saved = s.snapshot();
    let mut restored = CircuitSession::from_snapshot(&saved).unwrap();
    let next = Advance {
        revision: Revision {
            parameters: 2,
            sequence: 3,
            ..r
        },
        accepted_start: 0.1,
        requested_end: 0.6,
        max_steps: 6000,
        ..request
    };
    s.advance(next, &AtomicBool::new(false)).unwrap();
    restored.advance(next, &AtomicBool::new(false)).unwrap();
    assert_eq!(s.snapshot(), restored.snapshot());
    let expected = -3.0 + (current + 3.0) * (-2.0_f64).exp();
    assert!((s.accepted().operating_point.branches[2].current - expected).abs() < 0.0003);
    assert!(s.observations(s.revision()).unwrap().readings[2].total_returned_energy > 0.0);
    near(s.accepted().energy.balance_residual_joules(), 0.0);
}
