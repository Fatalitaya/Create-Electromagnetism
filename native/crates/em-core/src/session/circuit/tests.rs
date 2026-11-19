use super::*;
use crate::{
    model::element::Element,
    solver::dc::DcBranch,
    state::{InitialConditions, InitialStorage},
};
use em_protocol::contract::{DeviceId, Id, PortId};

fn session(minimum_step: f64) -> CircuitSession {
    let port = |terminal| PortId {
        device: DeviceId {
            instance: Id([0, 1]),
            generation: 0,
        },
        terminal,
    };
    let branches = [
        Element::DcVoltageSource { volts: 4.0 },
        Element::from_parameters(13, &[2.0, 0.0, 4.0, 0.5, 300.0, 301.0]).unwrap(),
    ]
    .into_iter()
    .enumerate()
    .map(|(i, element)| DcBranch {
        id: Id([1, i as u64]),
        positive: port(0),
        negative: port(1),
        additional_ports: vec![],
        element,
    })
    .collect();
    let kernel = TransientCircuit::new(
        branches,
        Some(&InitialConditions::from([(
            Id([1, 1]),
            InitialStorage::TemperatureKelvin(300.0),
        )])),
    )
    .unwrap();
    CircuitSession::new_adaptive(
        Revision {
            session: Id([9, 1]),
            topology: 1,
            parameters: 1,
            sequence: 0,
        },
        kernel,
        1.0,
        Accuracy {
            relative: 0.1,
            voltage: 0.1,
            current: 0.1,
            charge: 0.1,
            flux: 0.1,
            energy: 0.1,
            minimum_step,
        },
    )
    .unwrap()
}

#[test]
fn localization_failures_retry_without_publishing_discarded_candidates() {
    // Exercise the transaction using deterministic solver/accuracy failures at
    // the trial boundary, rather than depending on a platform-specific Newton failure.
    for solver_failure in [false, true] {
        for limit in ["recover", "minimum", "work", "cancel", "deadline"] {
            let mut session = session(if limit == "minimum" { 1.0 } else { 1e-6 });
            let before = session.accepted().clone();
            let cancel = AtomicBool::new(false);
            let request = Advance {
                revision: Revision {
                    sequence: 1,
                    ..session.revision()
                },
                accepted_start: 0.0,
                requested_end: 2.0,
                max_steps: if limit == "work" { 2 } else { 200 },
                budget_nanos: if limit == "deadline" {
                    100_000_000
                } else {
                    10_000_000_000
                },
            };
            let mut injected = false;
            let result = session
                .advance_with_trial(request, &cancel, None, false, |kernel, _, end, _, local, _, _| Poll::Ready((|| {
                    let candidate = kernel.trial(end)?;
                    if local && !injected {
                        injected = true;
                        if limit == "cancel" {
                            cancel.store(true, Ordering::Release);
                        }
                        if limit == "deadline" {
                            std::thread::sleep(Duration::from_millis(110));
                        }
                        if solver_failure {
                            return Err(rejected("injected localization solver failure").into());
                        }
                        return Ok(Trial::single(candidate, 2.0));
                    }
                    Ok(Trial::single(candidate, 0.0))
                })()))
                .unwrap();
            assert!(injected);
            assert_eq!(result.diagnostic.status,
                if limit == "minimum" { Status::Rejected } else { Status::Partial });
            assert!(result.work.rejected_steps >= 1);
            if limit == "recover" {
                assert_eq!(session.pending_events().len(), 1);
                assert!((session.pending_events()[0].temperature_kelvin - 301.0).abs() < 1e-8);
                let saved = session.snapshot();
                assert_eq!(session.advance(request, &cancel).unwrap().end, result.end);
                assert_eq!(session.snapshot(), saved);
            } else {
                assert_eq!(session.accepted(), &before);
                assert!(session.pending_events().is_empty());
            }
        }
    }
}
