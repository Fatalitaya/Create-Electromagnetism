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
fn revision(topology: u64, sequence: u64) -> Revision {
    Revision {
        session: Id([2, 1]),
        topology,
        parameters: 1,
        sequence,
    }
}
fn circuit() -> Vec<DcBranch> {
    vec![
        branch(1, 1, 0, Element::Resistor { ohms: 1.0 }),
        branch(2, 1, 0, Element::Capacitor { farads: 1.0 }),
        branch(3, 3, 2, Element::Resistor { ohms: 2.0 }),
        branch(4, 3, 2, Element::Capacitor { farads: 1.0 }),
    ]
}
fn session() -> CircuitSession {
    let initial = InitialConditions::from([
        (Id([1, 2]), InitialStorage::ChargeCoulombs(1.0)),
        (Id([1, 4]), InitialStorage::ChargeCoulombs(2.0)),
    ]);
    CircuitSession::new(
        revision(1, 0),
        TransientCircuit::new(circuit(), Some(&initial)).unwrap(),
        0.001,
    )
    .unwrap()
}

#[test]
fn combined_parameter_and_removal_boundary_has_one_atomic_energy_account() {
    let mut s = session();
    let before = s.snapshot();
    let revision = Revision {
        parameters: 2,
        ..revision(2, 1)
    };
    let mut next = circuit();
    next.retain(|b| b.id != Id([1, 4]));
    next[1].element = Element::Capacitor { farads: 2.0 };
    assert!(
        s.apply_boundary(revision, 0.0, next.clone(), false)
            .is_err()
    );
    assert_eq!(s.snapshot(), before);
    s.apply_boundary(revision, 0.0, next.clone(), true).unwrap();
    let energy = s.accepted().energy;
    assert!((energy.removed_joules - 2.0).abs() < 1e-12);
    assert!((energy.parameter_work_joules + 0.25).abs() < 1e-12);
    assert!((energy.stored_joules - 0.25).abs() < 1e-12);
    assert!(energy.balance_residual_joules().abs() < 1e-12);
    let accepted = s.snapshot();
    s.apply_boundary(revision, 0.0, next, true).unwrap();
    assert_eq!(accepted, s.snapshot());
}

#[test]
fn split_merge_reorder_preserve_identity_charge_energy_and_restore() {
    let mut s = session();
    let before = s.accepted().energy;
    let mut merged = circuit();
    merged.push(branch(5, 0, 2, Element::Resistor { ohms: 1.0 }));
    merged.push(branch(6, 1, 3, Element::Resistor { ohms: 1.0 }));
    merged.reverse();
    s.apply_topology(revision(2, 1), 0.0, merged.clone())
        .unwrap();
    assert_eq!(s.accepted().operating_point.references.len(), 1);
    assert_eq!(before, s.accepted().energy);
    for (id, voltage) in [(2, 1.0), (4, 2.0)] {
        let point = s
            .accepted()
            .operating_point
            .branches
            .iter()
            .find(|b| b.id == Id([1, id]))
            .unwrap();
        assert!((point.voltage - voltage).abs() < 1e-12);
    }
    let checkpoint = s.snapshot();
    s.apply_topology(revision(2, 1), 0.0, merged).unwrap();
    assert_eq!(checkpoint, s.snapshot());
    let mut restored = session();
    restored.restore(&checkpoint).unwrap();
    assert_eq!(checkpoint, restored.snapshot());
    let request = Advance {
        revision: revision(2, 2),
        accepted_start: 0.0,
        requested_end: 0.1,
        max_steps: 1000,
        budget_nanos: 10_000_000_000,
    };
    s.advance(request, &AtomicBool::new(false)).unwrap();
    restored.advance(request, &AtomicBool::new(false)).unwrap();
    assert_eq!(s.snapshot(), restored.snapshot());
    let energy = s.accepted().energy;
    s.apply_topology(revision(3, 3), 0.1, circuit()).unwrap();
    assert_eq!(s.accepted().operating_point.references.len(), 2);
    assert_eq!(energy, s.accepted().energy);
}

#[test]
fn destructive_or_inconsistent_edits_are_rejected_atomically() {
    let mut s = session();
    let before = s.snapshot();
    let mut removal = circuit();
    removal.retain(|b| b.id != Id([1, 2]));
    let mut conflict = circuit();
    conflict.push(branch(5, 1, 0, Element::DcVoltageSource { volts: 5.0 }));
    let mut replacement = circuit();
    replacement[1].id = Id([1, 99]);
    let mut changed = circuit();
    changed[0].element = Element::Resistor { ohms: 2.0 };
    for branches in [removal, conflict, replacement, changed] {
        assert!(s.apply_topology(revision(2, 1), 0.0, branches).is_err());
        assert_eq!(before, s.snapshot());
    }
    let mut duplicate = circuit();
    duplicate.push(duplicate[0].clone());
    assert!(s.apply_topology(revision(2, 1), 0.0, duplicate).is_err());
    assert!(s.apply_topology(revision(2, 1), 0.1, circuit()).is_err());
    assert_eq!(before, s.snapshot());
}

#[test]
fn new_storage_is_zero_and_cannot_import_unaccounted_energy() {
    let mut s = session();
    let mut new = circuit();
    new.push(branch(5, 5, 4, Element::Resistor { ohms: 1.0 }));
    new.push(branch(6, 5, 4, Element::Capacitor { farads: 1.0 }));
    s.apply_topology(revision(2, 1), 0.0, new).unwrap();
    assert_eq!(s.accepted().energy.stored_joules, 2.5);
    assert_eq!(s.accepted().operating_point.branches[5].voltage, 0.0);
    s.apply_topology(revision(3, 2), 0.0, circuit()).unwrap();
    assert_eq!(s.accepted().energy.stored_joules, 2.5);
}

#[test]
fn explicit_removal_exports_energy_once_and_replacement_cannot_inherit_storage() {
    let mut s = session();
    let mut replacement = circuit();
    replacement[1].id = Id([1, 99]);
    s.apply_topology_with_disposition(revision(2, 1), 0.0, replacement.clone(), true)
        .unwrap();
    assert_eq!(s.accepted().energy.stored_joules, 2.0);
    assert_eq!(s.accepted().energy.removed_joules, 0.5);
    assert_eq!(s.accepted().energy.dissipated_joules, 0.0);
    assert_eq!(s.accepted().energy.balance_residual_joules(), 0.0);
    assert_eq!(s.accepted().operating_point.branches[1].voltage, 0.0);
    let saved = s.snapshot();
    s.apply_topology_with_disposition(revision(2, 1), 0.0, replacement.clone(), true)
        .unwrap();
    assert_eq!(s.snapshot(), saved);
    assert!(
        s.apply_topology_with_disposition(revision(2, 1), 0.0, replacement, false)
            .is_err()
    );
    assert_eq!(s.snapshot(), saved);
    let restored = CircuitSession::from_snapshot(&saved).unwrap();
    assert_eq!(restored.snapshot(), saved);
    let mut invalid = circuit();
    invalid.retain(|b| b.id != Id([1, 4]));
    invalid.push(branch(5, 1, 0, Element::DcVoltageSource { volts: 5.0 }));
    assert!(
        s.apply_topology_with_disposition(revision(3, 2), 0.0, invalid, true)
            .is_err()
    );
    assert_eq!(s.snapshot(), saved);
}
