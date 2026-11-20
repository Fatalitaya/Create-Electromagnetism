use em_core::{
    model::{DeviceModel, element::Element},
    session::circuit::CircuitSession,
    solver::{
        dc::{self, DcBranch},
        transient::{Accuracy, TransientCircuit},
    },
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision, Status};
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
fn rc(initial_voltage: f64, source_voltage: f64) -> TransientCircuit {
    TransientCircuit::new(
        vec![
            branch(
                1,
                1,
                0,
                Element::DcVoltageSource {
                    volts: source_voltage,
                },
            ),
            branch(2, 1, 2, Element::Resistor { ohms: 1.0 }),
            branch(3, 2, 0, Element::Capacitor { farads: 1.0 }),
        ],
        Some(&InitialConditions::from([(
            Id([1, 3]),
            InitialStorage::ChargeCoulombs(initial_voltage),
        )])),
    )
    .unwrap()
}
fn near(actual: f64, expected: f64, tolerance: f64) {
    assert!(
        (actual - expected).abs() <= tolerance,
        "actual={actual} expected={expected} tolerance={tolerance}"
    );
}
fn run(kernel: &mut TransientCircuit, end: f64, steps: u32) {
    for n in 1..=steps {
        kernel.step(end * f64::from(n) / f64::from(steps)).unwrap();
    }
}
fn revision(sequence: u64) -> Revision {
    Revision {
        session: Id([5, 1]),
        topology: 1,
        parameters: 1,
        sequence,
    }
}
fn request(sequence: u64, start: f64, end: f64, steps: u32) -> Advance {
    Advance {
        revision: revision(sequence),
        accepted_start: start,
        requested_end: end,
        max_steps: steps,
        budget_nanos: 10_000_000_000,
    }
}

#[test]
fn rc_step_converges_first_order_and_energy_quadrature_converges() {
    let expected = 1.0 - (-1.0_f64).exp();
    let mut errors = Vec::new();
    let mut loss_errors = Vec::new();
    for steps in [100, 200, 400] {
        let mut kernel = rc(0.0, 1.0);
        run(&mut kernel, 1.0, steps);
        let state = kernel.accepted();
        errors.push((state.operating_point.branches[2].voltage - expected).abs());
        loss_errors.push((state.energy.dissipated_joules - (1.0 - (-2.0_f64).exp()) / 2.0).abs());
        near(state.energy.balance_residual_joules(), 0.0, 1e-11);
        assert!(state.energy.numerical_damping_joules > 0.0);
    }
    for errors in [errors, loss_errors] {
        for pair in errors.windows(2) {
            assert!(pair[0] / pair[1] > 1.9 && pair[0] / pair[1] < 2.1);
        }
    }
}

#[test]
fn rl_step_converges_with_flux_and_correct_source_power() {
    let mut errors = Vec::new();
    for steps in [100, 200, 400] {
        let mut kernel = TransientCircuit::new(
            vec![
                branch(1, 1, 0, Element::DcVoltageSource { volts: 1.0 }),
                branch(2, 1, 2, Element::Resistor { ohms: 1.0 }),
                branch(3, 2, 0, Element::Inductor { henries: 1.0 }),
            ],
            Some(&InitialConditions::from([(
                Id([1, 3]),
                InitialStorage::FluxWebers(0.0),
            )])),
        )
        .unwrap();
        run(&mut kernel, 1.0, steps);
        let state = kernel.accepted();
        errors.push((state.operating_point.branches[2].current - (1.0 - (-1.0_f64).exp())).abs());
        assert!(state.operating_point.branches[0].absorbed_watts < 0.0);
        near(state.energy.balance_residual_joules(), 0.0, 1e-11);
    }
    for pair in errors.windows(2) {
        assert!(pair[0] / pair[1] > 1.9 && pair[0] / pair[1] < 2.1);
    }
}

