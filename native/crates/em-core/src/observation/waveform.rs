//! Accepted-state four-pole antialias filter and bounded uniform output sampling.
//! Pole states use the same backward-Euler trajectory as the electrical state.
//! Samples are filtered/reconstructed, carry physical timestamps and include filter delay.
use crate::{model::element::rejected, solver::dc::DcOperatingPoint};
use em_protocol::contract::{
    Diagnostic, SampleQuality, Waveform, WaveformChannel, WaveformSample, WaveformSubscription,
};
use std::collections::{HashSet, VecDeque};

pub const MAX_CHANNELS: usize = 32;
pub const MAX_SAMPLES: usize = em_protocol::contract::MAX_WAVEFORM_SAMPLES;

#[derive(Clone, Debug, PartialEq)]
pub struct ChannelState {
    pub channel: WaveformChannel,
    pub poles: [f64; 4],
    pub samples: VecDeque<(f64, f64)>,
    pub dropped: u64,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Sampler {
    pub origin: f64,
    pub next_index: u64,
    pub samples_per_second: f64,
    pub bandwidth_hertz: f64,
    pub capacity: usize,
    pub channels: Vec<ChannelState>,
}

impl Sampler {
    pub fn new(
        subscription: &WaveformSubscription,
        time: f64,
        point: &DcOperatingPoint,
    ) -> Result<Self, Diagnostic> {
        let rate = subscription.samples_per_second;
        let bandwidth = subscription.bandwidth_hertz;
        let capacity = subscription.max_samples as usize;
        if !time.is_finite()
            || time < 0.0
            || !rate.is_finite()
            || !(0.001..=192000.0).contains(&rate)
            || !bandwidth.is_finite()
            || bandwidth <= 0.0
            || bandwidth > rate / 8.0
            || capacity == 0
            || subscription.channels.len() > MAX_CHANNELS
            || subscription
                .channels
                .len()
                .checked_mul(capacity)
                .is_none_or(|count| count > MAX_SAMPLES)
        {
            return Err(rejected("Invalid waveform bandwidth or resource limits"));
        }
        if time + 1.0 / rate <= time {
            return Err(rejected("Waveform clock exceeds time resolution"));
        }
        let mut seen = HashSet::new();
        let mut channels = Vec::new();
        for channel in &subscription.channels {
            if !seen.insert(channel.channel) || channel.positive == channel.negative {
                return Err(rejected("Duplicate waveform identity or collapsed probe"));
            }
            // Cross-island voltage depends on arbitrary gauges and is not observable.
            // Connectivity is checked by the session against the frozen circuit topology.
            let voltage = probe(channel, point)?;
            channels.push(ChannelState {
                channel: channel.clone(),
                poles: [voltage; 4],
                samples: VecDeque::new(),
                dropped: 0,
            });
        }
        Ok(Self {
            origin: time,
            next_index: 1,
            samples_per_second: rate,
            bandwidth_hertz: bandwidth,
            capacity,
            channels,
        })
    }

    fn pole_rate(&self) -> f64 {
        std::f64::consts::TAU * self.bandwidth_hertz / (2.0_f64.powf(0.25) - 1.0).sqrt()
    }

    pub fn step_limit(&self) -> f64 {
        if self.channels.is_empty() {
            f64::INFINITY
        } else {
            (1.0 / (8.0 * self.samples_per_second)).min(1.0 / (32.0 * self.pole_rate()))
        }
    }

    pub fn next_time(&self) -> f64 {
        if self.channels.is_empty() {
            f64::INFINITY
        } else {
            self.origin + self.next_index as f64 / self.samples_per_second
        }
    }

    /// Candidate owns filter state and only samples generated in its interval.
    pub fn candidate(&self) -> Self {
        Self {
            channels: self.channels.iter().map(|c| ChannelState {
                channel: c.channel.clone(), poles: c.poles,
                samples: VecDeque::new(), dropped: 0,
            }).collect(),
            origin: self.origin, next_index: self.next_index,
            samples_per_second: self.samples_per_second,
            bandwidth_hertz: self.bandwidth_hertz, capacity: self.capacity,
        }
    }

    /// Called only after the trajectory is accepted; rejected samples never enter history.
    pub fn commit_samples(&mut self, previous: &mut Self) {
        for (next, old) in self.channels.iter_mut().zip(&mut previous.channels) {
            std::mem::swap(&mut next.samples, &mut old.samples);
            next.dropped += old.dropped;
            for sample in old.samples.drain(..) {
                if next.samples.len() == self.capacity {
                    next.samples.pop_front();
                    next.dropped = next.dropped.saturating_add(1);
                }
                next.samples.push_back(sample);
            }
        }
    }

    pub fn reset_output(&mut self) {
        for channel in &mut self.channels {
            channel.samples.clear();
            channel.dropped = 0;
        }
    }

    pub fn integrate(
        &mut self,
        start: f64,
        end: f64,
        point: &DcOperatingPoint,
    ) -> Result<(), Diagnostic> {
        self.integrate_solver(start, end, point).map_err(Into::into)
    }

