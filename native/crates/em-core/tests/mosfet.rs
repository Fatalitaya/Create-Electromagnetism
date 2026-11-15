use em_core::{
    model::{DeviceModel, element::Element, mosfet::Mosfet},
    session::circuit::CircuitSession,
    solver::{
        ac,
        dc::{self, DcBranch},
        transient::TransientCircuit,
    },
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;

fn model(p_channel: bool) -> Mosfet {
    Mosfet {
        beta_amperes_per_volt_squared: 0.02,
        threshold_volts: 2.0,
        saturation_amperes: 1e-12,
        thermal_volts: 0.025,
        gate_source_farads: 1e-6,
        gate_drain_farads: 0.5e-6,
        p_channel,
    }
}
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
        additional_ports: Vec::new(),
        element,
    }
}
fn circuit(p_channel: bool, supply: f64, drive: f64) -> Vec<DcBranch> {
    let sign = if p_channel { -1.0 } else { 1.0 };
    vec![
        branch(
            1,
            3,
            0,
            Element::DcVoltageSource {
                volts: sign * supply,
            },
        ),
        branch(2, 3, 1, Element::Resistor { ohms: 100.0 }),
        branch(
            3,
            4,
            0,
            Element::DcVoltageSource {
                volts: sign * drive,
            },
        ),
        branch(4, 4, 2, Element::Resistor { ohms: 1000.0 }),
        DcBranch {
            additional_ports: vec![port(2)],
            ..branch(5, 1, 0, Element::Mosfet(model(p_channel)))
        },
    ]
}
fn close(a: f64, b: f64, tolerance: f64) {
    assert!((a - b).abs() <= tolerance, "{a} != {b}");
}
fn revision(parameters: u64, sequence: u64) -> Revision {
    Revision {
        session: Id([6, 1]),
        topology: 1,
        parameters,
        sequence,
    }
}
fn advance(s: &mut CircuitSession, sequence: u64, end: f64) {
    let r = s
        .advance(
            Advance {
                revision: revision(2, sequence),
                accepted_start: s.accepted().time_seconds,
                requested_end: end,
                max_steps: 10000,
                budget_nanos: 30_000_000_000,
            },
            &AtomicBool::new(false),
        )
        .unwrap();
    assert_eq!(r.end, end, "{}", r.diagnostic.reason);
}

#[test]
fn n_channel_p_channel_jacobians_charge_and_passivity_match_finite_differences() {
    for p_channel in [false, true] {
        let element = Element::Mosfet(model(p_channel));
        let sign = if p_channel { -1.0 } else { 1.0 };
        for (collector, base) in [
            (1.0, 5.0),
            (5.0, 3.0),
            (2.0, -0.2),
            (-0.5, 0.0),
            (-0.1, 4.0),
        ] {
            let x = [
                sign * collector,
                0.0,
                sign * 0.001,
                sign * base,
                sign * 0.0001,
            ];
            let e = element.evaluate(&x, 0.0).unwrap();
            assert!(e.dissipated_watts >= 0.0);
            close(e.residual[0] + e.residual[1] + e.residual[3], 0.0, 1e-18);
            for col in 0..5 {
                let mut plus = x;
                let mut minus = x;
                plus[col] += 1e-7;
                minus[col] -= 1e-7;
                let a = element.evaluate(&plus, 0.0).unwrap();
                let b = element.evaluate(&minus, 0.0).unwrap();
                for row in 0..5 {
                    close(
                        (a.residual[row] - b.residual[row]) / 2e-7,
                        e.residual_jacobian[row * 5 + col],
                        1e-8,
                    );
                    close(
                        (a.charge[row] - b.charge[row]) / 2e-7,
                        e.charge_jacobian[row * 5 + col],
                        1e-14,
                    );
                }
            }
            close(
                e.stored_joules,
                0.5e-6 * base * base + 0.25e-6 * (base - collector).powi(2),
                1e-18,
            );
        }
    }
}

#[test]
fn common_source_bias_gain_and_body_diode_match_analytic_reference() {
    for p_channel in [false, true] {
        let sign = if p_channel { -1.0 } else { 1.0 };
        let branches = circuit(p_channel, 6.0, 3.0);
        let point = dc::solve(&branches).unwrap();
        close(point.branches[4].voltage, sign * 5.0, 1e-8);
        close(point.branches[4].current, sign * 0.01, 1e-11);
        close(point.power_balance_watts, 0.0, 1e-12);
        let response = ac::solve(
            &branches,
            0.0,
            &[(
                Id([1, 3]),
                ac::Phasor {
                    real: 1.0,
                    imaginary: 0.0,
                },
            )],
        )
        .unwrap();
        close(response.branches[4].voltage.real, -2.0, 1e-8);
        let diode = -1e-12 * (20.0_f64.exp() - 1.0);
        let reverse = dc::solve(&circuit(p_channel, -0.5 + 100.0 * diode, 0.0)).unwrap();
        close(reverse.branches[4].voltage, -sign * 0.5, 1e-8);
        close(reverse.branches[4].current, sign * diode, 1e-11);
    }
}

#[test]
fn switching_transient_conserves_energy_and_restores_junction_charges() {
    for p_channel in [false, true] {
        let branches = circuit(p_channel, 5.0, 0.0);
        let mut s = CircuitSession::new(
            revision(1, 0),
            TransientCircuit::new(branches, None).unwrap(),
            1e-5,
        )
        .unwrap();
        assert!(s.accepted().operating_point.branches[4].current.abs() < 1e-9);
        let sign = if p_channel { -1.0 } else { 1.0 };
        s.set_parameters(
            revision(2, 1),
            0.0,
            vec![(Id([1, 3]), Element::DcVoltageSource { volts: sign * 5.0 })],
        )
        .unwrap();
        advance(&mut s, 2, 0.005);
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
        let checkpoint = s.snapshot();
        let mut restored = CircuitSession::from_snapshot(&checkpoint).unwrap();
        assert_eq!(restored.snapshot(), checkpoint);
        advance(&mut s, 3, 0.04);
        advance(&mut restored, 3, 0.04);
        assert_eq!(s.snapshot(), restored.snapshot());
        let transistor = &s.accepted().operating_point.branches[4];
        assert!(
            transistor.voltage.abs() < 0.81,
            "Vce={}",
            transistor.voltage
        );
        assert!(
            transistor.current.abs() > 0.0419,
            "Ic={}",
            transistor.current
        );
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
        assert!(s.accepted().energy.numerical_damping_joules > 0.0);
        close(
            transistor.voltage.abs(),
            (0.07_f64 - 0.0029_f64.sqrt()) / 0.02,
            1e-5,
        );
        let before = s.snapshot();
        let mut invalid = model(p_channel);
        invalid.beta_amperes_per_volt_squared = -1.0;
        assert!(
            s.set_parameters(
                revision(3, 4),
                0.04,
                vec![(Id([1, 5]), Element::Mosfet(invalid))]
            )
            .is_err()
        );
        assert_eq!(s.snapshot(), before);
        let x = s.accepted().operating_point.branches[4]
            .local_values
            .clone();
        let mut changed = model(p_channel);
        changed.gate_source_farads *= 2.0;
        s.set_parameters(
            revision(3, 4),
            0.04,
            vec![(Id([1, 5]), Element::Mosfet(changed))],
        )
        .unwrap();
        let y = &s.accepted().operating_point.branches[4].local_values;
        close((y[3] - y[1]) * 2.0, x[3] - x[1], 1e-10);
        close(y[3] - y[0], x[3] - x[0], 1e-10);
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
    }
}
