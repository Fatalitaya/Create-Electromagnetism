use em_core::{
    model::{DeviceModel, UnknownUnit, element::Element},
    solver::{ac, dc::DcBranch},
};
use em_protocol::contract::{DeviceId, Id, PortId};

fn fixtures() -> Vec<Element> {
    let mut cases = vec![
        (1, vec![2.0]),
        (2, vec![1.0]),
        (3, vec![1e-6]),
        (4, vec![0.2]),
        (5, vec![1e-12, 0.026, 1e-9]),
        (6, vec![0.0, 1.0, 0.0, 0.2]),
        (7, vec![1e-12, 0.026, 100.0, 2.0, 1e-9, 2e-9]),
        (8, vec![1e-12, 0.026, 100.0, 2.0, 1e-9, 2e-9]),
        (9, vec![0.01, 1.0, 1e-12, 0.026, 1e-9, 2e-9]),
        (10, vec![0.01, 1.0, 1e-12, 0.026, 1e-9, 2e-9]),
        (11, vec![0.2, 0.1, 0.05, 1.0, 2.0]),
        (12, vec![2.0, 0.004, 4.0, 0.5, 300.0]),
        (13, vec![2.0, 0.004, 4.0, 0.5, 300.0, 400.0]),
        (14, vec![0.01]),
        (15, vec![1.0, -2.0]),
        (16, vec![1.0, 4.2, 0.2]),
        (17, vec![5.0, 1.0, 0.9]),
        (18, vec![1.0, 0.0, 2.0, 0.3]),
        (19, vec![10.0, 0.01, 1e6, 5.0, 2.0, 0.0, 0.0]),
        (
            21,
            vec![0.0, 0.01, 1e-12, 2.0, 1000.0, 1000.0, 1.0, 1.0, 300.0],
        ),
        (22, vec![2.0, 0.1, 0.1, 5.0]),
        (23, vec![0.0, 100.0, 1e-5, 1e-3, 2.0, 1.0, 0.01]),
        (25, vec![0.0, 0.2, 0.5]),
        (27, vec![1.0, 2.0]),
        (28, vec![1.0, 0.0, 2.0, 0.3]),
    ];
    let mut audio = vec![0.0, 1.0 / 16000.0, 1.0, 2.0];
    audio.extend([0.3; 800]);
    cases.push((24, audio.clone()));
    let mut radio = vec![0.0, 0.2];
    radio.extend(audio);
    cases.push((26, radio));
    for ports in [2, 3, 8, 32] {
        let mut p = vec![2.0, 0.004, 4.0, 0.5, 300.0, 400.0, ports as f64];
        let mut positions = [0.0; 32];
        positions[1] = 1.0;
        for i in 2..ports {
            positions[i] = (i - 1) as f64 / (ports - 1) as f64;
        }
        p.extend(positions);
        cases.push((20, p));
    }
    cases
        .into_iter()
        .map(|(tag, p)| {
            Element::from_parameters(tag, &p).unwrap_or_else(|e| panic!("tag {tag}: {e:?}"))
        })
        .collect()
}

fn check_jacobians(element: &Element, x: &[f64], time: f64) {
    let base = element.evaluate(x, time).unwrap();
    let n = x.len();
    for col in 0..n {
        let h = 1e-6 * x[col].abs().max(0.01);
        let mut plus = x.to_vec();
        let mut minus = x.to_vec();
        plus[col] += h;
        minus[col] -= h;
        let plus = element.evaluate(&plus, time).unwrap();
        let minus = element.evaluate(&minus, time).unwrap();
        for (name, analytic, a, b) in [
            (
                "F",
                &base.residual_jacobian,
                &plus.residual,
                &minus.residual,
            ),
            ("Q", &base.charge_jacobian, &plus.charge, &minus.charge),
        ] {
            for row in 0..n {
                let numeric = (a[row] - b[row]) / (2.0 * h);
                let expected = analytic[row * n + col];
                let cancellation = 64.0 * f64::EPSILON * a[row].abs().max(b[row].abs()) / h;
                assert!(
                    (numeric - expected).abs()
                        <= 2e-5 * expected.abs().max(numeric.abs()) + 1e-12 + cancellation,
                    "{element:?} {name}[{row},{col}]: {numeric} != {expected}"
                );
            }
        }
    }
}

#[test]
fn all_models_declare_units_and_match_residual_and_charge_derivatives() {
    for element in fixtures() {
        let units = element.unknown_units();
        assert_eq!(units.len(), element.unknown_count());
        assert_eq!(
            &units[..3],
            &[UnknownUnit::Volt, UnknownUnit::Volt, UnknownUnit::Ampere]
        );
        for port in 2..element.port_count() {
            assert_eq!(units[2 * port - 1], UnknownUnit::Volt);
            assert_eq!(units[2 * port], UnknownUnit::Ampere);
        }
        match element.parameters().0 {
            12 | 13 | 20 | 21 => assert_eq!(units.last(), Some(&UnknownUnit::Kelvin)),
            22 => assert_eq!(units.last(), Some(&UnknownUnit::VoltSquared)),
            _ => {}
        }
        for magnitude in [-0.2, 0.1, 0.7, 2.0] {
            let mut x: Vec<_> = units
                .iter()
                .enumerate()
                .map(|(i, unit)| match unit {
                    UnknownUnit::Volt => {
                        if i == 0 {
                            magnitude
                        } else {
                            0.0
                        }
                    }
                    UnknownUnit::Ampere => 0.001,
                    UnknownUnit::Kelvin => 10.0,
                    UnknownUnit::VoltSquared => 4.0,
                })
                .collect();
            if matches!(element, Element::Converter { .. }) {
                x[3] = 3.0;
            }
            if matches!(element, Element::BatteryContact { .. }) {
                x[3] = -2.0;
            }
            if matches!(element, Element::Mosfet(_)) {
                x[3] = magnitude * 2.0;
            }
            for time in [0.0, 0.0002, 0.02, 0.06] {
                check_jacobians(&element, &x, time);
            }
            if matches!(element, Element::Regulator(_)) {
                for detector in [0.01, 1.0, 1000.0] {
                    x[5] = detector;
                    check_jacobians(&element, &x, 0.0);
                }
            }
            if matches!(element, Element::Arc(_)) {
                x[3] = 1200.0;
                check_jacobians(&element, &x, 0.0);
                x[0] = 1100.0;
                x[3] = 0.1;
                check_jacobians(&element, &x, 0.0);
            }
        }
    }
}

#[test]
fn ac_rejects_every_time_dependent_variant_even_with_zero_clock_frequency() {
    for element in fixtures() {
        let tag = element.parameters().0;
        let expected = !matches!(tag, 6 | 18 | 23 | 24 | 25 | 26 | 28);
        assert_eq!(element.supports_stationary_ac_bias(), expected, "tag {tag}");
        if expected {
            continue;
        }
        let port = |terminal| PortId {
            device: DeviceId {
                instance: Id([0, 1]),
                generation: 0,
            },
            terminal,
        };
        let branch = DcBranch {
            id: Id([1, 1]),
            positive: port(0),
            negative: port(1),
            additional_ports: (2..element.port_count()).map(|i| port(i as u32)).collect(),
            element,
        };
        let error = ac::solve(&[branch], 1.0, &[]).unwrap_err();
        assert!(error.reason.contains("stationary"), "{error:?}");
    }
}
