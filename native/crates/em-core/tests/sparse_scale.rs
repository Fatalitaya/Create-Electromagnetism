use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{
        ac,
        dc::{self, DcBranch},
        linear,
        sparse::Matrix,
        transient::TransientCircuit,
    },
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision, Status};
use std::{sync::atomic::AtomicBool, time::Instant};

fn branch(id: u64, a: u64, b: u64, element: Element) -> DcBranch {
    let port = |n| PortId {
        device: DeviceId {
            instance: Id([0, n]),
            generation: 1,
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
fn close(actual: f64, expected: f64, tolerance: f64) {
    assert!(
        (actual - expected).abs() <= tolerance,
        "{actual} != {expected}"
    );
}
fn revision(sequence: u64) -> Revision {
    Revision {
        session: Id([2, 1]),
        topology: 1,
        parameters: 1,
        sequence,
    }
}

#[test]
fn sparse_matches_independent_dense_elimination_with_row_pivoting() {
    let n = 31;
    let mut dense = vec![0.0; n * n];
    let mut sparse = Matrix::new(n).unwrap();
    // Permuted, non-symmetric, strictly diagonally dominant before row permutation.
    for row in 0..n {
        for (col, v) in [(row, 5.0), ((row + 1) % n, -1.5), ((row + 7) % n, 0.5)] {
            let r = (row * 7) % n;
            dense[r * n + col] = v;
            sparse.add(r, col, v).unwrap();
        }
    }
    let rhs: Vec<_> = (0..n).map(|r| (r as f64).sin()).collect();
    let reference = linear::solve(&dense, &rhs).unwrap();
    for (actual, expected) in sparse.solve(&rhs).unwrap().iter().zip(reference) {
        close(*actual, expected, 1e-13);
    }
}

#[test]
fn thousand_branch_chain_matches_series_resistance() {
    let mut branches = vec![branch(
        0,
        1023,
        0,
        Element::DcVoltageSource { volts: 1023.0 },
    )];
    for n in 1..1024 {
        branches.push(branch(n, n, n - 1, Element::Resistor { ohms: 1.0 }));
    }
    let start = Instant::now();
    let point = dc::solve(&branches).unwrap();
    eprintln!("chain branches=1024 DC elapsed={:?}", start.elapsed());
    for b in &point.branches[1..] {
        close(b.current, 1.0, 1e-9);
        close(b.voltage, 1.0, 1e-9);
    }
    close(point.power_balance_watts, 0.0, 1e-7);
}

#[test]
fn thousand_branch_grid_matches_linear_potential_and_kcl() {
    let mut branches = Vec::new();
    let mut push = |a, b, e| branches.push(branch(branches.len() as u64, a, b, e));
    for r in 0..16 {
        for c in 0..32 {
            let node = r * 32 + c;
            if c > 0 {
                push(node, node - 1, Element::Resistor { ohms: 1.0 });
            }
            if r > 0 {
                push(node, node - 32, Element::Resistor { ohms: 1.0 });
            }
        }
        push(
            r * 32 + 31,
            r * 32,
            Element::DcVoltageSource { volts: 31.0 },
        );
    }
    for c in 0..32 {
        push(32 + c, c, Element::Resistor { ohms: 2.0 });
    }
    assert_eq!(branches.len(), 1024);
    let start = Instant::now();
    let point = dc::solve(&branches).unwrap();
    eprintln!("grid branches=1024 DC elapsed={:?}", start.elapsed());
    assert_eq!(point.references.len(), 1);
    let mut kcl = std::collections::HashMap::<PortId, f64>::new();
    for (b, p) in branches.iter().zip(&point.branches) {
        let a = b.positive.device.instance.0[1] % 32;
        let z = b.negative.device.instance.0[1] % 32;
        close(p.voltage, a as f64 - z as f64, 1e-8);
        if let Element::Resistor { ohms } = b.element {
            close(p.current, (a as f64 - z as f64) / ohms, 1e-9);
        }
        *kcl.entry(b.positive).or_default() += p.current;
        *kcl.entry(b.negative).or_default() -= p.current;
    }
    for current in kcl.values() {
        close(*current, 0.0, 1e-10);
    }
    close(point.power_balance_watts, 0.0, 1e-7);
}

fn rc_islands() -> Vec<DcBranch> {
    let mut branches = Vec::new();
    for i in 0..256 {
        let n = 3 * i;
        for (a, b, e) in [
            (n + 1, n, Element::DcVoltageSource { volts: 1.0 }),
            (n + 1, n + 2, Element::Resistor { ohms: 1.0 }),
            (n + 2, n, Element::Capacitor { farads: 1.0 }),
            (n + 2, n, Element::Resistor { ohms: 1.0 }),
        ] {
            branches.push(branch(branches.len() as u64, a, b, e));
        }
    }
    branches
}

#[test]
fn thousand_branch_ac_islands_match_analytic_rc_transfer() {
    let branches = rc_islands();
    let sources: Vec<_> = branches
        .iter()
        .step_by(4)
        .map(|b| {
            (
                b.id,
                ac::Phasor {
                    real: 1.0,
                    imaginary: 0.0,
                },
            )
        })
        .collect();
    let start = Instant::now();
    let result = ac::solve(&branches, 1.0 / std::f64::consts::TAU, &sources).unwrap();
    eprintln!("islands=256 branches=1024 AC elapsed={:?}", start.elapsed());
    // Vc/Vs = 1 / (2 + j omega), with omega = 1 rad/s.
    for b in result.branches.iter().skip(2).step_by(4) {
        close(b.voltage.real, 0.4, 1e-12);
        close(b.voltage.imaginary, -0.2, 1e-12);
    }
}

#[test]
fn islands_share_partial_acceptance_and_snapshot_boundary() {
    let branches = rc_islands();
    let initial: InitialConditions = branches
        .iter()
        .skip(2)
        .step_by(4)
        .map(|b| (b.id, InitialStorage::ChargeCoulombs(0.0)))
        .collect();
    let kernel = TransientCircuit::new(branches.clone(), Some(&initial)).unwrap();
    let mut session = CircuitSession::new(revision(0), kernel, 0.001).unwrap();
    let request = Advance {
        revision: revision(1),
        accepted_start: 0.0,
        requested_end: 0.05,
        max_steps: 2,
        budget_nanos: 30_000_000_000,
    };
    let accepted = session.advance(request, &AtomicBool::new(false)).unwrap();
    assert_eq!(accepted.diagnostic.status, Status::Partial);
    assert_eq!(accepted.work.attempted_steps, 2);
    assert_eq!(accepted.work.accepted_steps, 2);
    assert_eq!(accepted.work.rejected_steps, 0);
    assert_eq!(accepted.work.discarded_steps, 0);
    close(accepted.end, 0.002, 1e-15);
    for b in session
        .accepted()
        .operating_point
        .branches
        .iter()
        .skip(2)
        .step_by(4)
    {
        close(b.voltage, 0.5 * (1.0 - 1.002_f64.powi(-2)), 1e-13);
        close(b.voltage, 0.5 * (1.0 - (-2.0 * accepted.end).exp()), 5e-6);
    }
    close(
        session.accepted().energy.balance_residual_joules(),
        0.0,
        1e-11,
    );
    let saved = session.snapshot();
    assert_eq!(
        CircuitSession::from_snapshot(&saved).unwrap().snapshot(),
        saved
    );
    assert_eq!(
        session
            .advance(request, &AtomicBool::new(false))
            .unwrap()
            .end,
        accepted.end
    );
    assert_eq!(session.snapshot(), saved);
    let mut invalid = branches;
    // A contradictory source in the last island must not publish any other island's result.
    invalid.push(branch(
        1024,
        766,
        765,
        Element::DcVoltageSource { volts: 2.0 },
    ));
    assert!(
        session
            .apply_topology(
                Revision {
                    topology: 2,
                    ..revision(2)
                },
                accepted.end,
                invalid
            )
            .is_err()
    );
    assert_eq!(session.snapshot(), saved);
}

#[test]
fn thousand_branch_nonlinear_islands_match_scalar_bisection() {
    let mut branches = rc_islands();
    for chunk in branches.chunks_mut(4) {
        chunk[0].element = Element::DcVoltageSource { volts: 0.3 };
        chunk[1].element = Element::Resistor { ohms: 1000.0 };
        chunk[2].element = Element::Diode {
            saturation_amperes: 1e-9,
            thermal_volts: 0.026,
            junction_farads: 0.0,
        };
        chunk[3].element = Element::Resistor { ohms: 1000.0 };
    }
    let (mut low, mut high) = (0.0_f64, 0.3_f64);
    for _ in 0..100 {
        let v = (low + high) * 0.5;
        if (0.3 - v) / 1000.0 > v / 1000.0 + 1e-9 * (v / 0.026).exp_m1() {
            low = v;
        } else {
            high = v;
        }
    }
    let start = Instant::now();
    let point = dc::solve(&branches).unwrap();
    eprintln!(
        "islands=256 branches=1024 nonlinear DC elapsed={:?}",
        start.elapsed()
    );
    for b in point.branches.iter().skip(2).step_by(4) {
        close(b.voltage, (low + high) * 0.5, 1e-10);
    }
    close(point.power_balance_watts, 0.0, 1e-10);
}

#[test]
fn excessive_topology_rejects_before_matrix_allocation() {
    let b = branch(0, 0, 1, Element::Resistor { ohms: 1.0 });
    let err = dc::solve(&vec![b; em_protocol::wire::MAX_BRANCHES + 1]).unwrap_err();
    assert!(err.reason.contains("resource limit"));
}

#[test]
fn adaptive_work_counts_include_rejection_and_cached_requests() {
    use em_core::solver::transient::Accuracy;
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
    let kernel = TransientCircuit::new(branches, Some(&initial)).unwrap();
    let mut session = CircuitSession::new_adaptive(
        revision(0),
        kernel,
        0.1,
        Accuracy {
            relative: 1e-6,
            voltage: 1e-12,
            current: 1e-12,
            charge: 1e-12,
            flux: 1e-12,
            energy: 1e-12,
            minimum_step: 1e-12,
        },
    )
    .unwrap();
    let request = Advance {
        revision: revision(1),
        accepted_start: 0.0,
        requested_end: 0.1,
        max_steps: 1,
        budget_nanos: 10_000_000_000,
    };
    let result = session.advance(request, &AtomicBool::new(false)).unwrap();
    assert_eq!(result.end, 0.0);
    assert_eq!(result.work.attempted_steps, 1);
    assert_eq!(result.work.rejected_steps, 1);
    assert_eq!(result.work.accepted_steps, 0);
    assert_eq!(
        session
            .advance(request, &AtomicBool::new(false))
            .unwrap()
            .work,
        result.work
    );
    let cancelled = session
        .advance(
            Advance {
                revision: revision(2),
                ..request
            },
            &AtomicBool::new(true),
        )
        .unwrap();
    assert_eq!(
        cancelled.work,
        em_protocol::contract::AdvanceWork::default()
    );
}