#[test]
fn source_free_capacitor_discharges_without_losing_ledger_energy() {
    let mut kernel = TransientCircuit::new(
        vec![
            branch(1, 1, 0, Element::Resistor { ohms: 1.0 }),
            branch(2, 1, 0, Element::Capacitor { farads: 1.0 }),
        ],
        Some(&InitialConditions::from([(
            Id([1, 2]),
            InitialStorage::ChargeCoulombs(1.0),
        )])),
    )
    .unwrap();
    run(&mut kernel, 1.0, 1000);
    let state = kernel.accepted();
    near(
        state.operating_point.branches[1].voltage,
        (-1.0_f64).exp(),
        2e-4,
    );
    near(state.energy.source_supplied_joules, 0.0, 0.0);
    near(state.energy.balance_residual_joules(), 0.0, 1e-11);
}

#[test]
fn lossless_lc_reports_damping_separately_and_converges_to_analytic_solution() {
    let mut last_error = f64::INFINITY;
    let mut last_damping = f64::INFINITY;
    for steps in [100, 200, 400] {
        let mut kernel = TransientCircuit::new(
            vec![
                branch(1, 1, 0, Element::Capacitor { farads: 1.0 }),
                branch(2, 1, 0, Element::Inductor { henries: 1.0 }),
            ],
            Some(&InitialConditions::from([
                (Id([1, 1]), InitialStorage::ChargeCoulombs(1.0)),
                (Id([1, 2]), InitialStorage::FluxWebers(0.0)),
            ])),
        )
        .unwrap();
        run(&mut kernel, 1.0, steps);
        let state = kernel.accepted();
        let error = (state.operating_point.branches[0].voltage - 1.0_f64.cos()).abs()
            + (state.operating_point.branches[1].current - 1.0_f64.sin()).abs();
        assert!(error < last_error * 0.52);
        assert!(state.energy.numerical_damping_joules < last_damping);
        near(state.energy.dissipated_joules, 0.0, 0.0);
        near(state.energy.balance_residual_joules(), 0.0, 1e-11);
        last_error = error;
        last_damping = state.energy.numerical_damping_joules;
    }
}

#[test]
fn dc_initialization_preserves_steady_storage() {
    let branches = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 2.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 1.0 }),
        branch(3, 2, 0, Element::Capacitor { farads: 3.0 }),
    ];
    let dc = dc::solve(&branches).unwrap();
    near(dc.branches[2].stored_joules, 6.0, 1e-12);
    let mut kernel = TransientCircuit::new(branches, None).unwrap();
    run(&mut kernel, 1.0, 10);
    near(kernel.accepted().energy.stored_joules, 6.0, 1e-12);
    near(kernel.accepted().energy.source_supplied_joules, 0.0, 1e-12);
}

#[test]
fn inconsistent_missing_wrong_kind_and_nonfinite_initial_storage_are_rejected() {
    let branches = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 2.0 }),
        branch(2, 1, 0, Element::Capacitor { farads: 1.0 }),
    ];
    for initial in [
        InitialConditions::new(),
        InitialConditions::from([(Id([1, 2]), InitialStorage::ChargeCoulombs(1.0))]),
        InitialConditions::from([(Id([1, 2]), InitialStorage::FluxWebers(1.0))]),
        InitialConditions::from([(Id([1, 2]), InitialStorage::ChargeCoulombs(f64::NAN))]),
    ] {
        assert!(TransientCircuit::new(branches.clone(), Some(&initial)).is_err());
    }
}

#[test]
fn discarded_and_failed_candidates_leave_time_storage_and_observations_unchanged() {
    let mut kernel = rc(0.0, 1.0);
    let before = kernel.accepted().clone();
    let trial = kernel.trial(0.1).unwrap();
    assert_ne!(trial, before);
    assert_eq!(kernel.accepted(), &before);
    for end in [0.0, -1.0, f64::NAN, f64::INFINITY, f64::from_bits(1)] {
        assert!(kernel.step(end).is_err());
        assert_eq!(kernel.accepted(), &before);
    }
    kernel.step(0.1).unwrap();
    assert_eq!(kernel.accepted(), &trial);
}

