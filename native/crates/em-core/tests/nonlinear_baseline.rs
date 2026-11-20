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
fn diode(capacitance: f64) -> Element {
    Element::Diode {
        saturation_amperes: 1e-12,
        thermal_volts: 0.025,
        junction_farads: capacitance,
    }
}
fn near(a: f64, b: f64, t: f64) {
    assert!((a - b).abs() <= t, "{a} != {b}, tolerance {t}");
}
fn series(source: Element, cap: f64) -> Vec<DcBranch> {
    vec![
        branch(1, 1, 0, source),
        branch(2, 1, 2, Element::Resistor { ohms: 1000.0 }),
        branch(3, 2, 0, diode(cap)),
    ]
}
fn revision(sequence: u64) -> Revision {
    Revision {
        session: Id([4, 5]),
        topology: 1,
        parameters: 1,
        sequence,
    }
}
fn request(seq: u64, start: f64, end: f64) -> Advance {
    Advance {
        revision: revision(seq),
        accepted_start: start,
        requested_end: end,
        max_steps: 100000,
        budget_nanos: 10_000_000_000,
    }
}

#[test]
fn diode_operating_point_matches_closed_form_constructed_reference_and_reverse_leakage() {
    for expected_voltage in [-2.0, 0.0, 0.3, 0.5, 0.7] {
        let expected_current = 1e-12 * (expected_voltage / 0.025_f64).exp_m1();
        let source = expected_voltage + 1000.0 * expected_current;
        let result = dc::solve(&series(Element::DcVoltageSource { volts: source }, 0.0)).unwrap();
        near(result.branches[2].voltage, expected_voltage, 1e-8);
        near(result.branches[2].current, expected_current, 1e-10);
        near(result.power_balance_watts, 0.0, 1e-7);
        assert!(result.branches[2].dissipated_watts >= 0.0);
    }
}

#[test]
fn junction_jacobians_match_finite_differences_and_charge_energy_are_consistent() {
    let model = diode(2e-6);
    let values = [0.4, 0.0, 0.001];
    let base = model.evaluate(&values, 0.0).unwrap();
    near(base.charge[2], -8e-7, 1e-20);
    near(base.stored_joules, 1.6e-7, 1e-20);
    near(base.residual[0] + base.residual[1], 0.0, 0.0);
    for col in 0..3 {
        let mut p = values;
        let mut m = values;
        p[col] += 1e-7;
        m[col] -= 1e-7;
        let plus = model.evaluate(&p, 0.0).unwrap();
        let minus = model.evaluate(&m, 0.0).unwrap();
        for row in 0..3 {
            near(
                (plus.residual[row] - minus.residual[row]) / 2e-7,
                base.residual_jacobian[row * 3 + col],
                1e-8,
            );
            near(
                (plus.charge[row] - minus.charge[row]) / 2e-7,
                base.charge_jacobian[row * 3 + col],
                1e-12,
            );
        }
    }
    assert!(model.evaluate(&[1000.0, 0.0, 0.0], 0.0).is_err());
}

#[test]
fn sine_rectifier_obeys_polarity_and_energy_on_one_time_axis() {
    let source = Element::SineVoltageSource {
        offset: 0.0,
        peak: 1.0,
        hertz: 10.0,
        phase: 0.0,
    };
    let mut kernel = TransientCircuit::new(series(source, 0.0), None).unwrap();
    for step in 1..=200 {
        let t = f64::from(step) * 0.0005;
        kernel.step(t).unwrap();
        let state = kernel.accepted();
        near(
            state.operating_point.branches[0].voltage,
            (std::f64::consts::TAU * 10.0 * t).sin(),
            1e-9,
        );
        if step == 50 {
            assert!(state.operating_point.branches[2].current > 0.0004);
        }
        if step == 150 {
            near(state.operating_point.branches[2].current, -1e-12, 1e-13);
        }
        near(state.energy.balance_residual_joules(), 0.0, 1e-10);
    }
}

#[test]
fn junction_transient_is_transactional_and_checkpoint_continuation_is_exact() {
    let initial = InitialConditions::from([(Id([1, 3]), InitialStorage::ChargeCoulombs(0.0))]);
    let kernel = TransientCircuit::new(
        series(Element::DcVoltageSource { volts: 1.0 }, 1e-6),
        Some(&initial),
    )
    .unwrap();
    let controls = Accuracy {
        relative: 1e-4,
        voltage: 1e-6,
        current: 1e-9,
        charge: 1e-12,
        flux: 1e-12,
        energy: 1e-11,
        minimum_step: 1e-10,
    };
    let mut session = CircuitSession::new_adaptive(revision(0), kernel, 0.001, controls).unwrap();
    let cancel = AtomicBool::new(false);
    let first = session.advance(request(1, 0.0, 0.002), &cancel).unwrap();
    assert_eq!(
        first.diagnostic.status,
        Status::Ready,
        "{}",
        first.diagnostic.reason
    );
    let mut restored = CircuitSession::from_snapshot(&session.snapshot()).unwrap();
    let next = request(2, 0.002, 0.004);
    assert_eq!(
        session.advance(next, &cancel).unwrap().diagnostic.status,
        Status::Ready
    );
    restored.advance(next, &cancel).unwrap();
    assert_eq!(session.accepted(), restored.accepted());
    near(
        session.accepted().energy.balance_residual_joules(),
        0.0,
        1e-10,
    );
    assert!(session.accepted().energy.stored_joules > 0.0);
}

