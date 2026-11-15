use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{dc::DcBranch, transient::TransientCircuit},
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;

fn branch(id: u64, a: u64, b: u64, element: Element) -> DcBranch {
    let port = |n| PortId {
        device: DeviceId {
            instance: Id([0, n]),
            generation: 0,
        },
        terminal: 0,
    };
    DcBranch {
        additional_ports: Vec::new(),
        id: Id([1, id]),
        positive: port(a),
        negative: port(b),
        element,
    }
}
fn revision(parameters: u64, sequence: u64) -> Revision {
    Revision {
        session: Id([2, 1]),
        topology: 1,
        parameters,
        sequence,
    }
}
fn session() -> CircuitSession {
    let circuit = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 1.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 1.0 }),
        branch(3, 2, 0, Element::Capacitor { farads: 1.0 }),
    ];
    let initial = InitialConditions::from([(Id([1, 3]), InitialStorage::ChargeCoulombs(0.0))]);
    CircuitSession::new(
        revision(1, 0),
        TransientCircuit::new(circuit, Some(&initial)).unwrap(),
        0.001,
    )
    .unwrap()
}
fn advance(s: &mut CircuitSession, parameters: u64, sequence: u64, end: f64) {
    let start = s.accepted().time_seconds;
    let result = s
        .advance(
            Advance {
                revision: revision(parameters, sequence),
                accepted_start: start,
                requested_end: end,
                max_steps: 10000,
                budget_nanos: 10_000_000_000,
            },
            &AtomicBool::new(false),
        )
        .unwrap();
    assert_eq!(result.end, end);
}
fn near(a: f64, b: f64, tolerance: f64) {
    assert!((a - b).abs() <= tolerance, "{a} != {b}");
}

#[test]
fn resistor_edit_preserves_charge_time_ledger_and_changes_future_time_constant() {
    let mut s = session();
    advance(&mut s, 1, 1, 0.5);
    let before = s.accepted().clone();
    let changes = vec![(Id([1, 2]), Element::Resistor { ohms: 2.0 })];
    s.set_parameters(revision(2, 2), 0.5, changes.clone())
        .unwrap();
    assert_eq!(s.accepted().time_seconds, before.time_seconds);
    assert_eq!(s.accepted().energy, before.energy);
    near(
        s.accepted().operating_point.branches[2].voltage,
        before.operating_point.branches[2].voltage,
        1e-14,
    );
    near(
        s.accepted().operating_point.branches[2].current,
        before.operating_point.branches[2].current / 2.0,
        1e-14,
    );
    let after = s.snapshot();
    s.set_parameters(revision(2, 2), 0.5, changes).unwrap();
    assert_eq!(after, s.snapshot());
    let mut restored = session();
    restored.restore(&after).unwrap();
    assert!(
        restored
            .set_parameters(revision(2, 2), 0.5, vec![])
            .is_err()
    );
    advance(&mut s, 2, 3, 1.0);
    advance(&mut restored, 2, 3, 1.0);
    assert_eq!(s.snapshot(), restored.snapshot());
    let expected = 1.0 - (1.0 - before.operating_point.branches[2].voltage) * (-0.25_f64).exp();
    near(
        s.accepted().operating_point.branches[2].voltage,
        expected,
        0.0001,
    );
}

#[test]
fn invalid_parameter_batches_are_atomic_and_do_not_consume_sequence() {
    let mut s = session();
    advance(&mut s, 1, 1, 0.1);
    let before = s.snapshot();
    for changes in [
        vec![
            (Id([1, 2]), Element::Resistor { ohms: 2.0 }),
            (Id([1, 99]), Element::Resistor { ohms: 1.0 }),
        ],
        vec![(Id([1, 3]), Element::Capacitor { farads: 0.0 })],
        vec![(Id([1, 2]), Element::Resistor { ohms: f64::NAN })],
        vec![
            (Id([1, 2]), Element::Resistor { ohms: 2.0 }),
            (Id([1, 2]), Element::Resistor { ohms: 3.0 }),
        ],
        vec![(Id([1, 2]), Element::DcVoltageSource { volts: 2.0 })],
    ] {
        assert!(s.set_parameters(revision(2, 2), 0.1, changes).is_err());
        assert_eq!(before, s.snapshot());
    }
    assert!(s.set_parameters(revision(2, 2), 0.0, vec![]).is_err());
    assert!(s.set_parameters(revision(3, 2), 0.1, vec![]).is_err());
    assert_eq!(before, s.snapshot());
    s.set_parameters(
        revision(2, 2),
        0.1,
        vec![(Id([1, 1]), Element::DcVoltageSource { volts: 2.0 })],
    )
    .unwrap();
    near(s.accepted().operating_point.branches[0].voltage, 2.0, 1e-12);
}