#[test]
fn partial_requests_deduplicate_and_resume_without_double_energy_commit() {
    let mut session = CircuitSession::new(revision(0), rc(0.0, 1.0), 0.01).unwrap();
    let cancel = AtomicBool::new(false);
    let req = request(1, 0.0, 0.1, 2);
    let result = session.advance(req, &cancel).unwrap();
    assert_eq!(result.diagnostic.status, Status::Partial);
    near(result.end, 0.02, 1e-15);
    let accepted = session.accepted().clone();
    near(session.advance(req, &cancel).unwrap().end, result.end, 0.0);
    assert_eq!(session.accepted(), &accepted);
    let mut changed = req;
    changed.max_steps = 3;
    assert!(session.advance(changed, &cancel).is_err());
    let result = session
        .advance(request(2, 0.02, 0.1, 100), &cancel)
        .unwrap();
    assert_eq!(result.diagnostic.status, Status::Ready);
    assert!(session.advance(req, &cancel).is_err());
    near(
        session.accepted().energy.balance_residual_joules(),
        0.0,
        1e-12,
    );
}

#[test]
fn cancellation_deadline_pause_and_revision_conflicts_preserve_state() {
    let mut session = CircuitSession::new(revision(0), rc(0.0, 1.0), 0.01).unwrap();
    let before = session.accepted().clone();
    let cancelled = session
        .advance(request(1, 0.0, 0.1, 20), &AtomicBool::new(true))
        .unwrap();
    assert_eq!(cancelled.diagnostic.status, Status::Partial);
    assert_eq!(session.accepted(), &before);
    let mut deadline = request(2, 0.0, 0.1, 20);
    deadline.budget_nanos = 1;
    assert_eq!(
        session
            .advance(deadline, &AtomicBool::new(false))
            .unwrap()
            .diagnostic
            .status,
        Status::Partial
    );
    assert_eq!(session.accepted(), &before);
    let mut wrong = request(3, 0.0, 0.1, 20);
    wrong.revision.parameters = 2;
    assert!(session.advance(wrong, &AtomicBool::new(false)).is_err());
    assert!(
        session
            .advance(request(3, 0.1, 0.2, 20), &AtomicBool::new(false))
            .is_err()
    );
    let paused = session
        .advance(request(3, 0.0, 0.0, 20), &AtomicBool::new(false))
        .unwrap();
    assert_eq!(paused.diagnostic.status, Status::Ready);
    assert_eq!(session.accepted(), &before);
}

#[test]
fn dynamic_charge_jacobians_and_energy_match_constitutive_derivatives() {
    for element in [
        Element::Capacitor { farads: 2.0 },
        Element::Inductor { henries: 3.0 },
    ] {
        let values = [2.0, 1.0, 0.5];
        let base = element.evaluate(&values, 0.0).unwrap();
        for column in 0..3 {
            let mut plus = values;
            let mut minus = values;
            plus[column] += 1e-5;
            minus[column] -= 1e-5;
            let p = element.evaluate(&plus, 0.0).unwrap();
            let m = element.evaluate(&minus, 0.0).unwrap();
            for row in 0..3 {
                near(
                    (p.charge[row] - m.charge[row]) / 2e-5,
                    base.charge_jacobian[row * 3 + column],
                    1e-8,
                );
                near(
                    (p.residual[row] - m.residual[row]) / 2e-5,
                    base.residual_jacobian[row * 3 + column],
                    1e-8,
                );
            }
        }
        near(base.dissipated_watts, 0.0, 0.0);
    }
}