#[test]
fn impossible_exponential_candidate_is_rejected_without_committing() {
    let source = Element::SineVoltageSource {
        offset: 0.0,
        peak: 1000.0,
        hertz: 1.0,
        phase: 0.0,
    };
    let mut kernel = TransientCircuit::new(
        vec![branch(1, 1, 0, source), branch(2, 1, 0, diode(0.0))],
        None,
    )
    .unwrap();
    let before = kernel.accepted().clone();
    assert!(kernel.step(0.25).is_err());
    assert_eq!(&before, kernel.accepted());
}

#[test]
fn source_frequency_caps_steps_to_prevent_aliasing_and_snapshots_keep_phase() {
    let kernel = TransientCircuit::new(
        vec![
            branch(
                1,
                1,
                0,
                Element::SineVoltageSource {
                    offset: 0.0,
                    peak: 1.0,
                    hertz: 100.0,
                    phase: 0.0,
                },
            ),
            branch(2, 1, 0, Element::Resistor { ohms: 1.0 }),
        ],
        None,
    )
    .unwrap();
    let mut session = CircuitSession::new(revision(0), kernel, 1.0).unwrap();
    let cancel = AtomicBool::new(false);
    let result = session.advance(request(1, 0.0, 0.01), &cancel).unwrap();
    assert_eq!(result.diagnostic.status, Status::Ready);
    near(session.accepted().energy.dissipated_joules, 0.005, 1e-12);
    let mut restored = CircuitSession::from_snapshot(&session.snapshot()).unwrap();
    let next = request(2, 0.01, 0.0125);
    session.advance(next, &cancel).unwrap();
    restored.advance(next, &cancel).unwrap();
    assert_eq!(session.accepted(), restored.accepted());
    near(
        session.accepted().operating_point.branches[0].voltage,
        1.0,
        1e-12,
    );
}

#[test]
fn diode_parameter_domains_are_explicit_and_rejected_before_solving() {
    for (is, vt, c) in [
        (0.0, 0.025, 0.0),
        (1e-12, 0.0, 0.0),
        (1e-12, 0.025, -1.0),
        (f64::NAN, 0.025, 0.0),
    ] {
        let element = Element::Diode {
            saturation_amperes: is,
            thermal_volts: vt,
            junction_farads: c,
        };
        assert!(element.evaluate(&[0.0; 3], 0.0).is_err());
    }
}

#[test]
fn checkpoint_rejects_reverse_leakage_mismatch_using_ampere_tolerance() {
    let kernel =
        TransientCircuit::new(series(Element::DcVoltageSource { volts: -2.0 }, 0.0), None).unwrap();
    let session = CircuitSession::new(revision(0), kernel, 0.001).unwrap();
    let mut bytes = session.snapshot();
    assert!(CircuitSession::from_snapshot(&bytes).is_ok());
    // Changing Is on the last branch preserves KCL and storage but makes snapshot leakage
    // inconsistent with the accepted operating point.
    let observation_bytes = 8 + session.accepted().operating_point.branches.len() * 20 * 8 + 4;
    // The event cursor and count occupy 12 bytes, followed by a four-byte optional interval
    // marker.
    let offset = bytes.len() - observation_bytes - 16 - 6 * 8;
    bytes[offset..offset + 8].copy_from_slice(&1e-10_f64.to_le_bytes());
    let error = CircuitSession::from_snapshot(&bytes).err().unwrap();
    assert!(
        error.reason.contains("algebraic branch constraint"),
        "{}",
        error.reason
    );
}

#[test]
fn algebraic_sine_load_energy_error_is_controlled_even_when_endpoints_match() {
    let end = 0.17;
    let exact = end / 2.0 - (4.0 * std::f64::consts::PI * end).sin() / (8.0 * std::f64::consts::PI);
    let mut previous = f64::INFINITY;
    for energy in [1e-6, 1e-9] {
        let kernel = TransientCircuit::new(
            vec![
                branch(
                    1,
                    1,
                    0,
                    Element::SineVoltageSource {
                        offset: 0.0,
                        peak: 1.0,
                        hertz: 1.0,
                        phase: 0.0,
                    },
                ),
                branch(2, 1, 0, Element::Resistor { ohms: 1.0 }),
            ],
            None,
        )
        .unwrap();
        let controls = Accuracy {
            relative: 1e-9,
            voltage: 1.0,
            current: 1.0,
            charge: 1.0,
            flux: 1.0,
            energy,
            minimum_step: 1e-10,
        };
        let mut session = CircuitSession::new_adaptive(revision(0), kernel, 1.0, controls).unwrap();
        let result = session
            .advance(request(1, 0.0, end), &AtomicBool::new(false))
            .unwrap();
        assert_eq!(
            result.diagnostic.status,
            Status::Ready,
            "{}",
            result.diagnostic.reason
        );
        let error = (session.accepted().energy.dissipated_joules - exact).abs();
        assert!(error < previous * 0.1, "{error} vs {previous}");
        previous = error;
    }
}
