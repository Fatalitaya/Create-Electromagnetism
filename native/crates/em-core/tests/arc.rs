use em_core::{
    model::{DeviceModel, arc::Arc, element::Element},
    solver::{dc::DcBranch, transient::TransientCircuit},
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{DeviceId, Id, PortId};

fn gap() -> Arc {
    Arc {
        closed: false,
        contact_ohms: 0.01,
        leakage_siemens: 1e-12,
        hot_siemens: 2.0,
        breakdown_volts: 1000.0,
        ignition_rise: 1000.0,
        capacity: 1.0,
        cooling: 1.0,
        ambient: 293.15,
    }
}

#[test]
fn cold_insulation_and_hot_conduction_have_declared_limits_in_both_polarities() {
    let model = gap();
    for voltage in [-900.0, -50.0, 0.0, 50.0, 900.0] {
        let expected = model.leakage_siemens * voltage;
        let e = model.evaluate(&[voltage, 0.0, expected, 0.0]).unwrap();
        assert!(e.residual[2].abs() < 1e-24);
        assert!((e.residual[3] + voltage * expected).abs() < 1e-20);
    }
    for (voltage, rise) in [(1100.0, 0.0), (-1100.0, 0.0), (50.0, 1100.0)] {
        let expected = model.hot_siemens * voltage;
        let e = model.evaluate(&[voltage, 0.0, expected, rise]).unwrap();
        assert!(e.residual[2].abs() < 1e-12);
    }
}

#[test]
fn ignition_transition_jacobian_matches_finite_differences() {
    for (voltage, rise) in [(1000.0, 0.0), (-1000.0, 0.0), (500.0, 750.0)] {
        let x = [voltage, 0.0, 0.1, rise];
        let model = gap();
        let expected = model.evaluate(&x).unwrap();
        for col in 0..4 {
            let h = 1e-4;
            let mut a = x;
            let mut b = x;
            a[col] += h;
            b[col] -= h;
            let a = model.evaluate(&a).unwrap();
            let b = model.evaluate(&b).unwrap();
            for row in 0..4 {
                let derivative = (a.residual[row] - b.residual[row]) / (2.0 * h);
                let analytic = expected.residual_jacobian[row * 4 + col];
                assert!((derivative - analytic).abs() < 1e-6 * analytic.abs().max(1.0));
            }
        }
    }
}

#[test]
fn unpowered_gap_cools_through_extinction_with_conserved_heat() {
    let p = PortId {
        device: DeviceId {
            instance: Id([1, 1]),
            generation: 0,
        },
        terminal: 0,
    };
    let n = PortId { terminal: 1, ..p };
    let branches = [Element::DcVoltageSource { volts: 0.0 }, Element::Arc(gap())]
        .into_iter()
        .enumerate()
        .map(|(i, element)| DcBranch {
            id: Id([2, i as u64]),
            positive: p,
            negative: n,
            additional_ports: vec![],
            element,
        })
        .collect();
    let initial =
        InitialConditions::from([(Id([2, 1]), InitialStorage::TemperatureRiseKelvin(1200.0))]);
    let mut kernel = TransientCircuit::new(branches, Some(&initial)).unwrap();
    for i in 1..=1000 {
        kernel.step(i as f64 * 0.001).unwrap();
    }
    let state = kernel.accepted();
    let theta = state.operating_point.branches[1].local_values[3];
    assert!((theta - 1200.0 * (-1.0_f64).exp()).abs() < 0.23);
    assert!(state.energy.balance_residual_joules().abs() < 1e-8);
    let voltage = 50.0;
    let e = Element::Arc(gap())
        .evaluate(&[voltage, 0.0, voltage * gap().leakage_siemens, theta], 1.0)
        .unwrap();
    assert!(e.residual[2].abs() < 1e-24);
}