#[test]
fn checkpoint_resumes_exactly_and_prior_sequences_cannot_replay() {
    let cancel = AtomicBool::new(false);
    let mut original = CircuitSession::new(revision(0), rc(0.0, 1.0), 0.01).unwrap();
    let first = request(1, 0.0, 1.0, 25);
    let partial = original.advance(first, &cancel).unwrap();
    assert_eq!(partial.diagnostic.status, Status::Partial);
    let bytes = original.snapshot();
    let mut restored = CircuitSession::from_snapshot(&bytes).unwrap();
    assert_eq!(original.accepted(), restored.accepted());
    assert_eq!(bytes, restored.snapshot());
    assert!(restored.advance(first, &cancel).is_err());
    assert_eq!(bytes, restored.snapshot());
    let next = request(2, partial.end, 1.0, 200);
    original.advance(next, &cancel).unwrap();
    restored.advance(next, &cancel).unwrap();
    assert_eq!(original.accepted(), restored.accepted());
    assert_eq!(original.snapshot(), restored.snapshot());
}

#[test]
fn restore_rejects_corruption_atomically_and_cannot_rewind_live_state() {
    let cancel = AtomicBool::new(false);
    let mut source = CircuitSession::new(revision(0), rc(0.0, 1.0), 0.01).unwrap();
    source.advance(request(1, 0.0, 0.1, 20), &cancel).unwrap();
    let bytes = source.snapshot();
    let mut target = CircuitSession::new(revision(0), rc(0.0, 1.0), 0.01).unwrap();
    let pristine = target.snapshot();
    for length in 0..bytes.len() {
        assert!(target.restore(&bytes[..length]).is_err());
        assert_eq!(target.snapshot(), pristine);
    }
    for (offset, value) in [(0, 99_u32), (4, 99_u32), (8, 99_u32)] {
        let mut bad = bytes.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(target.restore(&bad).is_err());
        assert_eq!(target.snapshot(), pristine);
    }
    let metadata_end = 8 + em_protocol::MOD_VERSION.len();
    for (offset, value) in [
        (metadata_end + 60, f64::NAN),
        (metadata_end + 68, -1.0),
        (metadata_end + 76, 99.0),
        (metadata_end + 220, 5.0),
    ] {
        let mut bad = bytes.clone();
        bad[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
        assert!(target.restore(&bad).is_err());
        assert_eq!(target.snapshot(), pristine);
    }
    let mut trailing = bytes.clone();
    trailing.push(0);
    assert!(target.restore(&trailing).is_err());
    let mut wrong = bytes.clone();
    wrong[metadata_end..metadata_end + 8].copy_from_slice(&99_u64.to_le_bytes());
    assert!(target.restore(&wrong).is_err());
    target.restore(&bytes).unwrap();
    assert_eq!(target.accepted(), source.accepted());
    assert!(target.restore(&pristine).is_err());
    assert_eq!(target.accepted(), source.accepted());
}

#[test]
fn checkpoint_preserves_lc_flux_charge_and_source_clamped_capacitor() {
    let kernel = TransientCircuit::new(
        vec![
            branch(1, 1, 0, Element::Capacitor { farads: 1.0 }),
            branch(2, 1, 0, Element::Inductor { henries: 1.0 }),
        ],
        Some(&InitialConditions::from([
            (Id([1, 1]), InitialStorage::ChargeCoulombs(1.0)),
            (Id([1, 2]), InitialStorage::FluxWebers(0.0)),
        ])),
    )
    .unwrap();
    let mut session = CircuitSession::new(revision(0), kernel, 0.01).unwrap();
    let cancel = AtomicBool::new(false);
    session.advance(request(1, 0.0, 0.5, 100), &cancel).unwrap();
    let mut restored = CircuitSession::from_snapshot(&session.snapshot()).unwrap();
    session.advance(request(2, 0.5, 1.0, 100), &cancel).unwrap();
    restored
        .advance(request(2, 0.5, 1.0, 100), &cancel)
        .unwrap();
    assert_eq!(session.accepted(), restored.accepted());
    // Restore a valid operating point directly to avoid projecting through singular
    // specified-storage equations.
    let kernel = TransientCircuit::new(
        vec![
            branch(1, 1, 0, Element::DcVoltageSource { volts: 2.0 }),
            branch(2, 1, 0, Element::Capacitor { farads: 1.0 }),
        ],
        None,
    )
    .unwrap();
    let original = CircuitSession::new(revision(0), kernel, 0.01).unwrap();
    let restored = CircuitSession::from_snapshot(&original.snapshot()).unwrap();
    assert_eq!(original.accepted(), restored.accepted());
}

fn accuracy(tolerance: f64) -> Accuracy {
    Accuracy {
        relative: tolerance,
        voltage: tolerance,
        current: tolerance,
        charge: tolerance,
        flux: tolerance,
        energy: tolerance,
        minimum_step: 1e-8,
    }
}

fn adaptive_rc() -> TransientCircuit {
    // Both RC loads share an ideal source and an analytic response. Each has its own storage state
    // in the error estimate.
    let branches = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 1.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 1.0 }),
        branch(3, 2, 0, Element::Capacitor { farads: 1.0 }),
        branch(4, 1, 3, Element::Resistor { ohms: 1.0 }),
        branch(5, 3, 0, Element::Capacitor { farads: 1.0 }),
    ];
    let initial = InitialConditions::from([
        (Id([1, 3]), InitialStorage::ChargeCoulombs(0.0)),
        (Id([1, 5]), InitialStorage::ChargeCoulombs(0.0)),
    ]);
    TransientCircuit::new(branches, Some(&initial)).unwrap()
}

