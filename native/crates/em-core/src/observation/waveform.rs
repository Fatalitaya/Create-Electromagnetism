//! Integrate the four filter poles along linear voltage ramps between electrical samples, then
//! sample on a uniform clock. Samples carry physical timestamps and include filter delay.
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
    // Caller and trial can round the same grid instant to slightly different endpoints.
    pub(crate) aligned_time: Option<f64>,
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
            // Disconnected islands have independent potential references. The session checks probe
            // connectivity against frozen topology.
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
            aligned_time: None,
            samples_per_second: rate,
            bandwidth_hertz: bandwidth,
            capacity,
            channels,
        })
    }

    fn pole_rate(&self) -> f64 {
        crate::model::catalog::pole_rate(self.bandwidth_hertz)
    }

    pub fn step_limit(&self) -> f64 {
        if self.channels.is_empty() {
            f64::INFINITY
        } else {
            1.0 / (8.0 * self.samples_per_second)
        }
    }

    pub fn next_time(&self) -> f64 {
        if self.channels.is_empty() {
            f64::INFINITY
        } else {
            self.aligned_time.unwrap_or_else(|| self.origin + self.next_index as f64 / self.samples_per_second)
        }
    }

    pub(crate) fn align_boundary(&mut self, end: f64) {
        self.aligned_time = None;
        let grid = self.next_time();
        if grid.is_finite() && grid.to_bits().abs_diff(end.to_bits()) <= 2
            && (grid - end).abs() < 0.25 / self.samples_per_second
        {
            self.aligned_time = Some(end);
        }
    }

    /// Each candidate owns its filter state and the samples produced within its interval.
    pub fn candidate(&self) -> Self {
        Self {
            channels: self.channels.iter().map(|c| ChannelState {
                channel: c.channel.clone(), poles: c.poles,
                samples: VecDeque::new(), dropped: 0,
            }).collect(),
            origin: self.origin, next_index: self.next_index,
            aligned_time: self.aligned_time,
            samples_per_second: self.samples_per_second,
            bandwidth_hertz: self.bandwidth_hertz, capacity: self.capacity,
        }
    }

    /// Append samples to history only after accepting the trajectory.
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
        let sample = self.sample_at_end(start, end)?;
        self.integrate_step(end - start, sample, point, point).map_err(Into::into)
    }

    pub(crate) fn sample_at_end(&self, start: f64, end: f64) -> Result<Option<f64>, crate::solver::SolveError> {
        if self.channels.is_empty() {
            return Ok(None);
        }
        let h = end - start;
        let next = self.next_time();
        if !h.is_finite() || h <= 0.0 || next <= start || end > next {
            return Err(crate::solver::permanent("Waveform candidate crossed an output boundary"));
        }
        Ok((end == next).then_some(next))
    }

    pub(crate) fn integrate_step(
        &mut self, h: f64, sample: Option<f64>, start: &DcOperatingPoint, end: &DcOperatingPoint,
    ) -> Result<(), crate::solver::SolveError> {
        if self.channels.is_empty() { return Ok(()); }
        // All four trapezoidal stages share the substep endpoints and input ramp. The output grid
        // limits step size to one sample and bandwidth to one eighth of the sample rate.
        let duration_in_pole_units = h * self.pole_rate();
        let substeps = (32.0 * duration_in_pole_units).ceil().max(1.0) as usize;
        let half_step = 0.5 * duration_in_pole_units / substeps as f64;
        let gain = half_step / (1.0 + half_step);
        for channel in &mut self.channels {
            let first = probe(&channel.channel, start)?;
            let last = probe(&channel.channel, end)?;
            let mut input = first;
            for step in 1..=substeps {
                let mut left = input;
                input = if step == substeps { last } else {
                    first + (last - first) * (step as f64 / substeps as f64)
                };
                let mut right = input;
                for pole in &mut channel.poles {
                    let previous = *pole;
                    *pole += gain * ((left - previous) + (right - previous));
                    left = previous;
                    right = *pole;
                }
            }
            if !channel.poles.iter().all(|p| p.is_finite()) {
                return Err(rejected("Non-finite waveform filter candidate").into());
            }
        }
        self.finish_sample(sample)
    }

    fn finish_sample(&mut self, sample: Option<f64>) -> Result<(), crate::solver::SolveError> {
        if let Some(time) = sample {
            for channel in &mut self.channels {
                if channel.samples.len() == self.capacity {
                    channel.samples.pop_front();
                    channel.dropped = channel.dropped.checked_add(1)
                        .ok_or_else(|| crate::solver::permanent("Waveform overflow counter exhausted"))?;
                }
                channel.samples.push_back((time, channel.poles[3]));
            }
            self.aligned_time = None;
            self.next_index = self
                .next_index
                .checked_add(1)
                .ok_or_else(|| crate::solver::permanent("Waveform sample clock exhausted"))?;
            if self.next_time() <= time {
                return Err(crate::solver::permanent("Waveform clock exceeds time resolution"));
            }
        }
        Ok(())
    }

    /// Each probe is constant or has one real RC pole. Advance the four cascaded filter
    /// stages by convolution with that response while emitting on the original sample clock.
    pub(crate) async fn analytic_interval(&mut self, start: f64, end: f64, curves: &[(f64, f64, f64)])
        -> Result<(), crate::solver::SolveError> {
        if self.channels.is_empty() { return Ok(()); }
        let rate = self.pole_rate();
        let mut t = start;
        while t < end {
            crate::solver::cooperative::checkpoint().await;
            self.align_boundary(end);
            let next = self.next_time().min(end);
            let sample = self.sample_at_end(t, next)?;
            let a = rate * (next - t);
            let (poisson, step_gains) = crate::model::catalog::coefficients!([a], {
                let mut poisson = [(-a).exp(); 5];
                for j in 1..5 { poisson[j] = poisson[j-1] * a / j as f64; }
                let mut step_gains = [0.0; 5];
                for j in 1..=4 {
                    // Poisson tail. Subtracting the first j terms from one loses small gains.
                    let mut term = poisson[j];
                    let mut sum = term;
                    for k in 1..=32 {
                        term *= a / (j+k) as f64;
                        sum += term;
                        if term <= f64::EPSILON * sum { break; }
                    }
                    step_gains[j] = sum;
                }
                (poisson, step_gains)
            }, 1, ([f64; 5], [f64; 5]));
            for (channel, &(initial, steady, tau)) in self.channels.iter_mut().zip(curves) {
                let b = (next-t) / tau;
                let d = a-b;
                let previous = channel.poles;
                let elapsed = (t-start)/tau;
                let first = if elapsed < 0.5 {
                    initial + (steady-initial)*-(-elapsed).exp_m1()
                } else { steady + (initial-steady)*(-elapsed).exp() };
                let remaining = (steady-initial)*(-elapsed).exp();
                let mut exponential_gain = 0.0;
                for j in 1..=4 {
                    // Convolve the change in RC input, remaining*(1-exp(-b*u)), with
                    // the j-stage impulse response. The divided-difference series also
                    // covers coincident poles and very slow inputs without subtracting
                    // two almost identical filter gains.
                    let change_gain = if remaining == 0.0 {
                        0.0
                    } else if d.abs() <= 2.0 {
                        let mut difference = 0.0;
                        let mut term = poisson[j];
                        let mut sum = 0.0;
                        for k in 1..=32 {
                            let divisor = (j+k) as f64;
                            difference = (a*difference + b*term)/divisor;
                            term *= d/divisor;
                            sum += difference;
                        }
                        sum
                    } else {
                        exponential_gain = if j == 1 {
                            a*((-b).exp()-poisson[0])/d
                        } else {
                            (a*exponential_gain-j as f64*poisson[j])/d
                        };
                        step_gains[j]-exponential_gain
                    };
                    let old = previous[j-1];
                    let mut change = (first-old)*step_gains[j] + remaining*change_gain;
                    for k in 1..j { change += poisson[k]*(previous[j-1-k]-old); }
                    channel.poles[j-1] = old + change;
                }
                if !channel.poles.iter().all(|p| p.is_finite()) {
                    return Err(rejected("Non-finite analytic waveform candidate").into());
                }
            }
            self.finish_sample(sample)?;
            t = next;
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

    /// Persist filter state and the sampling clock. Already published samples need no replay.
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
        let last_grid = origin + (next_index - 1) as f64 / samples_per_second;
        if !sampler.next_time().is_finite()
            || sampler.next_time() <= time
            || (last_grid > time && (last_grid.to_bits().abs_diff(time.to_bits()) > 2
                || last_grid - time >= 0.25 / samples_per_second))
        {
            return Err("Invalid waveform snapshot sample cursor".into());
        }
        for (channel, poles) in sampler.channels.iter_mut().zip(poles) {
            channel.poles = poles;
        }
        Ok(sampler)
    }
}

pub(crate) fn probe(channel: &WaveformChannel, point: &DcOperatingPoint) -> Result<f64, Diagnostic> {
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
