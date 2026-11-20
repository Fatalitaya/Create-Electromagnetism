//! Estimate window-start phase from rising zero crossings of stable bipolar voltage. Require three
//! full periods, at least 16 accepted intervals per cycle, and extrema above 1 µV whose magnitudes
//! differ by at most a factor of five.
use crate::model::element::rejected;
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Periodicity {
    age: f64,
    period: f64,
    maximum: f64,
    minimum: f64,
    max_step: f64,
    stable_cycles: u8,
    seen_crossing: bool,
}
impl Periodicity {
    pub fn fields(self) -> [f64; 7] {
        [
            self.age,
            self.period,
            self.maximum,
            self.minimum,
            self.max_step,
            f64::from(self.stable_cycles),
            f64::from(self.seen_crossing),
        ]
    }
    pub fn from_fields(v: [f64; 7]) -> Result<Self, Diagnostic> {
        if v.iter().any(|x| !x.is_finite())
            || v[0] < 0.0
            || v[1] < 0.0
            || v[2] < 0.0
            || v[3] > 0.0
            || v[4] < 0.0
            || v[5].fract() != 0.0
            || !(0.0..=3.0).contains(&v[5])
            || (v[6] != 0.0 && v[6] != 1.0)
            || (v[5] > 0.0 && (v[1] <= 0.0 || v[6] != 1.0))
        {
            return Err(rejected("Invalid periodic observation state"));
        }
        Ok(Self {
            age: v[0],
            period: v[1],
            maximum: v[2],
            minimum: v[3],
            max_step: v[4],
            stable_cycles: v[5] as u8,
            seen_crossing: v[6] == 1.0,
        })
    }
    pub(crate) fn integrate(&mut self, before: f64, after: f64, h: f64) -> Result<(), Diagnostic> {
        self.maximum = self.maximum.max(before).max(after);
        self.minimum = self.minimum.min(before).min(after);
        self.max_step = self.max_step.max(h);
        if before <= 0.0 && after > 0.0 {
            let scale = before.abs().max(after.abs());
            let fraction = (before.abs() / scale) / (before.abs() / scale + after.abs() / scale);
            let period = self.age + h * fraction;
            let small = self.maximum.min(-self.minimum);
            let large = self.maximum.max(-self.minimum);
            let applicable = self.seen_crossing
                && period > 0.0
                && self.max_step <= period / 16.0
                && small >= 1e-6
                && small >= 0.2 * large;
            if applicable {
                let stable =
                    self.period == 0.0 || (period - self.period).abs() <= 0.01 * self.period;
                self.stable_cycles = if stable {
                    (self.stable_cycles + 1).min(3)
                } else {
                    1
                };
                self.period = period;
            } else {
                self.stable_cycles = 0;
                self.period = 0.0;
            }
            self.seen_crossing = true;
            self.age = h * (1.0 - fraction);
            self.maximum = after;
            self.minimum = 0.0;
            self.max_step = h;
        } else {
            self.age += h;
        }
        Self::from_fields(self.fields())?;
        Ok(())
    }
    pub fn reading(self, window_duration: f64) -> Option<(f64, f64)> {
        if self.stable_cycles < 3
            || self.period <= 0.0
            || self.age > 1.25 * self.period
            || self.max_step > self.period / 16.0
        {
            return None;
        }
        let frequency = 1.0 / self.period;
        let cycles = window_duration / self.period;
        if !frequency.is_finite() || !cycles.is_finite() {
            return None;
        }
        let phase = (std::f64::consts::TAU * (self.age / self.period - cycles.fract()))
            .rem_euclid(std::f64::consts::TAU);
        Some((frequency, phase))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample(f: impl Fn(f64) -> f64, h: f64, duration: f64) -> Periodicity {
        let mut state = Periodicity::default();
        let mut t = 0.0;
        while t < duration {
            let next = (t + h).min(duration);
            state.integrate(f(t), f(next), next - t).unwrap();
            t = next;
        }
        state
    }
    #[test]
    fn stable_bipolar_phase_and_applicability() {
        let duration = 0.437;
        let phase = 0.71;
        let state = sample(
            |t| 3.0 * (std::f64::consts::TAU * 17.0 * t + phase).sin(),
            0.0003,
            duration,
        );
        let (frequency, measured) = state.reading(duration).unwrap();
        assert!((frequency - 17.0).abs() < 0.002);
        assert!((measured - phase).abs() < 0.001);
        assert_eq!(state, Periodicity::from_fields(state.fields()).unwrap());
        for state in [
            sample(|_| 10.0, 0.0001, 0.5),
            sample(
                |t| (std::f64::consts::TAU * 10.0 * t).sin().abs(),
                0.0001,
                0.5,
            ),
            sample(
                |t| 1e-8 * (std::f64::consts::TAU * 10.0 * t).sin(),
                0.0001,
                0.5,
            ),
            sample(|t| (std::f64::consts::TAU * 10.0 * t).sin(), 0.01, 0.5),
            sample(|t| (std::f64::consts::TAU * 10.0 * t).sin(), 0.0001, 0.2),
        ] {
            assert!(state.reading(0.5).is_none());
        }
    }
    #[test]
    fn stops_and_irregular_periods_invalidate_without_inventing_zero_hertz() {
        let mut state = sample(|t| (std::f64::consts::TAU * 10.0 * t).sin(), 0.0001, 0.5);
        assert!(state.reading(0.5).is_some());
        state.integrate(0.0, 0.0, 0.13).unwrap();
        assert!(state.reading(0.63).is_none());
        let chirp = sample(
            |t| (std::f64::consts::TAU * (10.0 * t + 30.0 * t * t)).sin(),
            0.0001,
            0.5,
        );
        assert!(chirp.reading(0.5).is_none());
        let mut invalid = state.fields();
        invalid[5] = 1.5;
        assert!(Periodicity::from_fields(invalid).is_err());
    }
}
