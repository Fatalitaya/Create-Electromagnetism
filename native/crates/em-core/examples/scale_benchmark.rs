//! Run in release mode for JSON Lines timings and physical reference values.
//! `--replay <snapshot> [ticks] [rad/s]` advances consecutive 50 ms windows, using captured shaft speeds unless overridden.
//! The three-phase-bridge fixtures model a 256 RPM alternator feeding six rated rectifiers.
//! `open` and `loaded` (1 kΩ) start at 0.05 s. `pretrip` starts at 0.55 s after the
//! load changed to 10 mΩ at 0.05 s. Replay one window to observe the first protection event.
//! `--alternator [ticks] [primary-ohms] [tolerance-scale]` starts a cold 4.267 Hz transformer and regulator circuit.
//! `--plant-window [--early-window] [--first-event] [--sampled|--unsampled] [--no-trips]` replays the plant snapshot.
//! `--full-interval [topology] [requests]` measures complete 50 ms windows.
#[path = "comparison/bridge.rs"]
mod bridge_comparison;

use em_core::{
    model::{element::Element, windings::Windings},
    session::circuit::CircuitSession,
    solver::{
        dc::DcBranch,
        transient::{Accuracy, TransientCircuit},
    },
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{Accepted, Advance, DeviceId, Id, PortId, Revision, Status};
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
                        tap: None,
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
            // Use the two eigenvalues to evaluate exp(-L⁻¹Rt) at the initial state (1, 1).
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
fn interval_outcome(result: &Accepted, pending_events: usize) -> &'static str {
    match result.diagnostic.status {
        Status::Ready if result.end == result.requested_end && pending_events == 0 => "complete",
        Status::Partial if pending_events > 0 => {
            eprintln!("session {:?} stopped at {} s: {}", result.revision.session, result.end, result.diagnostic.reason);
            "protection_event"
        }
        status => panic!(
            "session {:?} advanced to {} of {} s ({status:?}, {pending_events} pending events): {}",
            result.revision.session, result.end, result.requested_end, result.diagnostic.reason
        ),
    }
}

// A 100 Hz drive feeds 600 Ω receivers. Eight-block spacing keeps each antenna pair within 256
// blocks.
fn antenna_density() {
    for antennas in [2_u64, 4, 8, 16] {
        let mut branches = Vec::new();
        let mut append = |a, b, element, extra: &[u64]| {
            let mut device = branch(branches.len() as u64, a, b, element);
            device.additional_ports.extend(extra.iter().copied().map(port));
            branches.push(device);
        };
        let mut next = antennas * 3;
        for i in 0..antennas {
            let n = i * 3;
            let load = if i == 0 {
                Element::SineVoltageSource { offset: 0.0, peak: 1.0, hertz: 100.0, phase: 0.0 }
            } else { Element::Resistor { ohms: 600.0 } };
            append(n, n + 1, load, &[]);
            append(n, n + 2, Element::Capacitor { farads: 1e-6 }, &[]);
            append(n + 2, n + 1, Element::Resistor { ohms: 50.0 }, &[]);
            for j in i + 1..antennas {
                let distance = ((j - i) * 8) as f64;
                let attenuation = distance.recip();
                let delay = (distance / 299_792_458.0).max(1e-6);
                append(n + 2, n + 1, Element::Windings(Windings {
                    tap: None,
                    primary_henries: 0.010001, secondary_henries: 0.01 * attenuation.powi(2) + 1e-6,
                    mutual_henries: 0.01 * attenuation, primary_ohms: 0.01, secondary_ohms: 0.01,
                }), &[next, next + 3]);
                append(next, next + 3, Element::Resistor { ohms: 1e9 }, &[]);
                append(next, next + 1, Element::Resistor { ohms: distance * 0.01 }, &[]);
                append(next + 1, next + 2, Element::Inductor { henries: 50.0 * delay }, &[]);
                append(next + 2, next + 3, Element::Capacitor { farads: delay / 50.0 }, &[]);
                append(next + 2, j * 3 + 2, Element::Resistor { ohms: 50.0 }, &[]);
                append(next + 3, j * 3 + 1, Element::Resistor { ohms: 0.01 }, &[]);
                next += 4;
            }
        }
        let count = branches.len();
        let build = Instant::now();
        let kernel = TransientCircuit::new(branches, None).expect("initialize passive antenna network");
        let revision = Revision { session: Id([9, antennas]), topology: 1, parameters: 1, sequence: 0 };
        let mut session = CircuitSession::new_adaptive(revision, kernel, 0.0001, accuracy()).unwrap();
        let build_ms = build.elapsed().as_secs_f64() * 1000.0;
        let start = Instant::now();
        let result = session.advance(Advance {
            revision: Revision { sequence: 1, ..revision }, accepted_start: 0.0, requested_end: 0.05,
            max_steps: i32::MAX as u32, budget_nanos: i64::MAX as u64,
        }, &AtomicBool::new(false)).unwrap();
        let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
        let outcome = interval_outcome(&result, session.pending_events().len());
        assert_eq!(outcome, "complete", "passive antenna network stopped early");
        println!("{{\"antennas\":{antennas},\"branches\":{count},\"outcome\":\"{outcome}\",\"build_ms\":{build_ms},\"elapsed_ms\":{elapsed_ms},\"physical_seconds\":{},\"accepted_steps\":{},\"rejected_steps\":{}}}",
            result.end, result.work.accepted_steps, result.work.rejected_steps);
    }
}

