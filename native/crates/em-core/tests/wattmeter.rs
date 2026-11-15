use em_core::{
    model::{DeviceModel, element::Element},
    session::circuit::CircuitSession,
    solver::{dc::DcBranch, transient::TransientCircuit},
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
fn circuit() -> Vec<DcBranch> {
    vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 10.0 }),
        DcBranch {
            additional_ports: vec![port(2)],
            ..branch(2, 1, 0, Element::Meter { ohms: 0.1 })
        },
        branch(3, 2, 3, Element::Resistor { ohms: 9.9 }),
        branch(4, 3, 0, Element::DcVoltageSource { volts: 0.0 }),
    ]
}
fn near(a: f64, b: f64) {
    assert!((a - b).abs() < 1e-9, "{a} != {b}");
}
#[test]
fn through_power_is_distinct_from_contact_heat_and_bidirectional_counts_restore() {
    let r = Revision {
        session: Id([7, 14]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s =
        CircuitSession::new(r, TransientCircuit::new(circuit(), None).unwrap(), 0.001).unwrap();
    let request = Advance {
        revision: Revision { sequence: 1, ..r },
        accepted_start: 0.0,
        requested_end: 0.1,
        max_steps: 1000,
        budget_nanos: 10_000_000_000,
    };
    s.advance(request, &AtomicBool::new(false)).unwrap();
    let result = s.observations(s.revision()).unwrap();
    let reading = &result.readings[1];
    near(reading.active_power, 10.0);
    near(reading.dissipated_energy, 0.01);
    near(reading.total_absorbed_energy, 1.0);
    near(s.accepted().energy.balance_residual_joules(), 0.0);
    let saved = s.snapshot();
    let mut s = CircuitSession::from_snapshot(&saved).unwrap();
    assert_eq!(saved, s.snapshot());
    s.set_parameters(
        Revision {
            parameters: 2,
            sequence: 2,
            ..r
        },
        0.1,
        vec![(Id([1, 4]), Element::DcVoltageSource { volts: 20.0 })],
    )
    .unwrap();
    let request = Advance {
        revision: Revision {
            parameters: 2,
            sequence: 3,
            ..r
        },
        accepted_start: 0.1,
        requested_end: 0.2,
        ..request
    };
    s.advance(request, &AtomicBool::new(false)).unwrap();
    let result = s.observations(s.revision()).unwrap();
    let reading = &result.readings[1];
    near(reading.active_power, -10.0);
    near(reading.total_absorbed_energy, 1.0);
    near(reading.total_returned_energy, 1.0);
    near(reading.total_dissipated_energy, 0.02);
    near(s.accepted().energy.balance_residual_joules(), 0.0);
    let saved = s.snapshot();
    s.advance(request, &AtomicBool::new(false)).unwrap();
    assert_eq!(saved, s.snapshot());
}
#[test]
fn meter_jacobian_reference_isolation_and_passive_contact_are_explicit() {
    let model = Element::Meter { ohms: 0.1 };
    let x = [10.0, 0.0, 1.0, 9.9, -1.0];
    let base = model.evaluate(&x, 0.0).unwrap();
    near(base.dissipated_watts, 0.1);
    for col in 0..5 {
        let mut plus = x;
        let mut minus = x;
        plus[col] += 1e-5;
        minus[col] -= 1e-5;
        let a = model.evaluate(&plus, 0.0).unwrap();
        let b = model.evaluate(&minus, 0.0).unwrap();
        for row in 0..5 {
            near(
                (a.residual[row] - b.residual[row]) / 2e-5,
                base.residual_jacobian[5 * row + col],
            );
        }
    }
    let mut isolated = circuit();
    isolated[1].negative = port(99);
    assert!(
        TransientCircuit::new(isolated, None)
            .err()
            .unwrap()
            .reason
            .contains("floating island")
    );
}

#[test]
fn open_meter_with_finite_insulation_and_warm_leads_converges() {
    use em_core::model::thermal::ThermalResistor;
    let mut branches = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 10.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 0.001 }),
        DcBranch {
            additional_ports: vec![port(4)],
            ..branch(3, 3, 7, Element::Meter { ohms: 0.001 })
        },
        branch(4, 3, 7, Element::Resistor { ohms: 9999.0 }),
        branch(5, 8, 7, Element::Resistor { ohms: 0.001 }),
        branch(6, 5, 6, Element::Resistor { ohms: 5.0 }),
    ];
    for (id, a, b) in [(7, 2, 3), (8, 0, 7), (9, 4, 5), (10, 6, 8)] {
        branches.push(branch(
            id,
            a,
            b,
            Element::ThermalResistor(ThermalResistor {
                ambient_ohms: 0.01,
                coefficient_per_kelvin: 0.0,
                heat_capacity_joules_per_kelvin: 40.0,
                conductance_watts_per_kelvin: 0.4,
                ambient_kelvin: 293.15,
                trip_kelvin: Some(673.15),
            }),
        ));
    }
    for shift in 0..10 {
        let mut branches = branches.clone();
        branches.rotate_left(shift);
        let r = Revision {
            session: Id([19, 1]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        };
        let mut s =
            CircuitSession::new(r, TransientCircuit::new(branches, None).unwrap(), 0.0001).unwrap();
        s.set_parameters(
            Revision {
                parameters: 2,
                sequence: 1,
                ..r
            },
            0.0,
            vec![
                (Id([1, 3]), Element::Meter { ohms: 1e12 }),
                (Id([1, 5]), Element::Resistor { ohms: 1e12 }),
            ],
        )
        .unwrap();
        let request = Advance {
            revision: Revision {
                parameters: 2,
                sequence: 2,
                ..r
            },
            accepted_start: 0.0,
            requested_end: 0.01,
            max_steps: 1000,
            budget_nanos: 10_000_000_000,
        };
        s.advance(request, &AtomicBool::new(false)).unwrap();
        assert_eq!(s.accepted().time_seconds, 0.01);
        let point = &s.accepted().operating_point;
        let reading = point.branches.iter().find(|b| b.id == Id([1, 3])).unwrap();
        let load_path = 2e12 + 5.02;
        let input_voltage = 10.0 / (1.0 + 0.021 * (1.0 / 9999.0 + 1.0 / load_path));
        let expected = input_voltage / load_path;
        assert!((reading.current / expected - 1.0).abs() < 1e-4);
        assert!((reading.voltage - input_voltage).abs() < 1e-8);
        assert!(s.accepted().energy.balance_residual_joules().abs() < 1e-8);
    }
}
