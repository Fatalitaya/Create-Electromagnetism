use em_core::{
    model::{DeviceModel, element::Element, thermal::ThermalResistor},
    session::circuit::CircuitSession,
    solver::{
        dc::{self, DcBranch},
        transient::TransientCircuit,
    },
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Advance, Status};
use em_protocol::contract::{DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;
fn model(alpha: f64) -> ThermalResistor {
    ThermalResistor {
        ambient_ohms: 2.0,
        coefficient_per_kelvin: alpha,
        heat_capacity_joules_per_kelvin: 4.0,
        conductance_watts_per_kelvin: 0.5,
        ambient_kelvin: 300.0,
        trip_kelvin: None,
    }
}
fn branches(m: ThermalResistor, volts: f64) -> Vec<DcBranch> {
    let a = PortId {
        device: DeviceId {
            instance: Id([0, 1]),
            generation: 0,
        },
        terminal: 0,
    };
    let b = PortId { terminal: 1, ..a };
    vec![
        DcBranch {
            id: Id([1, 1]),
            positive: a,
            negative: b,
            additional_ports: vec![],
            element: Element::DcVoltageSource { volts },
        },
        DcBranch {
            id: Id([1, 2]),
            positive: a,
            negative: b,
            additional_ports: vec![],
            element: Element::ThermalResistor(m),
        },
    ]
}
fn initial(m: ThermalResistor, t: f64, volts: f64) -> TransientCircuit {
    TransientCircuit::new(
        branches(m, volts),
        Some(&InitialConditions::from([(
            Id([1, 2]),
            InitialStorage::TemperatureKelvin(t),
        )])),
    )
    .unwrap()
}
fn near(a: f64, b: f64, tol: f64) {
    assert!((a - b).abs() < tol, "{a} != {b}");
}
#[test]
fn heating_and_cooling_match_analytic_thermal_time_constant_and_energy() {
    let m = model(0.0);
    let mut errors = vec![];
    for steps in [100, 200, 400] {
        let mut k = initial(m, 300.0, 4.0);
        for j in 1..=steps {
            k.step(8.0 * f64::from(j) / f64::from(steps)).unwrap();
        }
        let theta = k.accepted().operating_point.branches[1].local_values[3];
        errors.push((theta - 16.0 * (1.0 - (-1.0_f64).exp())).abs());
        near(k.accepted().energy.source_supplied_joules, 64.0, 1e-9);
        near(k.accepted().energy.balance_residual_joules(), 0.0, 1e-9);
        near(k.accepted().energy.numerical_damping_joules, 0.0, 1e-14);
    }
    assert!(errors[1] < 0.51 * errors[0] && errors[2] < 0.51 * errors[1]);
    let mut cooling = initial(m, 316.0, 0.0);
    for j in 1..=400 {
        cooling.step(0.02 * f64::from(j)).unwrap();
    }
    near(
        cooling.accepted().operating_point.branches[1].local_values[3],
        16.0 * (-1.0_f64).exp(),
        0.008,
    );
    near(
        cooling.accepted().energy.balance_residual_joules(),
        0.0,
        1e-9,
    );
}
#[test]
fn temperature_feedback_dc_and_full_jacobian_match_physical_reference() {
    let m = model(0.01);
    let e = Element::ThermalResistor(m);
    let x = [4.0, 0.0, 1.5, 10.0];
    let y = e.evaluate(&x, 0.0).unwrap();
    for col in 0..4 {
        let mut a = x;
        let mut b = x;
        a[col] += 1e-5;
        b[col] -= 1e-5;
        let a = e.evaluate(&a, 0.0).unwrap();
        let b = e.evaluate(&b, 0.0).unwrap();
        for row in 0..4 {
            near(
                (a.residual[row] - b.residual[row]) / 2e-5,
                y.residual_jacobian[row * 4 + col],
                1e-9,
            );
            near(
                (a.charge[row] - b.charge[row]) / 2e-5,
                y.charge_jacobian[row * 4 + col],
                1e-9,
            );
        }
    }
    // Thermal equilibrium V²/R(T) = Gθ gives 0.01θ² + θ − 16 = 0.
    let theta = (-1.0 + 1.64_f64.sqrt()) / 0.02;
    let p = dc::solve(&branches(m, 4.0)).unwrap();
    near(p.branches[1].local_values[3], theta, 1e-8);
    near(
        p.branches[1].current,
        4.0 / (2.0 * (1.0 + 0.01 * theta)),
        1e-10,
    );
    near(
        p.branches[1].dissipated_watts,
        p.branches[1].absorbed_watts,
        1e-9,
    );
}
#[test]
fn thermal_parameter_work_and_snapshot_preserve_temperature_without_replay() {
    let m = model(0.01);
    let r = Revision {
        session: Id([6, 1]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s = CircuitSession::new(r, initial(m, 310.0, 4.0), 0.001).unwrap();
    let mut changed = m;
    changed.heat_capacity_joules_per_kelvin = 8.0;
    s.set_parameters(
        Revision {
            parameters: 2,
            sequence: 1,
            ..r
        },
        0.0,
        vec![(Id([1, 2]), Element::ThermalResistor(changed))],
    )
    .unwrap();
    near(
        s.accepted().operating_point.branches[1].local_values[3],
        10.0,
        1e-10,
    );
    near(s.accepted().energy.parameter_work_joules, 40.0, 1e-10);
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
    let bytes = s.snapshot();
    let restored = CircuitSession::from_snapshot(&bytes).unwrap();
    assert_eq!(bytes, restored.snapshot());
    changed.ambient_kelvin = 290.0;
    assert!(
        s.set_parameters(
            Revision {
                parameters: 3,
                sequence: 2,
                ..r
            },
            0.0,
            vec![(Id([1, 2]), Element::ThermalResistor(changed))]
        )
        .is_err()
    );
    assert_eq!(bytes, s.snapshot());
}

fn advance(
    s: &mut CircuitSession,
    sequence: u64,
    end: f64,
    steps: u32,
) -> em_protocol::contract::Accepted {
    s.advance(
        Advance {
            revision: Revision {
                sequence,
                ..s.revision()
            },
            accepted_start: s.accepted().time_seconds,
            requested_end: end,
            max_steps: steps,
            budget_nanos: 10_000_000_000,
        },
        &AtomicBool::new(false),
    )
    .unwrap()
}

#[test]
fn fuse_stops_at_threshold_and_only_acknowledgement_can_resume_after_restore() {
    let mut m = model(0.0);
    m.trip_kelvin = Some(310.0);
    let r = Revision {
        session: Id([8, 1]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s = CircuitSession::new(r, initial(m, 300.0, 4.0), 0.02).unwrap();
    let result = advance(&mut s, 1, 10.0, 2000);
    assert_eq!(result.diagnostic.status, Status::Partial);
    near(result.end, -8.0 * 0.375_f64.ln(), 0.011);
    assert_eq!(s.pending_events().len(), 1);
    let event = s.pending_events()[0].clone();
    near(event.temperature_kelvin, 310.0, 1e-8);
    near(event.stored_joules, 40.0, 1e-8);
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-9);
    let observations = s.observations(s.revision()).unwrap();
    assert_eq!(observations.events, vec![event.clone()]);
    let saved = s.snapshot();
    let mut restored = CircuitSession::from_snapshot(&saved).unwrap();
    assert_eq!(saved, restored.snapshot());
    let ledger = restored.accepted().energy;
    let blocked = advance(&mut restored, 2, 10.0, 2000);
    assert_eq!(blocked.end, result.end);
    assert_eq!(restored.accepted().energy, ledger);
    assert_eq!(restored.pending_events(), std::slice::from_ref(&event));
    let unchanged = restored.snapshot();
    assert!(
        restored
            .apply_topology(
                Revision {
                    topology: 2,
                    sequence: 3,
                    ..r
                },
                blocked.end,
                vec![]
            )
            .is_err()
    );
    assert!(
        restored
            .set_parameters(
                Revision {
                    parameters: 2,
                    sequence: 3,
                    ..r
                },
                blocked.end,
                vec![(Id([1, 2]), Element::ThermalResistor(m))]
            )
            .is_err()
    );
    assert!(
        restored
            .acknowledge_events(
                Revision {
                    topology: 2,
                    sequence: 3,
                    ..r
                },
                blocked.end,
                vec![Id([0, 0])]
            )
            .is_err()
    );
    assert_eq!(unchanged, restored.snapshot());
    let ack = Revision {
        topology: 2,
        sequence: 3,
        ..r
    };
    restored
        .acknowledge_events(ack, blocked.end, vec![event.id])
        .unwrap();
    let acknowledged = restored.snapshot();
    restored
        .acknowledge_events(ack, blocked.end, vec![event.id])
        .unwrap();
    assert_eq!(acknowledged, restored.snapshot());
    assert!(restored.pending_events().is_empty());
    near(
        restored.accepted().energy.dissipated_joules - ledger.dissipated_joules,
        event.stored_joules,
        1e-9,
    );
    near(restored.accepted().energy.removed_joules, 0.0, 1e-12);
    near(
        restored.accepted().energy.balance_residual_joules(),
        0.0,
        1e-9,
    );
    let supplied = restored.accepted().energy.source_supplied_joules;
    assert_eq!(advance(&mut restored, 4, 10.0, 2000).end, 10.0);
    near(
        restored.accepted().energy.source_supplied_joules,
        supplied,
        1e-12,
    );
}

#[test]
fn interrupted_event_localization_never_publishes_overshoot_or_consumes_heat() {
    let mut m = model(0.0);
    m.trip_kelvin = Some(301.0);
    let r = Revision {
        session: Id([8, 2]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s = CircuitSession::new(r, initial(m, 300.0, 4.0), 1.0).unwrap();
    let ledger = s.accepted().energy;
    let result = advance(&mut s, 1, 2.0, 1);
    assert_eq!(result.end, 0.0);
    assert!(s.pending_events().is_empty());
    assert_eq!(ledger, s.accepted().energy);
    let result = advance(&mut s, 2, 2.0, 100);
    assert!(result.end > 0.0 && result.end < 1.0);
    assert_eq!(s.pending_events().len(), 1);
    near(s.pending_events()[0].temperature_kelvin, 301.0, 1e-8);
}

#[test]
fn sub_nanokelvin_heat_survives_topology_rebuild_without_absolute_temperature_roundoff() {
    let mut m = model(0.0);
    m.heat_capacity_joules_per_kelvin = 1000.0;
    let r = Revision {
        session: Id([8, 3]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut kernel = initial(m, 300.0, 4.0);
    kernel.step(1e-8).unwrap();
    let mut s = CircuitSession::new(r, kernel, 0.001).unwrap();
    let before = s.accepted().energy;
    assert!(before.stored_joules > 0.0 && before.stored_joules < 1e-6);
    s.apply_boundary(
        Revision {
            topology: 2,
            parameters: 2,
            sequence: 1,
            ..r
        },
        1e-8,
        branches(m, 4.0),
        false,
    )
    .unwrap();
    near(
        s.accepted().energy.stored_joules,
        before.stored_joules,
        1e-18,
    );
    near(s.accepted().energy.parameter_work_joules, 0.0, 1e-18);
}

#[test]
fn assembly_destruction_dissipates_junction_storage_atomically_and_once() {
    let mut m = model(0.0);
    m.trip_kelvin = Some(301.0);
    let mut circuit = branches(m, 4.0);
    let internal = PortId {
        terminal: 2,
        ..circuit[1].positive
    };
    let capacitor = DcBranch {
        id: Id([1, 3]),
        positive: internal,
        element: Element::Capacitor { farads: 0.5 },
        ..circuit[1].clone()
    };
    circuit.push(DcBranch {
        id: Id([1, 4]),
        negative: internal,
        element: Element::Resistor { ohms: 1.0 },
        ..circuit[0].clone()
    });
    circuit.push(capacitor);
    let initial = InitialConditions::from([
        (Id([1, 2]), InitialStorage::TemperatureKelvin(300.0)),
        (Id([1, 3]), InitialStorage::ChargeCoulombs(2.0)),
    ]);
    let r = Revision {
        session: Id([8, 9]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s = CircuitSession::new(
        r,
        TransientCircuit::new(circuit, Some(&initial)).unwrap(),
        0.01,
    )
    .unwrap();
    advance(&mut s, 1, 1.0, 1000);
    assert_eq!(s.pending_events().len(), 1);
    let ids = s.pending_events().iter().map(|e| e.id).collect::<Vec<_>>();
    let revision = Revision {
        topology: 2,
        sequence: 2,
        ..r
    };
    let time = s.accepted().time_seconds;
    let before = s.snapshot();
    let energy = s.accepted().energy;
    for removed in [vec![Id([1, 3]), Id([1, 3])], vec![Id([99, 99])]] {
        assert!(
            s.acknowledge_destruction(revision, time, ids.clone(), removed)
                .is_err()
        );
        assert_eq!(before, s.snapshot());
    }
    s.acknowledge_destruction(revision, time, ids.clone(), vec![Id([1, 3])])
        .unwrap();
    near(s.accepted().energy.stored_joules, 0.0, 1e-14);
    near(
        s.accepted().energy.removed_joules,
        energy.removed_joules,
        1e-14,
    );
    near(
        s.accepted().energy.dissipated_joules - energy.dissipated_joules,
        energy.stored_joules,
        1e-10,
    );
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-9);
    let acknowledged = s.snapshot();
    s.acknowledge_destruction(revision, time, ids, vec![Id([1, 3])])
        .unwrap();
    assert_eq!(acknowledged, s.snapshot());
}
