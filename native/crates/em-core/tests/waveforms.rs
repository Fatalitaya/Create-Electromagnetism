use em_core::{
    model::element::Element,
    session::circuit::CircuitSession,
    solver::{dc::DcBranch, transient::TransientCircuit},
};
use em_protocol::contract::{
    Advance, DeviceId, Id, PortId, Revision, SampleQuality, WaveformChannel, WaveformSubscription,
};
use std::sync::atomic::AtomicBool;

fn revision(sequence: u64) -> Revision {
    Revision {
        session: Id([1, 90]),
        topology: 1,
        parameters: 1,
        sequence,
    }
}
fn port(terminal: u32) -> PortId {
    PortId {
        device: DeviceId {
            instance: Id([2, 1]),
            generation: 0,
        },
        terminal,
    }
}
fn circuit(hertz: f64, step: f64) -> CircuitSession {
    let branches = [
        Element::SineVoltageSource {
            offset: 0.0,
            peak: 1.0,
            hertz,
            phase: 0.0,
        },
        Element::Resistor { ohms: 1.0 },
    ]
    .into_iter()
    .enumerate()
    .map(|(i, element)| DcBranch {
        additional_ports: Vec::new(),
        id: Id([3, i as u64]),
        positive: port(0),
        negative: port(1),
        element,
    })
    .collect();
    CircuitSession::new(
        revision(0),
        TransientCircuit::new(branches, None).unwrap(),
        step,
    )
    .unwrap()
}
fn subscription(capacity: u32) -> WaveformSubscription {
    WaveformSubscription {
        revision: revision(1),
        channels: vec![WaveformChannel {
            channel: Id([9, 1]),
            positive: port(0),
            negative: port(1),
        }],
        samples_per_second: 100.0,
        bandwidth_hertz: 10.0,
        max_samples: capacity,
    }
}
fn advance(s: &mut CircuitSession, sequence: u64, end: f64) {
    let request = Advance {
        revision: revision(sequence),
        accepted_start: s.accepted().time_seconds,
        requested_end: end,
        max_steps: 100_000,
        budget_nanos: 30_000_000_000,
    };
    let result = s.advance(request, &AtomicBool::new(false)).unwrap();
    assert_eq!(result.end, end, "{}", result.diagnostic.reason);
}

#[test]
fn filtered_waveform_converges_to_independent_continuous_transfer() {
    let pole = std::f64::consts::TAU * 10.0 / (2.0_f64.powf(0.25) - 1.0).sqrt();
    let omega = std::f64::consts::TAU * 5.0;
    let gain = (1.0 + (omega / pole).powi(2)).powi(-2);
    let phase = -4.0 * (omega / pole).atan();
    let mut errors = Vec::new();
    for step in [0.0001, 0.00005] {
        let mut s = circuit(5.0, step);
        s.subscribe_waveforms(subscription(100)).unwrap();
        advance(&mut s, 2, 1.0);
        let observations = s.observations(revision(2)).unwrap();
        let waveform = &observations.waveforms[0];
        assert_eq!(waveform.samples.len(), 100);
        assert_eq!(waveform.dropped_samples, 0);
        let mut error = 0.0_f64;
        for (i, sample) in waveform.samples.iter().enumerate() {
            assert_eq!(sample.time, (i + 1) as f64 / 100.0);
            assert_eq!(sample.quality, SampleQuality::Reconstructed);
            if sample.time > 0.5 {
                error = error.max(
                    (sample.value.unwrap() - gain * (omega * sample.time + phase).sin()).abs(),
                );
            }
        }
        assert!(error < 0.01, "{error}");
        errors.push(error);
    }
    assert!(errors[1] < 0.6 * errors[0], "{errors:?}");
}

