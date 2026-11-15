use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{
        ac::{self, Phasor},
        dc::DcBranch,
        transient::TransientCircuit,
    },
};
use em_protocol::contract::{DeviceId, Id, PortId, Revision};

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
fn near(a: f64, b: f64) {
    assert!((a - b).abs() < 1e-9, "{a} != {b}");
}
fn source() -> [(Id, Phasor); 1] {
    [(
        Id([1, 1]),
        Phasor {
            real: 1.0,
            imaginary: 0.0,
        },
    )]
}
fn rc() -> Vec<DcBranch> {
    vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 1.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 1000.0 }),
        branch(3, 2, 0, Element::Capacitor { farads: 1e-6 }),
    ]
}

#[test]
fn rc_frequency_response_matches_analytic_complex_transfer_and_kcl() {
    for omega in [0.0, 1.0, 100.0, 1000.0, 100000.0] {
        let result = ac::solve(&rc(), omega / std::f64::consts::TAU, &source()).unwrap();
        let x = omega * 0.001;
        let cap = &result.branches[2];
        near(cap.voltage.real, 1.0 / (1.0 + x * x));
        near(cap.voltage.imaginary, -x / (1.0 + x * x));
        near(cap.current.real, -omega * 1e-6 * cap.voltage.imaginary);
        near(cap.current.imaginary, omega * 1e-6 * cap.voltage.real);
        near(result.branches[0].current.real + cap.current.real, 0.0);
        near(result.branches[1].current.imaginary, cap.current.imaginary);
    }
}

#[test]
fn rlc_resonance_and_complex_excitation_follow_peak_convention() {
    let circuit = vec![
        branch(1, 1, 0, Element::DcVoltageSource { volts: 0.0 }),
        branch(2, 1, 2, Element::Resistor { ohms: 10.0 }),
        branch(3, 2, 3, Element::Inductor { henries: 0.01 }),
        branch(4, 3, 0, Element::Capacitor { farads: 1e-6 }),
    ];
    let result = ac::solve(
        &circuit,
        10000.0 / std::f64::consts::TAU,
        &[(
            Id([1, 1]),
            Phasor {
                real: 0.0,
                imaginary: 2.0,
            },
        )],
    )
    .unwrap();
    near(result.branches[1].current.real, 0.0);
    near(result.branches[1].current.imaginary, 0.2);
    near(result.branches[2].voltage.real, -20.0);
    near(result.branches[3].voltage.real, 20.0);
}

#[test]
fn biased_diode_uses_same_differential_conductance_and_junction_charge() {
    let v: f64 = 0.5;
    let current = 1e-12 * (v / 0.025).exp_m1();
    let conductance = 1e-12 * (v / 0.025).exp() / 0.025;
    let circuit = vec![
        branch(
            1,
            1,
            0,
            Element::DcVoltageSource {
                volts: v + 1000.0 * current,
            },
        ),
        branch(2, 1, 2, Element::Resistor { ohms: 1000.0 }),
        branch(
            3,
            2,
            0,
            Element::Diode {
                saturation_amperes: 1e-12,
                thermal_volts: 0.025,
                junction_farads: 1e-6,
            },
        ),
    ];
    let result = ac::solve(&circuit, 1000.0 / std::f64::consts::TAU, &source()).unwrap();
    let a = 1.0 + 1000.0 * conductance;
    near(result.branches[2].voltage.real, a / (a * a + 1.0));
    near(result.branches[2].voltage.imaginary, -1.0 / (a * a + 1.0));
}

#[test]
fn analysis_and_invalid_requests_leave_session_snapshot_unchanged() {
    let mut kernel = TransientCircuit::new(rc(), None).unwrap();
    kernel.step(0.01).unwrap();
    let session = CircuitSession::new(
        Revision {
            session: Id([3, 1]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        },
        kernel,
        0.001,
    )
    .unwrap();
    let before = session.snapshot();
    session.analyze_ac(100.0, &source()).unwrap();
    for f in [-1.0, f64::NAN, f64::INFINITY, f64::MAX] {
        assert!(session.analyze_ac(f, &source()).is_err());
    }
    assert!(
        session
            .analyze_ac(1.0, &[source()[0], source()[0]])
            .is_err()
    );
    assert!(
        session
            .analyze_ac(1.0, &[(Id([1, 2]), source()[0].1)])
            .is_err()
    );
    assert_eq!(before, session.snapshot());
    let mut sine = rc();
    sine[0].element = Element::SineVoltageSource {
        offset: 0.0,
        peak: 1.0,
        hertz: 50.0,
        phase: 0.0,
    };
    assert!(ac::solve(&sine, 1.0, &source()).is_err());
}
