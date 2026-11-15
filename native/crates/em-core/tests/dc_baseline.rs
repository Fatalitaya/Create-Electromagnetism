use em_core::{
    model::{DeviceModel, element::Element},
    solver::{
        dc::{self, DcBranch},
        linear,
    },
};
use em_protocol::contract::{DeviceId, Id, PortId};

fn port(n: u64) -> PortId {
    PortId {
        device: DeviceId {
            instance: Id([0, n]),
            generation: 1,
        },
        terminal: 0,
    }
}
fn resistor(id: u64, a: u64, b: u64, ohms: f64) -> DcBranch {
    DcBranch {
        additional_ports: Vec::new(),
        id: Id([1, id]),
        positive: port(a),
        negative: port(b),
        element: Element::Resistor { ohms },
    }
}
fn source(id: u64, a: u64, b: u64, volts: f64) -> DcBranch {
    DcBranch {
        element: Element::DcVoltageSource { volts },
        ..resistor(id, a, b, 1.0)
    }
}
fn near(actual: f64, expected: f64) {
    assert!(
        (actual - expected).abs() < 1e-10,
        "actual={actual}, expected={expected}"
    );
}

#[test]
fn divider_has_correct_voltage_current_and_power_signs() {
    let result = dc::solve(&[
        source(1, 1, 0, 12.0),
        resistor(2, 1, 2, 100.0),
        resistor(3, 2, 0, 200.0),
    ])
    .unwrap();
    near(result.voltages[&port(2)] - result.voltages[&port(0)], 8.0);
    near(result.branches[0].current, -0.04);
    near(result.branches[0].absorbed_watts, -0.48);
    near(result.branches[1].dissipated_watts, 0.16);
    near(result.branches[2].dissipated_watts, 0.32);
    near(result.power_balance_watts, 0.0);
}

#[test]
fn parallel_branches_keep_identity_and_orientation_under_reordering() {
    let mut branches = vec![
        source(1, 1, 0, 10.0),
        resistor(2, 1, 0, 100.0),
        resistor(3, 0, 1, 200.0),
    ];
    let first = dc::solve(&branches).unwrap();
    branches.reverse();
    let second = dc::solve(&branches).unwrap();
    near(first.branches[1].current, 0.1);
    near(first.branches[2].current, -0.05);
    for branch in first.branches {
        let other = second.branches.iter().find(|b| b.id == branch.id).unwrap();
        near(branch.voltage, other.voltage);
        near(branch.current, other.current);
    }
}

#[test]
fn floating_islands_and_source_free_island_use_independent_gauges() {
    let result = dc::solve(&[
        source(1, 1, 0, 10.0),
        resistor(2, 1, 0, 100.0),
        source(3, 3, 4, -5.0),
        resistor(4, 3, 4, 100.0),
        resistor(5, 7, 8, 1.0),
    ])
    .unwrap();
    assert_eq!(result.references.len(), 3);
    near(result.branches[3].current, -0.05);
    near(result.branches[4].current, 0.0);
    near(result.power_balance_watts, 0.0);
}

#[test]
fn balanced_bridge_has_zero_cross_current() {
    let result = dc::solve(&[
        source(1, 1, 0, 12.0),
        resistor(2, 1, 2, 100.0),
        resistor(3, 2, 0, 200.0),
        resistor(4, 1, 3, 200.0),
        resistor(5, 3, 0, 400.0),
        resistor(6, 2, 3, 50.0),
    ])
    .unwrap();
    near(result.branches[5].current, 0.0);
    near(result.branches[0].current, -0.06);
    near(result.power_balance_watts, 0.0);
}

#[test]
fn contradictory_and_redundant_sources_are_diagnosed_without_mutation() {
    for volts in [10.0, 11.0] {
        let branches = [source(1, 1, 0, 10.0), source(2, 1, 0, volts)];
        let before = format!("{branches:?}");
        assert!(
            dc::solve(&branches)
                .unwrap_err()
                .reason
                .contains("Singular")
        );
        assert_eq!(before, format!("{branches:?}"));
    }
}

#[test]
fn invalid_input_is_rejected_and_previous_dense_limit_is_removed() {
    for ohms in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        assert!(dc::solve(&[resistor(1, 1, 0, ohms)]).is_err());
    }
    assert!(dc::solve(&[source(1, 1, 0, f64::NAN)]).is_err());
    assert!(dc::solve(&[resistor(1, 1, 1, 1.0)]).is_err());
    assert!(dc::solve(&[resistor(1, 1, 0, 1.0), resistor(1, 1, 0, 2.0)]).is_err());
    assert!(dc::solve(&(0..129).map(|i| resistor(i, 1, 0, 1.0)).collect::<Vec<_>>()).is_ok());
    assert!(dc::solve(&[]).unwrap().branches.is_empty());
}

#[test]
fn dense_solver_pivots_and_checks_shape_and_nonfinite_data() {
    let x = linear::solve(&[0.0, 2.0, 1.0, 3.0], &[4.0, 7.0]).unwrap();
    near(x[0], 1.0);
    near(x[1], 2.0);
    assert!(linear::solve(&[1.0], &[1.0, 2.0]).is_err());
    assert!(linear::solve(&[f64::NAN], &[1.0]).is_err());
    assert!(linear::solve(&[0.0], &[1.0]).is_err());
}

#[test]
fn constitutive_jacobians_match_finite_differences_and_terminal_kcl() {
    for model in [
        Element::Resistor { ohms: 20.0 },
        Element::DcVoltageSource { volts: -5.0 },
    ] {
        let values = [3.0, -2.0, 0.25];
        let base = model.evaluate(&values, 0.0).unwrap();
        near(base.residual[0] + base.residual[1], 0.0);
        assert!(base.charge.iter().all(|&q| q == 0.0));
        near(base.stored_joules, 0.0);
        for col in 0..3 {
            let mut plus = values;
            let mut minus = values;
            plus[col] += 1e-5;
            minus[col] -= 1e-5;
            let p = model.evaluate(&plus, 0.0).unwrap();
            let m = model.evaluate(&minus, 0.0).unwrap();
            for row in 0..3 {
                assert!(
                    ((p.residual[row] - m.residual[row]) / 2e-5
                        - base.residual_jacobian[row * 3 + col])
                        .abs()
                        < 1e-8
                );
            }
        }
    }
}