#[test]
fn thirty_two_audio_channels_keep_two_complete_ticks_without_drops() {
    let mut s = circuit(0.0, 0.001);
    let mut sub = subscription(1024);
    sub.samples_per_second = 16000.0;
    sub.bandwidth_hertz = 2000.0;
    sub.channels = (0..32)
        .map(|i| WaveformChannel {
            channel: Id([9, i]),
            positive: port(0),
            negative: port(1),
        })
        .collect();
    s.subscribe_waveforms(sub).unwrap();
    for (sequence, end) in [(2, 0.05), (3, 0.1)] {
        advance(&mut s, sequence, end);
        let observations = s.observations(revision(sequence)).unwrap();
        assert_eq!(observations.waveforms.len(), 32);
        for waveform in observations.waveforms {
            assert_eq!(waveform.dropped_samples, 0);
            assert_eq!(waveform.samples.len(), 800);
            for (i, sample) in waveform.samples.iter().enumerate() {
                let expected = ((sequence - 2) * 800 + i as u64 + 1) as f64 / 16000.0;
                assert_eq!(sample.time, expected);
            }
        }
    }
}

#[test]
fn antialias_filter_suppresses_high_frequency_before_decimation() {
    let mut s = circuit(205.0, 0.0001);
    s.subscribe_waveforms(subscription(100)).unwrap();
    advance(&mut s, 2, 1.0);
    let waveforms = s.observations(revision(2)).unwrap().waveforms;
    let peak = waveforms[0]
        .samples
        .iter()
        .filter(|s| s.time > 0.5)
        .map(|s| s.value.unwrap().abs())
        .fold(0.0_f64, f64::max);
    // Direct 100 Hz sampling aliases the 1 V input to a 5 Hz signal of nearly 1 V.
    assert!(peak < 0.001, "aliased peak={peak}");
}

#[test]
fn overflow_timestamps_checkpoint_and_subscription_replay_are_transactional() {
    let mut s = circuit(5.0, 0.0001);
    let sub = subscription(3);
    s.subscribe_waveforms(sub.clone()).unwrap();
    let initial = s.snapshot();
    s.subscribe_waveforms(sub).unwrap();
    assert_eq!(s.snapshot(), initial);
    advance(&mut s, 2, 0.37);
    let waveforms = s.observations(revision(2)).unwrap().waveforms;
    assert_eq!(waveforms[0].samples.len(), 3);
    assert_eq!(waveforms[0].dropped_samples, 34);
    assert_eq!(waveforms[0].samples[0].time, 0.35);
    let snapshot = s.snapshot();
    let mut restored = CircuitSession::from_snapshot(&snapshot).unwrap();
    assert_eq!(restored.snapshot(), snapshot);
    assert!(restored.observations(revision(2)).is_err());
    assert!(restored.subscribe_waveforms(subscription(3)).is_err());
    advance(&mut s, 3, 0.6);
    advance(&mut restored, 3, 0.6);
    assert_eq!(s.snapshot(), restored.snapshot());
    let a = s.observations(revision(3)).unwrap().waveforms;
    let b = restored.observations(revision(3)).unwrap().waveforms;
    for (a, b) in a[0].samples.iter().zip(&b[0].samples) {
        assert_eq!(a.time, b.time);
        assert_eq!(a.value, b.value);
    }
    let before = s.snapshot();
    let mut invalid = subscription(100);
    invalid.revision = revision(4);
    invalid.bandwidth_hertz = 90.0;
    assert!(s.subscribe_waveforms(invalid).is_err());
    assert_eq!(s.snapshot(), before);
}

#[test]
fn probing_independent_gauges_is_rejected_without_mutation() {
    let branches = (0..2)
        .map(|n| DcBranch {
            additional_ports: Vec::new(),
            id: Id([3, n]),
            positive: port(2 * n as u32),
            negative: port(2 * n as u32 + 1),
            element: Element::Resistor { ohms: 1.0 },
        })
        .collect();
    let mut s = CircuitSession::new(
        revision(0),
        TransientCircuit::new(branches, None).unwrap(),
        0.001,
    )
    .unwrap();
    let before = s.snapshot();
    let mut sub = subscription(100);
    sub.channels[0].negative = port(3);
    assert!(
        s.subscribe_waveforms(sub)
            .unwrap_err()
            .reason
            .contains("floating islands")
    );
    assert_eq!(s.snapshot(), before);
}
