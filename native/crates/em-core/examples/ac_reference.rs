//! Print peak phasors for the AC reference circuit.
use em_core::{
    model::element::Element,
    solver::{
        ac::{self, Phasor},
        dc::DcBranch,
    },
};
use em_protocol::contract::{DeviceId, Id, PortId};

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
fn main() {
    let kind = std::env::args().nth(1).expect("rc, rlc or diode");
    let (branches, output) = match kind.as_str() {
        "rc" => (
            vec![
                branch(1, 1, 0, Element::DcVoltageSource { volts: 1.0 }),
                branch(2, 1, 2, Element::Resistor { ohms: 1000.0 }),
                branch(3, 2, 0, Element::Capacitor { farads: 1e-6 }),
            ],
            2,
        ),
        "rlc" => (
            vec![
                branch(1, 1, 0, Element::DcVoltageSource { volts: 0.0 }),
                branch(2, 1, 2, Element::Resistor { ohms: 10.0 }),
                branch(3, 2, 3, Element::Inductor { henries: 0.01 }),
                branch(4, 3, 0, Element::Capacitor { farads: 1e-6 }),
            ],
            3,
        ),
        "diode" => (
            vec![
                branch(
                    1,
                    1,
                    0,
                    Element::DcVoltageSource {
                        volts: 0.5 + 1000.0 * 1e-12 * 20.0_f64.exp_m1(),
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
            ],
            2,
        ),
        _ => panic!("Unknown fixture"),
    };
    println!(
        "frequency voltage_real voltage_imaginary source_current_real source_current_imaginary"
    );
    for index in 0..=50 {
        let frequency = 10_f64.powf(index as f64 / 10.0);
        let response = ac::solve(
            &branches,
            frequency,
            &[(
                Id([1, 1]),
                Phasor {
                    real: 1.0,
                    imaginary: 0.0,
                },
            )],
        )
        .unwrap();
        let voltage = response.branches[output].voltage;
        let current = response.branches[0].current;
        println!(
            "{frequency:.17e} {:.17e} {:.17e} {:.17e} {:.17e}",
            voltage.real, voltage.imaginary, current.real, current.imaginary
        );
    }
}
