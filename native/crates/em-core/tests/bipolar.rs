use em_core::{
    model::{DeviceModel, bipolar::Bipolar, element::Element},
    session::circuit::CircuitSession,
    solver::{
        ac,
        dc::{self, DcBranch},
        transient::TransientCircuit,
    },
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::sync::atomic::AtomicBool;

fn model(pnp: bool) -> Bipolar {
    Bipolar {
        saturation_amperes: 1e-12,
        thermal_volts: 0.025,
        forward_beta: 100.0,
        reverse_beta: 1.0,
        base_emitter_farads: 1e-6,
        base_collector_farads: 0.5e-6,
        pnp,
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
fn circuit(pnp: bool, supply: f64, drive: f64) -> Vec<DcBranch> {
    let sign = if pnp { -1.0 } else { 1.0 };
    vec![
        branch(
            1,
            3,
            0,
            Element::DcVoltageSource {
                volts: sign * supply,
            },
        ),
        branch(2, 3, 1, Element::Resistor { ohms: 1000.0 }),
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
            ..branch(5, 1, 0, Element::Bipolar(model(pnp)))
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
fn npn_pnp_jacobians_charge_and_passivity_match_finite_differences() {
    for pnp in [false, true] {
        let element = Element::Bipolar(model(pnp));
        let sign = if pnp { -1.0 } else { 1.0 };
        for (collector, base) in [(1.0, 0.5), (0.05, 0.6), (2.0, -0.2)] {
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
fn common_emitter_bias_and_small_signal_gain_match_analytic_reference() {
    // Construct a known operating point Vbe=.5 V, Vce=1 V using independent EM equations.
    let forward = 1e-12 * (20.0_f64.exp() - 1.0);
    let reverse = 1e-12 * ((-20.0_f64).exp() - 1.0);
    let collector = forward - 2.0 * reverse;
    let base = forward / 100.0 + reverse;
    for pnp in [false, true] {
        let branches = circuit(pnp, 1.0 + 1000.0 * collector, 0.5 + 1000.0 * base);
        let point = dc::solve(&branches).unwrap();
        let sign = if pnp { -1.0 } else { 1.0 };
        close(point.branches[4].voltage, sign, 1e-8);
        close(point.branches[4].current, sign * collector, 1e-11);
        close(
            point.branches[4].local_values[3] - point.branches[4].local_values[1],
            sign * 0.5,
            1e-9,
        );
        close(point.branches[4].local_values[4], sign * base, 1e-11);
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
        let gm = 1e-12 * 20.0_f64.exp() / 0.025;
        close(
            response.branches[4].voltage.real,
            -1000.0 * gm / (1.0 + 1000.0 * gm / 100.0),
            1e-6,
        );
    }
}

#[test]
fn switching_transient_conserves_energy_and_restores_junction_charges() {
    for pnp in [false, true] {
        let branches = circuit(pnp, 5.0, 0.0);
        let mut s = CircuitSession::new(
            revision(1, 0),
            TransientCircuit::new(branches, None).unwrap(),
            1e-5,
        )
        .unwrap();
        assert!(s.accepted().operating_point.branches[4].current.abs() < 1e-9);
        let sign = if pnp { -1.0 } else { 1.0 };
        s.set_parameters(
            revision(2, 1),
            0.0,
            vec![(Id([1, 3]), Element::DcVoltageSource { volts: sign * 1.5 })],
        )
        .unwrap();
        advance(&mut s, 2, 0.005);
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
        let checkpoint = s.snapshot();
        let mut restored = CircuitSession::from_snapshot(&checkpoint).unwrap();
        assert_eq!(restored.snapshot(), checkpoint);
        advance(&mut s, 3, 0.02);
        advance(&mut restored, 3, 0.02);
        assert_eq!(s.snapshot(), restored.snapshot());
        let transistor = &s.accepted().operating_point.branches[4];
        assert!(transistor.voltage.abs() < 0.2, "Vce={}", transistor.voltage);
        assert!(
            transistor.current.abs() > 0.0048,
            "Ic={}",
            transistor.current
        );
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
        assert!(s.accepted().energy.numerical_damping_joules > 0.0);
        let before = s.snapshot();
        let mut invalid = model(pnp);
        invalid.forward_beta = -1.0;
        assert!(
            s.set_parameters(
                revision(3, 4),
                0.02,
                vec![(Id([1, 5]), Element::Bipolar(invalid))]
            )
            .is_err()
        );
        assert_eq!(s.snapshot(), before);
        let x = s.accepted().operating_point.branches[4]
            .local_values
            .clone();
        let mut changed = model(pnp);
        changed.base_emitter_farads *= 2.0;
        s.set_parameters(
            revision(3, 4),
            0.02,
            vec![(Id([1, 5]), Element::Bipolar(changed))],
        )
        .unwrap();
        let y = &s.accepted().operating_point.branches[4].local_values;
        close((y[3] - y[1]) * 2.0, x[3] - x[1], 1e-10);
        close(y[3] - y[0], x[3] - x[0], 1e-10);
        close(s.accepted().energy.balance_residual_joules(), 0.0, 1e-10);
    }
}

#[test]
fn cold_thermal_leads_and_small_junctions_accept_supply_startup() {
    use em_core::{
        model::thermal::ThermalResistor,
        state::{InitialConditions, InitialStorage},
    };
    let mut m = model(false);
    m.saturation_amperes = 0.001 / (0.7_f64 / 0.02585).exp_m1();
    m.thermal_volts = 0.02585;
    m.base_emitter_farads = 1e-9;
    m.base_collector_farads = 1e-10;
    let heat = ThermalResistor {
        ambient_ohms: 0.01,
        coefficient_per_kelvin: 0.0,
        heat_capacity_joules_per_kelvin: 0.0001,
        conductance_watts_per_kelvin: 0.0001,
        ambient_kelvin: 295.15,
        trip_kelvin: Some(395.15),
    };
    let mut branches = circuit(false, 10.0, 0.7);
    branches[1].element = Element::ThermalResistor(heat);
    branches[3].element = Element::ThermalResistor(heat);
    branches[4].element = Element::Bipolar(m);
    let initial = InitialConditions::from([
        (branches[1].id, InitialStorage::TemperatureRiseKelvin(0.0)),
        (branches[3].id, InitialStorage::TemperatureRiseKelvin(0.0)),
        (
            branches[4].id,
            InitialStorage::PortCharges {
                control_return: 0.0,
                control_output: 0.0,
            },
        ),
    ]);
    let mut k = TransientCircuit::new(branches, Some(&initial)).unwrap();
    for j in 1..=10 {
        k.step(f64::from(j) * 1e-4).unwrap();
    }
    close(
        k.accepted().operating_point.branches[4].current,
        0.001,
        1e-6,
    );
    assert!(k.accepted().energy.balance_residual_joules().abs() < 1e-8);
}

#[test]
fn insulated_gate_cold_thermal_lead_does_not_create_negative_heat() {
    use em_core::{
        model::thermal::ThermalResistor,
        state::{InitialConditions, InitialStorage},
    };
    let mut m = model(false);
    m.saturation_amperes = 0.001 / (0.7_f64 / 0.02585).exp_m1();
    m.thermal_volts = 0.02585;
    m.base_emitter_farads = 1e-9;
    m.base_collector_farads = 1e-10;
    let heat = ThermalResistor {
        ambient_ohms: 0.01,
        coefficient_per_kelvin: 0.0,
        heat_capacity_joules_per_kelvin: 0.0001,
        conductance_watts_per_kelvin: 0.0001,
        ambient_kelvin: 295.15,
        trip_kelvin: Some(395.15),
    };
    let mut branches = circuit(false, 10.0, 2.01);
    branches[1].element = Element::ThermalResistor(heat);
    branches[3].element = Element::ThermalResistor(heat);
    branches[4].element = Element::Mosfet(em_core::model::mosfet::Mosfet {
        beta_amperes_per_volt_squared: 10.0,
        threshold_volts: 2.0,
        saturation_amperes: 1e-12,
        thermal_volts: m.thermal_volts,
        gate_source_farads: m.base_emitter_farads,
        gate_drain_farads: m.base_collector_farads,
        p_channel: false,
    });
    let initial = InitialConditions::from([
        (branches[1].id, InitialStorage::TemperatureRiseKelvin(0.0)),
        (branches[3].id, InitialStorage::TemperatureRiseKelvin(0.0)),
        (
            branches[4].id,
            InitialStorage::PortCharges {
                control_return: 0.0,
                control_output: 0.0,
            },
        ),
    ]);
    let mut k = TransientCircuit::new(branches, Some(&initial)).unwrap();
    for j in 1..=100 {
        k.step(f64::from(j) * 1e-4).unwrap();
    }
    assert!(k.accepted().operating_point.branches[3].stored_joules >= 0.0);
    close(
        k.accepted().operating_point.branches[4].current,
        0.0005,
        1e-6,
    );
    assert!(k.accepted().energy.balance_residual_joules().abs() < 1e-8);
}