#[test]
fn inductor_edit_preserves_flux_and_accounts_parameter_work() {
    let circuit = vec![
        branch(1, 1, 0, Element::Resistor { ohms: 1.0 }),
        branch(2, 1, 0, Element::Inductor { henries: 1.0 }),
    ];
    let initial = InitialConditions::from([(Id([1, 2]), InitialStorage::FluxWebers(1.0))]);
    let mut s = CircuitSession::new(
        revision(1, 0),
        TransientCircuit::new(circuit, Some(&initial)).unwrap(),
        0.001,
    )
    .unwrap();
    s.set_parameters(
        revision(2, 1),
        0.0,
        vec![(Id([1, 1]), Element::Resistor { ohms: 2.0 })],
    )
    .unwrap();
    near(s.accepted().operating_point.branches[1].current, 1.0, 1e-12);
    near(
        s.accepted().operating_point.branches[1].voltage,
        -2.0,
        1e-12,
    );
    s.set_parameters(
        revision(3, 2),
        0.0,
        vec![(Id([1, 2]), Element::Inductor { henries: 2.0 })],
    )
    .unwrap();
    near(s.accepted().operating_point.branches[1].current, 0.5, 1e-12);
    near(s.accepted().energy.parameter_work_joules, -0.25, 1e-12);
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-12);
    assert_eq!(
        CircuitSession::from_snapshot(&s.snapshot())
            .unwrap()
            .snapshot(),
        s.snapshot()
    );
    advance(&mut s, 3, 3, 0.5);
    near(
        s.accepted().operating_point.branches[1].current,
        0.5 * (-0.5_f64).exp(),
        0.0004,
    );
}

#[test]
fn capacitor_parameter_work_preserves_charge_and_failed_batch_rolls_back() {
    let mut s = session();
    advance(&mut s, 1, 1, 0.1);
    let old_voltage = s.accepted().operating_point.branches[2].voltage;
    let old_energy = s.accepted().energy.stored_joules;
    let before = s.snapshot();
    assert!(
        s.set_parameters(
            revision(2, 2),
            0.1,
            vec![
                (Id([1, 3]), Element::Capacitor { farads: 2.0 }),
                (Id([1, 99]), Element::Resistor { ohms: 1.0 }),
            ]
        )
        .is_err()
    );
    assert_eq!(s.snapshot(), before);
    let changes = vec![(Id([1, 3]), Element::Capacitor { farads: 2.0 })];
    s.set_parameters(revision(2, 2), 0.1, changes.clone())
        .unwrap();
    near(
        s.accepted().operating_point.branches[2].voltage,
        old_voltage / 2.0,
        1e-12,
    );
    near(
        s.accepted().energy.parameter_work_joules,
        -old_energy / 2.0,
        1e-12,
    );
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-12);
    let saved = s.snapshot();
    s.set_parameters(revision(2, 2), 0.1, changes).unwrap();
    assert_eq!(s.snapshot(), saved);
    s.set_parameters(
        revision(3, 3),
        0.1,
        vec![(Id([1, 3]), Element::Capacitor { farads: 0.5 })],
    )
    .unwrap();
    near(
        s.accepted().operating_point.branches[2].voltage,
        old_voltage * 2.0,
        1e-12,
    );
    near(s.accepted().energy.parameter_work_joules, old_energy, 1e-12);
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-12);
    assert_eq!(
        CircuitSession::from_snapshot(&s.snapshot())
            .unwrap()
            .snapshot(),
        s.snapshot()
    );
}