    pub(crate) fn integrate_solver(
        &mut self, start: f64, end: f64, point: &DcOperatingPoint,
    ) -> Result<(), crate::solver::SolveError> {
        if self.channels.is_empty() {
            return Ok(());
        }
        let h = end - start;
        let next = self.next_time();
        if !h.is_finite() || h <= 0.0 || next <= start || end > next {
            return Err(crate::solver::permanent("Waveform candidate crossed an output boundary"));
        }
        let alpha = h * self.pole_rate();
        for channel in &mut self.channels {
            let mut value = probe(&channel.channel, point)?;
            for pole in &mut channel.poles {
                *pole = (*pole + alpha * value) / (1.0 + alpha);
                value = *pole;
                if !value.is_finite() {
                    return Err(rejected("Non-finite waveform filter candidate").into());
                }
            }
            if end == next {
                if channel.samples.len() == self.capacity {
                    channel.samples.pop_front();
                    channel.dropped = channel
                        .dropped
                        .checked_add(1)
                        .ok_or_else(|| crate::solver::permanent("Waveform overflow counter exhausted"))?;
                }
                channel.samples.push_back((end, value));
            }
        }
        if end == next {
            self.next_index = self
                .next_index
                .checked_add(1)
                .ok_or_else(|| crate::solver::permanent("Waveform sample clock exhausted"))?;
            if self.next_time() <= end {
                return Err(crate::solver::permanent("Waveform clock exceeds time resolution"));
            }
        }
        Ok(())
    }

    pub fn waveforms(&self) -> Vec<Waveform> {
        self.channels
            .iter()
            .map(|channel| Waveform {
                channel: channel.channel.channel,
                positive: channel.channel.positive,
                negative: channel.channel.negative,
                samples: channel
                    .samples
                    .iter()
                    .map(|&(time, value)| WaveformSample {
                        time,
                        value: Some(value),
                        quality: SampleQuality::Reconstructed,
                    })
                    .collect(),
                dropped_samples: channel.dropped,
            })
            .collect()
    }

    pub fn write_snapshot(&self, w: &mut em_protocol::wire::Writer) {
        w.u32(u32::from(!self.channels.is_empty()));
        if self.channels.is_empty() {
            return;
        }
        w.f64(self.origin);
        w.u64(self.next_index);
        w.f64(self.samples_per_second);
        w.f64(self.bandwidth_hertz);
        w.u32(self.capacity as u32);
        w.u32(self.channels.len() as u32);
        for state in &self.channels {
            w.id(state.channel.channel);
            w.port(state.channel.positive);
            w.port(state.channel.negative);
            for value in state.poles {
                w.f64(value);
            }
        }
    }

    /// Persist filter history/clock, not already-published output samples.
    pub fn read_snapshot(
        r: &mut em_protocol::wire::Reader<'_>,
        revision: em_protocol::contract::Revision,
        time: f64,
        point: &DcOperatingPoint,
    ) -> Result<Self, String> {
        match r.u32()? {
            0 => return Ok(Self::default()),
            1 => {}
            _ => return Err("Invalid waveform snapshot flag".into()),
        }
        let origin = r.f64()?;
        let next_index = r.u64()?;
        let samples_per_second = r.f64()?;
        let bandwidth_hertz = r.f64()?;
        let max_samples = r.u32()?;
        let count = r.u32()? as usize;
        if count == 0 || count > MAX_CHANNELS || origin < 0.0 || origin > time || next_index == 0 {
            return Err("Invalid waveform snapshot clock or count".into());
        }
        let mut channels = Vec::new();
        let mut poles = Vec::new();
        for _ in 0..count {
            channels.push(WaveformChannel {
                channel: r.id()?,
                positive: r.port()?,
                negative: r.port()?,
            });
            poles.push([r.f64()?, r.f64()?, r.f64()?, r.f64()?]);
        }
        let mut sampler = Self::new(
            &WaveformSubscription {
                revision,
                channels,
                samples_per_second,
                bandwidth_hertz,
                max_samples,
            },
            origin,
            point,
        )
        .map_err(|d| d.reason)?;
        sampler.next_index = next_index;
        if !sampler.next_time().is_finite()
            || sampler.next_time() <= time
            || origin + (next_index - 1) as f64 / samples_per_second > time
        {
            return Err("Invalid waveform snapshot sample cursor".into());
        }
        for (channel, poles) in sampler.channels.iter_mut().zip(poles) {
            channel.poles = poles;
        }
        Ok(sampler)
    }
}

fn probe(channel: &WaveformChannel, point: &DcOperatingPoint) -> Result<f64, Diagnostic> {
    let a = point
        .voltages
        .get(&channel.positive)
        .ok_or_else(|| rejected("Unknown positive waveform terminal"))?;
    let b = point
        .voltages
        .get(&channel.negative)
        .ok_or_else(|| rejected("Unknown negative waveform terminal"))?;
    let voltage = a - b;
    if !voltage.is_finite() {
        return Err(rejected("Non-finite waveform voltage"));
    }
    Ok(voltage)
}
