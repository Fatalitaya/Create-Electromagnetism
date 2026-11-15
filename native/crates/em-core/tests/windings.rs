use em_core::{
    model::{DeviceModel, element::Element, windings::Windings},
    session::circuit::CircuitSession,
    solver::{ac, dc::DcBranch, transient::TransientCircuit},
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{DeviceId, Id, PortId, Revision};
fn port(n: u32) -> PortId {
    PortId {
        device: DeviceId {
            instance: Id([0, 1]),
            generation: 0,
        },
        terminal: n,
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
fn model() -> Windings {
    Windings {
        primary_henries: 2.0,
        secondary_henries: 2.0,
        mutual_henries: 1.0,
        primary_ohms: 0.1,
        secondary_ohms: 0.1,
    }
}
fn windings(m: Windings) -> DcBranch {
    DcBranch {
        additional_ports: vec![port(2), port(3)],
        ..branch(1, 0, 1, Element::Windings(m))
    }
}
fn near(a: f64, b: f64, tol: f64) {
    assert!((a - b).abs() < tol, "{a} != {b}");
}
fn circuit(m: Windings) -> Vec<DcBranch> {
    vec![
        windings(m),
        branch(2, 0, 1, Element::Resistor { ohms: 0.9 }),
        branch(3, 2, 3, Element::Resistor { ohms: 0.9 }),
    ]
}
#[test]
fn isolated_windings_decay_with_two_analytic_modes_and_conserve_energy() {
    let m = model();
    let (primary, secondary) = m.fluxes(1.0, 0.0);
    let init = InitialConditions::from([(
        Id([1, 1]),
        InitialStorage::WindingFluxes { primary, secondary },
    )]);
    let mut errors = vec![];
    for steps in [100, 200, 400] {
        let mut k = TransientCircuit::new(circuit(m), Some(&init)).unwrap();
        assert_eq!(k.accepted().operating_point.references.len(), 2);
        for j in 1..=steps {
            k.step(f64::from(j) / f64::from(steps)).unwrap();
        }
        let p = &k.accepted().operating_point.branches[0];
        let slow = (-1.0_f64 / 3.0).exp();
        let fast = (-1.0_f64).exp();
        errors.push((p.current - 0.5 * (slow + fast)).abs());
        near(p.local_values[4], 0.5 * (slow - fast), 0.001);
        near(p.local_values[4] + p.local_values[6], 0.0, 1e-12);
        near(k.accepted().energy.balance_residual_joules(), 0.0, 1e-11);
        assert!(k.accepted().energy.dissipated_joules > 0.5);
    }
    assert!(errors[1] < errors[0] * 0.51 && errors[2] < errors[1] * 0.51);
}
#[test]
fn magnetic_jacobians_and_loaded_ac_match_independent_impedance_equations() {
    let m = model();
    let e = Element::Windings(m);
    let x = [1.0, 0.2, 0.5, 0.4, -0.1, 0.0, 0.1];
    let evaluated = e.evaluate(&x, 0.0).unwrap();
    for col in 0..7 {
        let mut a = x;
        let mut b = x;
        a[col] += 1e-6;
        b[col] -= 1e-6;
        let a = e.evaluate(&a, 0.0).unwrap();
        let b = e.evaluate(&b, 0.0).unwrap();
        for row in 0..7 {
            near(
                (a.residual[row] - b.residual[row]) / 2e-6,
                evaluated.residual_jacobian[row * 7 + col],
                1e-9,
            );
            near(
                (a.charge[row] - b.charge[row]) / 2e-6,
                evaluated.charge_jacobian[row * 7 + col],
                1e-9,
            );
        }
    }
    let branches = vec![
        windings(m),
        branch(2, 0, 1, Element::DcVoltageSource { volts: 0.0 }),
        branch(3, 2, 3, Element::Resistor { ohms: 0.9 }),
    ];
    // At omega=1, Z1=.1+2j, Z2=1+2j; I1=Z2/(Z1*Z2+1).
    let response = ac::solve(
        &branches,
        1.0 / std::f64::consts::TAU,
        &[(
            Id([1, 2]),
            ac::Phasor {
                real: 1.0,
                imaginary: 0.0,
            },
        )],
    )
    .unwrap();
    let (dr, di) = (-2.9, 2.2);
    let magnitude = dr * dr + di * di;
    near(
        response.branches[0].current.real,
        (dr + 2.0 * di) / magnitude,
        1e-12,
    );
    near(
        response.branches[0].current.imaginary,
        (2.0 * dr - di) / magnitude,
        1e-12,
    );
    // I2=-j/determinant; load voltage=-.9 I2.
    near(
        response.branches[2].voltage.real,
        0.9 * di / magnitude,
        1e-12,
    );
    near(
        response.branches[2].voltage.imaginary,
        0.9 * dr / magnitude,
        1e-12,
    );
}
#[test]
fn flux_preserving_parameter_edit_restore_and_invalid_matrix_are_atomic() {
    let m = model();
    let init = InitialConditions::from([(
        Id([1, 1]),
        InitialStorage::WindingFluxes {
            primary: 2.0,
            secondary: 1.0,
        },
    )]);
    let r = Revision {
        session: Id([6, 1]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut s = CircuitSession::new(
        r,
        TransientCircuit::new(circuit(m), Some(&init)).unwrap(),
        0.001,
    )
    .unwrap();
    let mut changed = m;
    changed.primary_henries = 3.0;
    s.set_parameters(
        Revision {
            parameters: 2,
            sequence: 1,
            ..r
        },
        0.0,
        vec![(Id([1, 1]), Element::Windings(changed))],
    )
    .unwrap();
    let x = &s.accepted().operating_point.branches[0].local_values;
    let (a, b) = changed.fluxes(x[2], x[4]);
    near(a, 2.0, 1e-12);
    near(b, 1.0, 1e-12);
    near(s.accepted().energy.balance_residual_joules(), 0.0, 1e-12);
    assert!(s.accepted().energy.parameter_work_joules < 0.0);
    let bytes = s.snapshot();
    let restored = CircuitSession::from_snapshot(&bytes).unwrap();
    assert_eq!(bytes, restored.snapshot());
    changed.mutual_henries = 10.0;
    assert!(
        s.set_parameters(
            Revision {
                parameters: 3,
                sequence: 2,
                ..r
            },
            0.0,
            vec![(Id([1, 1]), Element::Windings(changed))]
        )
        .is_err()
    );
    assert_eq!(bytes, s.snapshot());
}