fn replay_details(session: &CircuitSession) -> String {
    let moments = &session.accepted().measurements.branches;
    let state = session.accepted();
    let locals: Vec<_> = state.operating_point.branches.iter().map(|p| &p.local_values).collect();
    let torque_seconds: Vec<_> = moments.iter().map(|m| m.torque_seconds).collect();
    let samples: Vec<Vec<_>> = state.sampler.channels.iter().map(|c| c.samples.iter().map(|&(t,v)| [t,v]).collect()).collect();
    let balance = state.energy.balance_residual_joules();
    let events: Vec<_> = session.pending_events().iter().map(|e| e.branch.0).collect();
    format!(r#""local_values":{locals:?},"torque_seconds":{torque_seconds:?},"waveforms":{samples:?},"energy_residual_joules":{balance},"event_branches":{events:?}"#)
}

// This native snapshot captures the 128 rpm plant immediately before protection operates.
fn plant_window(options: impl Iterator<Item = std::ffi::OsString>) {
    use em_protocol::contract::{IntervalInputs, WaveformSubscription};
    let mut early_window = false;
    let mut first_event = false;
    let mut sampling = None;
    let mut no_trips = false;
    for option in options {
        match option.to_str() {
            Some("--early-window") => early_window = true,
            Some("--first-event") => first_event = true,
            Some("--unsampled") => sampling = Some(false),
            Some("--sampled") => sampling = Some(true),
            Some("--no-trips") => no_trips = true,
            _ => panic!("Unknown plant-window option {option:?}"),
        }
    }
    let bytes: &[u8] = if early_window { include_bytes!("fixtures/plant-560.0s.bin") }
        else { include_bytes!("fixtures/plant-560.1s.bin") };
    let mut session = CircuitSession::from_snapshot(bytes).expect("restore captured plant");
    let start = session.accepted().time_seconds;
    let end = start + 0.05;
    if early_window {
        let inputs = session.interval_inputs().expect("captured shaft speeds").inputs.clone();
        let mut revision = session.revision();
        revision.sequence += 1;
        session.set_interval(IntervalInputs { revision, start, end, inputs }).expect("install early window");
    }
    let input = session.interval_inputs().expect("captured plant input window");
    assert_eq!((input.start, input.end), (start, end));
    if no_trips {
        // Keep the circuit equations, stored energy and contact positions fixed while moving
        // switching thresholds outside the measurement window.
        let changes = session.branches().iter().filter_map(|branch| {
            let mut model = branch.element.clone();
            match &mut model {
                Element::Relay { pickup_volts, dropout_volts, .. } => {
                    *pickup_volts = f64::MAX;
                    *dropout_volts = 0.0;
                }
                Element::Rectifier(m) => m.damage = false,
                Element::ThermalResistor(m) => m.trip_kelvin = None,
                Element::TappedWire(m) => m.thermal.trip_kelvin = None,
                _ => return None,
            }
            (model != branch.element).then_some((branch.id, model))
        }).collect();
        let mut revision = session.revision();
        revision.sequence += 1;
        revision.parameters += 1;
        session.set_parameters(revision, start, changes).expect("set benchmark control parameters");
    }
    if let Some(sampled) = sampling && sampled == session.accepted().sampler.channels.is_empty() {
        let channels = if sampled {
            let branch = session.branches().iter().find(|b| b.positive != b.negative).expect("plant voltage terminals");
            vec![em_protocol::contract::WaveformChannel { channel: Id([0,1]), positive: branch.positive, negative: branch.negative }]
        } else { Vec::new() };
        let mut revision = session.revision();
        revision.sequence += 1;
        session.subscribe_waveforms(WaveformSubscription {
            revision, channels, samples_per_second: 4096.0, bandwidth_hertz: 256.0, max_samples: 256,
        }).expect("configure benchmark subscriptions");
    }
    let sampled = !session.accepted().sampler.channels.is_empty();
    let window = Instant::now();
    loop {
        let mut revision = session.revision();
        revision.sequence += 1;
        let clock = Instant::now();
        let result = em_core::solver::profile_advance(|| session.advance_tick(Advance {
            revision, accepted_start: session.accepted().time_seconds, requested_end: end,
            max_steps: i32::MAX as u32, budget_nanos: i64::MAX as u64,
        }, &AtomicBool::new(false))).expect("advance plant");
        assert!(!no_trips || session.pending_events().is_empty(), "Plant control crossed a switching surface");
        let outcome = interval_outcome(&result, session.pending_events().len());
        let elapsed_ms = clock.elapsed().as_secs_f64() * 1e3;
        let window_ms = window.elapsed().as_secs_f64() * 1e3;
        let details = replay_details(&session);
        let p = em_core::solver::last_advance_profile();
        let actions: Vec<_> = session.pending_events().iter().map(|event| event.action).collect();
        let ids: Vec<_> = session.pending_events().iter().map(|event| event.id.0).collect();
        let profile = format!(r#""full_factorizations":{},"reduced_factorizations":{},"recovery_failures":{},"second_rhs_solves":{},"factor_hits":{},"newton_iterations":{},"backtracks":{},"linear_solves":{},"factorizations":{},"factor_us":{},"assembly_us":{},"substitution_us":{},"max_unknowns":{},"max_nonzeros":{},"localization_probe_us":{},"localization_validation_us":{},"localization_newton_iterations":{},"localization_factorizations":{}"#,
            p.factorizations-p.reduced_factorizations, p.reduced_factorizations, p.recovery_failures, p.second_rhs_solves, p.factor_hits,
            p.newton_iterations, p.backtracks, p.linear_solves, p.factorizations, p.factor_us,
            p.assembly_us, p.substitution_us, p.max_unknowns, p.max_nonzeros, p.localization_probe_us,
            p.localization_validation_us, p.localization_newton_iterations, p.localization_factorizations);
        println!(r#"{{"outcome":"{outcome}","sampled":{sampled},"frozen_thresholds":{no_trips},"window_start":{start},"end":{},"elapsed_ms":{elapsed_ms},"window_ms":{window_ms},"accepted_steps":{},"rejected_steps":{},"localization_trials":{},"event_ids":{ids:?},"event_actions":{actions:?},{profile},{details}}}"#,
            result.end, result.work.accepted_steps, result.work.rejected_steps, result.work.localization_trials);
        if session.pending_events().is_empty() || first_event { break; }
        let ids = session.pending_events().iter().map(|event| event.id).collect();
        revision.sequence += 1;
        revision.topology += 1;
        session.acknowledge_events(revision, result.end, ids).expect("acknowledge captured protection boundary");
        if result.end == end { break; }
    }
}

fn replay_snapshots(paths: impl Iterator<Item = std::ffi::OsString>) {
    for path in paths {
        let bytes = std::fs::read(&path).expect("read native snapshot");
        let mut session = CircuitSession::from_snapshot(&bytes).expect("restore accepted boundary");
        let base = session.revision();
        let start = session.accepted().time_seconds;
        let clock = Instant::now();
        let result = session.advance(Advance {
            revision: Revision { sequence: base.sequence.checked_add(1).unwrap(), ..base },
            accepted_start: start, requested_end: start + 0.05,
            max_steps: i32::MAX as u32, budget_nanos: i64::MAX as u64,
        }, &AtomicBool::new(false)).expect("advance restored boundary");
        let elapsed_ms = clock.elapsed().as_secs_f64() * 1000.0;
        let outcome = interval_outcome(&result, session.pending_events().len());
        let moments = &session.accepted().measurements.branches;
        let measurement_seconds = result.end - session.accepted().measurements.window_start;
        let voltage_squared_seconds: Vec<_> = moments.iter().map(|m| m.voltage_squared_seconds).collect();
        let current_squared_seconds: Vec<_> = moments.iter().map(|m| m.current_squared_seconds).collect();
        let net_work_joules: Vec<_> = moments.iter().map(|m| m.total_absorbed_joules - m.total_returned_joules).collect();
        let details = replay_details(&session);
        println!("{{\"session\":[{},{}],\"branches\":{},\"outcome\":\"{outcome}\",\"elapsed_ms\":{elapsed_ms},\"physical_seconds\":{},\"accepted_steps\":{},\"rejected_steps\":{},\"pending_events\":{},\"measurement_seconds\":{measurement_seconds},\"voltage_squared_seconds\":{voltage_squared_seconds:?},\"current_squared_seconds\":{current_squared_seconds:?},\"net_work_joules\":{net_work_joules:?},{details}}}",
            base.session.0[0], base.session.0[1], session.accepted().operating_point.branches.len(),
            result.end - start,
            result.work.accepted_steps, result.work.rejected_steps, session.pending_events().len());
    }
}

fn accuracy() -> Accuracy {
    Accuracy {
        relative: 1e-3,
        voltage: 1e-6,
        current: 1e-9,
        charge: 1e-15,
        flux: 1e-12,
        energy: 1e-9,
        minimum_step: 1e-12,
    }
}

fn alternator_benchmark(ticks: usize, primary_ohms: f64, scale: f64) {
    use em_core::model::regulator::Regulator;
    use em_protocol::contract::{BranchInterval, IntervalInputs, IntervalValue};
    let speed = 26.8082573;
    let mut branches = vec![
        branch(
            0,
            1,
            0,
            Element::AcShaft {
                k: 1.0,
                omega: speed,
                pole_pairs: 1.0,
                phase: 0.0,
            },
        ),
        branch(1, 1, 2, Element::Inductor { henries: 0.001 }),
        branch(
            2,
            2,
            0,
            Element::Windings(
                Windings::transformer(2.0, 0.1, 0.001, 0.001, primary_ohms, 0.1).unwrap(),
            ),
        ),
        branch(
            3,
            3,
            4,
            Element::Regulator(Regulator {
                target: 24.0,
                seconds: 0.2,
                minimum: 0.25,
                maximum: 2.0,
            }),
        ),
        branch(4, 5, 6, Element::Inductor { henries: 0.05 }),
        branch(5, 6, 4, Element::Meter { ohms: 0.01 }),
        branch(6, 7, 4, Element::Resistor { ohms: 16.0 }),
    ];
    branches[2].additional_ports.extend([port(3), port(4)]);
    branches[3].additional_ports.push(port(5));
    branches[5].additional_ports.push(port(7));
    let shaft = BranchInterval {
        branch: branches[0].id,
        owner: branches[0].positive.device,
        value: IntervalValue::ShaftSpeed(speed),
    };
    let kernel = TransientCircuit::new(branches, None).unwrap();
    let base = Revision {
        session: Id([8, 1]),
        topology: 1,
        parameters: 1,
        sequence: 0,
    };
    let mut limits = accuracy();
    assert!(scale > 0.0 && scale.is_finite());
    for tolerance in [&mut limits.relative, &mut limits.voltage, &mut limits.current, &mut limits.charge, &mut limits.flux, &mut limits.energy] {
        *tolerance *= scale;
    }
    let mut session = CircuitSession::new_adaptive(base, kernel, 0.05, limits).unwrap();
    for tick in 0..ticks {
        let start = session.accepted().time_seconds;
        let end = start + 0.05;
        let revision = Revision {
            sequence: session.revision().sequence + 1,
            ..base
        };
        session
            .set_interval(IntervalInputs {
                revision,
                start,
                end,
                inputs: vec![shaft.clone()],
            })
            .unwrap();
        let clock = Instant::now();
        let result = session
            .advance(
                Advance {
                    revision: Revision {
                        sequence: revision.sequence + 1,
                        ..revision
                    },
                    accepted_start: start,
                    requested_end: end,
                    max_steps: i32::MAX as u32,
                    budget_nanos: i64::MAX as u64,
                },
                &AtomicBool::new(false),
            )
            .unwrap();
        let ms = clock.elapsed().as_secs_f64() * 1000.0;
        assert_eq!(
            interval_outcome(&result, session.pending_events().len()),
            "complete"
        );
        let m = &session.accepted().measurements.branches;
        println!(
            "{{\"tick\":{tick},\"ms\":{ms},\"steps\":{},\"volts\":{},\"amps\":{},\"load_joules\":{},\"shaft_work\":{},{}}}",
            result.work.accepted_steps,
            (m[6].voltage_squared_seconds / 0.05).sqrt(),
            (m[6].current_squared_seconds / 0.05).sqrt(),
            m[6].total_dissipated_joules,
            m[0].total_returned_joules - m[0].total_absorbed_joules, replay_details(&session)
        );
    }
    CircuitSession::from_snapshot(&session.snapshot()).expect("restore alternator endpoint");
}

fn main() {
    let helper = std::env::var_os("EM_TRAJECTORY_WORKER").is_some_and(|v| v == "1");
    em_core::solver::with_helper(helper, benchmark)
}

fn benchmark() {
    let mut args = std::env::args_os().skip(1);
    let mode = args.next();
    if mode.as_deref() == Some(std::ffi::OsStr::new("--bridge-stages")) {
        let seed = std::fs::read(args.next().expect("checkpoint path")).unwrap();
        let h = args.next().expect("step seconds").to_str().unwrap().parse().unwrap();
        let mut session = CircuitSession::from_snapshot(&seed).unwrap();
        bridge_comparison::prescribe(&mut session, 256.0);
        let [full, reduced] = session.compare_bridge_stages(h).unwrap();
        for (stage, (a, b)) in full.iter().zip(&reduced).enumerate() {
            let mut worst = 0.0_f64;
            for (p, q) in a.branches.iter().zip(&b.branches) {
                assert_eq!(p.id, q.id);
                for (&x, &y) in p.local_values.iter().zip(&q.local_values) {
                    let error = (x-y).abs() / (1e-8 + 1e-8*x.abs());
                    assert!(error.is_finite());
                    worst = worst.max(error);
                }
            }
            println!("stage={stage} step_seconds={h} worst_tolerance_multiple={worst}");
            assert!(worst <= 1.0);
        }
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--bridge-comparison")) {
        em_core::model::catalog::initialize();
        bridge_comparison::run(args, |bytes, level| CircuitSession::replay_snapshot(bytes, level).unwrap(),
            || em_core::solver::last_advance_profile().radau_fallbacks);
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--plant-window")) {
        plant_window(args);
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--replay")) {
        let path = args.next().expect("accepted snapshot");
        let ticks = args
            .next()
            .map_or(4, |s| s.to_str().unwrap().parse::<usize>().unwrap());
        let mut session = CircuitSession::from_snapshot(&std::fs::read(path).unwrap()).unwrap();
        let mut inputs = session
            .interval_inputs()
            .expect("checkpoint with prescribed boundaries")
            .inputs
            .clone();
        if let Some(speed) = args.next() {
            let speed: f64 = speed.to_str().unwrap().parse().expect("shaft speed in rad/s");
            for input in &mut inputs {
                match &mut input.value {
                    em_protocol::contract::IntervalValue::ShaftSpeed(omega) => *omega = speed,
                    _ => panic!("speed override requires shaft inputs"),
                }
            }
        }
        for tick in 0..ticks {
            let start = session.accepted().time_seconds;
            let end = start + 0.05;
            let mut revision = session.revision();
            revision.sequence += 1;
            session
                .set_interval(em_protocol::contract::IntervalInputs {
                    revision,
                    start,
                    end,
                    inputs: inputs.clone(),
                })
                .unwrap();
            revision.sequence += 1;
            let clock = Instant::now();
            let result = session
                .advance(
                    Advance {
                        revision,
                        accepted_start: start,
                        requested_end: end,
                        max_steps: i32::MAX as u32,
                        budget_nanos: i64::MAX as u64,
                    },
                    &AtomicBool::new(false),
                )
                .unwrap();
            let ms = clock.elapsed().as_secs_f64() * 1000.0;
            let events = session.pending_events();
            if interval_outcome(&result, events.len()) == "protection_event" {
                let branches: Vec<_> = events.iter().map(|e| e.branch.0).collect();
                let owners: Vec<_> = events.iter().map(|e| {
                    let branch = session.branches().iter().find(|b| b.id == e.branch).unwrap();
                    let owner = branch.positive.device;
                    [owner.instance.0[0], owner.instance.0[1], owner.generation]
                }).collect();
                println!("{{\"tick\":{tick},\"ms\":{ms},\"event_time\":{},\"event_branches\":{branches:?},\"owners\":{owners:?}}}", result.end);
                break;
            }
            let m = &session.accepted().measurements.branches;
            let rms: Vec<_> = m
                .iter()
                .map(|m| (m.voltage_squared_seconds / 0.05).sqrt())
                .collect();
            let amps: Vec<_> = m
                .iter()
                .map(|m| (m.current_squared_seconds / 0.05).sqrt())
                .collect();
            let work: Vec<_> = m
                .iter()
                .map(|m| m.total_absorbed_joules - m.total_returned_joules)
                .collect();
            println!(
                "{{\"tick\":{tick},\"ms\":{ms},\"steps\":{},\"volts\":{rms:?},\"amps\":{amps:?},\"work\":{work:?}}}",
                result.work.accepted_steps
            );
        }
        let restored = CircuitSession::from_snapshot(&session.snapshot()).expect("restore replay endpoint");
        assert_eq!(restored.pending_events(), session.pending_events());
        assert_eq!(restored.accepted(), session.accepted());
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--alternator")) {
        let ticks = args
            .next()
            .map_or(120, |s| s.to_str().unwrap().parse().unwrap());
        let primary_ohms = args
            .next()
            .map_or(0.2, |s| s.to_str().unwrap().parse().unwrap());
        let scale = args.next().map_or(1.0, |s| s.to_str().unwrap().parse().unwrap());
        alternator_benchmark(ticks, primary_ohms, scale);
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--antennas")) {
        antenna_density();
        return;
    }
    if mode.as_deref() == Some(std::ffi::OsStr::new("--snapshot")) {
        let first = args.next().expect("--snapshot needs at least one native snapshot file");
        replay_snapshots(std::iter::once(first).chain(args));
        return;
    }
    let full = mode.as_deref() == Some(std::ffi::OsStr::new("--full-interval"));
    assert!(mode.is_none() || full, "expected --full-interval, --antennas, --snapshot, --replay or --alternator");
    let budget_ms = if full { "null" } else { "20" };
    let topologies = [
        "chain",
        "grid",
        "rc_islands",
        "nonlinear_islands",
        "coupled_islands",
    ];
    let selected = args.next().map(|s| s.into_string().expect("topology name"));
    if let Some(name) = &selected {
        assert!(topologies.contains(&name.as_str()), "unknown topology {name}");
    }
    let requests: u32 = args.next().map_or(20, |s| s.to_str().unwrap().parse().expect("positive request count"));
    assert!(requests > 0 && args.next().is_none(), "expected topology and positive request count");
    for name in topologies.into_iter().filter(|name| selected.as_deref().is_none_or(|s| s == *name)) {
        let (branches, initial) = setup(name);
        let build = Instant::now();
        let kernel = TransientCircuit::new(branches, initial.as_ref()).unwrap();
        let revision = Revision {
            session: Id([8, 1]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        };
        let mut session = CircuitSession::new_adaptive(revision, kernel, 0.0001, accuracy()).unwrap();
        let build_ms = build.elapsed().as_secs_f64() * 1000.0;
        let mut elapsed = vec![];
        let mut rejected = 0;
        let mut accepted = 0;
        let mut discarded = 0;
        let mut error: f64 = 0.0;
        for sequence in 1..=u64::from(requests) {
            let start = session.accepted().time_seconds;
            let request = Advance {
                revision: Revision {
                    sequence,
                    ..revision
                },
                accepted_start: start,
                requested_end: start + 0.05,
                max_steps: if full { i32::MAX as u32 } else { 10000 },
                budget_nanos: if full { i64::MAX as u64 } else { 20_000_000 },
            };
            let clock = Instant::now();
            let result = session.advance(request, &AtomicBool::new(false)).unwrap();
            elapsed.push(clock.elapsed().as_secs_f64() * 1000.0);
            if full {
                assert_eq!(interval_outcome(&result, session.pending_events().len()), "complete");
            } else if result.diagnostic.status != Status::Partial {
                interval_outcome(&result, session.pending_events().len());
            }
            accepted += u64::from(result.work.accepted_steps);
            rejected += u64::from(result.work.rejected_steps);
            discarded += u64::from(result.work.discarded_steps);
            error = error.max(reference_error(name, &session));
        }
        elapsed.sort_by(f64::total_cmp);
        let percentile = |percent: usize| elapsed[(elapsed.len() * percent).div_ceil(100) - 1];
        let time = session.accepted().time_seconds;
        let snapshot = session.snapshot().len();
        let unit = if name == "chain" || name == "coupled_islands" {
            "A"
        } else {
            "V"
        };
        println!(
            "{{\"topology\":\"{name}\",\"branches\":1024,\"build_ms\":{build_ms},\"request_count\":{requests},\"request_budget_ms\":{budget_ms},\"p50_ms\":{},\"p95_ms\":{},\"p99_ms\":{},\"accepted_steps\":{accepted},\"rejected_steps\":{rejected},\"discarded_steps\":{discarded},\"accepted_seconds\":{time},\"uncompleted_seconds\":{},\"max_reference_error\":{error},\"error_unit\":\"{unit}\",\"energy_residual_joules\":{},\"snapshot_bytes\":{snapshot}}}",
            percentile(50),
            percentile(95),
            percentile(99),
            (f64::from(requests) * 0.05 - time).max(0.0),
            session.accepted().energy.balance_residual_joules()
        );
    }
}
