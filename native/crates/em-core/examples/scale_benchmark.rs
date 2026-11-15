//! Reproducible bounded-work benchmark, with physical references alongside timings.
//! Run in release; stdout is JSON Lines. Memory/hardware are measured by the runner.
use em_core::{
    model::{element::Element, windings::Windings},
    session::circuit::CircuitSession,
    solver::{
        dc::DcBranch,
        transient::{Accuracy, TransientCircuit},
    },
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Advance, DeviceId, Id, PortId, Revision};
use std::{sync::atomic::AtomicBool, time::Instant};
fn port(n: u64) -> PortId {
    PortId {
        device: DeviceId {
            instance: Id([0, n]),
            generation: 0,
        },
        terminal: 0,
    }
}
fn branch(id: u64, a: u64, b: u64, element: Element) -> DcBranch {
    DcBranch {
        id: Id([1, id]),
        positive: port(a),
        negative: port(b),
        additional_ports: vec![],
        element,
    }
}
fn setup(name: &str) -> (Vec<DcBranch>, Option<InitialConditions>) {
    let mut b = vec![];
    let mut initial = InitialConditions::new();
    match name {
        "chain" => {
            b.push(branch(
                0,
                1023,
                0,
                Element::DcVoltageSource { volts: 1023.0 },
            ));
            for n in 1..1024 {
                b.push(branch(n, n, n - 1, Element::Resistor { ohms: 1.0 }));
            }
        }
        "grid" => {
            let mut push = |a, z, e| b.push(branch(b.len() as u64, a, z, e));
            for r in 0..16 {
                for c in 0..32 {
                    let n = r * 32 + c;
                    if c > 0 {
                        push(n, n - 1, Element::Resistor { ohms: 1.0 });
                    }
                    if r > 0 {
                        push(n, n - 32, Element::Resistor { ohms: 1.0 });
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
        }
        "rc_islands" | "nonlinear_islands" => {
            for island in 0..256 {
                let n = island * 3;
                let k = b.len() as u64;
                let nonlinear = name == "nonlinear_islands";
                b.push(branch(
                    k,
                    n + 1,
                    n,
                    if nonlinear {
                        Element::SineVoltageSource {
                            offset: 0.3,
                            peak: 0.05,
                            hertz: 50.0,
                            phase: 0.0,
                        }
                    } else {
                        Element::DcVoltageSource { volts: 1.0 }
                    },
                ));
                b.push(branch(
                    k + 1,
                    n + 1,
                    n + 2,
                    Element::Resistor {
                        ohms: if nonlinear { 1000.0 } else { 1.0 },
                    },
                ));
                b.push(branch(
                    k + 2,
                    n + 2,
                    n,
                    if nonlinear {
                        Element::Diode {
                            saturation_amperes: 1e-9,
                            thermal_volts: 0.026,
                            junction_farads: 1e-9,
                        }
                    } else {
                        Element::Capacitor { farads: 1.0 }
                    },
                ));
                b.push(branch(
                    k + 3,
                    n + 2,
                    n,
                    Element::Resistor {
                        ohms: if nonlinear { 1000.0 } else { 1.0 },
                    },
                ));
                if !nonlinear {
                    initial.insert(Id([1, k + 2]), InitialStorage::ChargeCoulombs(0.0));
                }
            }
        }
        "coupled_islands" => {
            for island in 0..256 {
                let n = island * 5;
                let k = b.len() as u64;
                b.push(branch(k, n + 1, n, Element::DcVoltageSource { volts: 0.0 }));
                b.push(branch(k + 1, n + 1, n + 2, Element::Resistor { ohms: 1.0 }));
                let mut winding = branch(
                    k + 2,
                    n + 2,
                    n,
                    Element::Windings(Windings {
                        primary_henries: 2.0,
                        secondary_henries: 1.0,
                        mutual_henries: 0.5,
                        primary_ohms: 0.1,
                        secondary_ohms: 0.1,
                    }),
                );
                winding.additional_ports = vec![port(n + 3), port(n + 4)];
                b.push(winding);
                b.push(branch(k + 3, n + 3, n + 4, Element::Resistor { ohms: 1.0 }));
                initial.insert(
                    Id([1, k + 2]),
                    InitialStorage::WindingFluxes {
                        primary: 2.5,
                        secondary: 1.5,
                    },
                );
            }
        }
        _ => unreachable!(),
    }
    assert_eq!(b.len(), 1024);
    (
        b,
        if initial.is_empty() {
            None
        } else {
            Some(initial)
        },
    )
}
fn reference_error(name: &str, s: &CircuitSession) -> f64 {
    let t = s.accepted().time_seconds;
    let p = &s.accepted().operating_point;
    match name {
        "chain" => p
            .branches
            .iter()
            .skip(1)
            .map(|p| (p.current - 1.0).abs())
            .fold(0.0, f64::max),
        "grid" => {
            let (grid, _) = setup("grid");
            p.branches
                .iter()
                .map(|p| {
                    let id = p.id.0[1] as usize;
                    let b = &grid;
                    let expected = (b[id].positive.device.instance.0[1] % 32) as f64
                        - (b[id].negative.device.instance.0[1] % 32) as f64;
                    (p.voltage - expected).abs()
                })
                .fold(0.0, f64::max)
        }
        "rc_islands" => {
            let expected = 0.5 * (1.0 - (-2.0 * t).exp());
            p.branches
                .iter()
                .skip(2)
                .step_by(4)
                .map(|p| (p.voltage - expected).abs())
                .fold(0.0, f64::max)
        }
        "nonlinear_islands" => {
            let source = 0.3 + 0.05 * (std::f64::consts::TAU * 50.0 * t).sin();
            let (mut low, mut high) = (0.0, source);
            for _ in 0..100 {
                let v = (low + high) * 0.5;
                if (source - v) / 1000.0 > v / 1000.0 + 1e-9 * (v / 0.026).exp_m1() {
                    low = v;
                } else {
                    high = v;
                }
            }
            p.branches
                .iter()
                .skip(2)
                .step_by(4)
                .map(|p| (p.voltage - (low + high) * 0.5).abs())
                .fold(0.0, f64::max)
        }
        "coupled_islands" => {
            // exp(-L^-1 R t)*(1,1), from the two eigenvalues of the 2x2 matrix.
            let a: f64 = 1.1 / 1.75;
            let b = -0.55 / 1.75;
            let d = 2.2 / 1.75;
            let mid = (a + d) * 0.5;
            let delta = (((a - d) * 0.5).powi(2) + b * b).sqrt();
            let expected = (-mid * t).exp()
                * ((delta * t).cosh() - (a - mid + b) / delta * (delta * t).sinh());
            p.branches
                .iter()
                .skip(2)
                .step_by(4)
                .map(|p| (p.current - expected).abs())
                .fold(0.0, f64::max)
        }
        _ => unreachable!(),
    }
}
fn main() {
    for name in [
        "chain",
        "grid",
        "rc_islands",
        "nonlinear_islands",
        "coupled_islands",
    ] {
        let (branches, initial) = setup(name);
        let build = Instant::now();
        let kernel = TransientCircuit::new(branches, initial.as_ref()).unwrap();
        let revision = Revision {
            session: Id([8, 1]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        };
        let accuracy = Accuracy {
            relative: 1e-3,
            voltage: 1e-6,
            current: 1e-9,
            charge: 1e-15,
            flux: 1e-12,
            energy: 1e-9,
            minimum_step: 1e-12,
        };
        let mut session = CircuitSession::new_adaptive(revision, kernel, 0.0001, accuracy).unwrap();
        let build_ms = build.elapsed().as_secs_f64() * 1000.0;
        let mut elapsed = vec![];
        let mut rejected = 0;
        let mut accepted = 0;
        let mut discarded = 0;
        let mut error: f64 = 0.0;
        for sequence in 1..=20 {
            let start = session.accepted().time_seconds;
            let request = Advance {
                revision: Revision {
                    sequence,
                    ..revision
                },
                accepted_start: start,
                requested_end: start + 0.05,
                max_steps: 10000,
                budget_nanos: 20_000_000,
            };
            let clock = Instant::now();
            let result = session.advance(request, &AtomicBool::new(false)).unwrap();
            elapsed.push(clock.elapsed().as_secs_f64() * 1000.0);
            accepted += result.work.accepted_steps;
            rejected += result.work.rejected_steps;
            discarded += result.work.discarded_steps;
            error = error.max(reference_error(name, &session));
        }
        elapsed.sort_by(f64::total_cmp);
        let time = session.accepted().time_seconds;
        let snapshot = session.snapshot().len();
        let unit = if name == "chain" || name == "coupled_islands" {
            "A"
        } else {
            "V"
        };
        println!(
            "{{\"topology\":\"{name}\",\"branches\":1024,\"build_ms\":{build_ms},\"request_count\":20,\"request_budget_ms\":20,\"p50_ms\":{},\"p95_ms\":{},\"p99_ms\":{},\"accepted_steps\":{accepted},\"rejected_steps\":{rejected},\"discarded_steps\":{discarded},\"accepted_seconds\":{time},\"uncompleted_seconds\":{},\"max_reference_error\":{error},\"error_unit\":\"{unit}\",\"energy_residual_joules\":{},\"snapshot_bytes\":{snapshot}}}",
            elapsed[9],
            elapsed[18],
            elapsed[19],
            1.0 - time,
            session.accepted().energy.balance_residual_joules()
        );
    }
}