#[test]
fn adaptive_rejections_do_not_commit_and_minimum_step_failure_is_explicit() {
    let cancel = AtomicBool::new(false);
    let mut session =
        CircuitSession::new_adaptive(revision(0), adaptive_rc(), 1.0, accuracy(1e-5)).unwrap();
    let before = session.accepted().clone();
    let result = session.advance(request(1, 0.0, 1.0, 1), &cancel).unwrap();
    assert_eq!(result.diagnostic.status, Status::Partial);
    assert_eq!(session.accepted(), &before);
    let next = request(2, 0.0, 1.0, 10000);
    assert_eq!(
        session.advance(next, &cancel).unwrap().diagnostic.status,
        Status::Ready
    );
    near(
        session.accepted().operating_point.branches[2].voltage,
        1.0 - (-1.0_f64).exp(),
        0.002,
    );
    near(
        session.accepted().energy.balance_residual_joules(),
        0.0,
        1e-10,
    );
    let mut config = accuracy(1e-12);
    config.minimum_step = 0.1;
    let mut limited = CircuitSession::new_adaptive(revision(0), adaptive_rc(), 0.1, config).unwrap();
    let before = limited.accepted().clone();
    let result = limited.advance(request(1, 0.0, 1.0, 100), &cancel).unwrap();
    assert!(result.diagnostic.reason.contains("minimum step"));
    assert_eq!(limited.accepted(), &before);
}

#[test]
fn adaptive_tighter_tolerances_improve_physical_error_and_checkpoint_is_exact() {
    let cancel = AtomicBool::new(false);
    let mut previous = f64::INFINITY;
    for tolerance in [1e-3, 1e-5, 1e-7] {
        let mut session =
            CircuitSession::new_adaptive(revision(0), adaptive_rc(), 1.0, accuracy(tolerance))
                .unwrap();
        let partial = session.advance(request(1, 0.0, 1.0, 10), &cancel).unwrap();
        let mut restored = CircuitSession::from_snapshot(&session.snapshot()).unwrap();
        assert_eq!(session.snapshot(), restored.snapshot());
        let next = request(2, partial.end, 1.0, 100000);
        assert_eq!(
            session.advance(next, &cancel).unwrap().diagnostic.status,
            Status::Ready
        );
        restored.advance(next, &cancel).unwrap();
        assert_eq!(session.accepted(), restored.accepted());
        let error = (session.accepted().operating_point.branches[2].voltage
            - (1.0 - (-1.0_f64).exp()))
        .abs();
        assert!(error < previous * 0.2);
        previous = error;
        near(
            session.accepted().energy.balance_residual_joules(),
            0.0,
            1e-10,
        );
    }
}
