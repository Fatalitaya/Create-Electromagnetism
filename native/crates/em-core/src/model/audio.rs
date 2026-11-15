//! One immutable 50 ms external acoustic input interval, at 16 kHz mono.
//! A finite source resistance accounts for transducer loss. Outside the supplied
//! interval the source is silent; missing input is exposed by the world adapter.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;
#[derive(Clone, Debug, PartialEq)]
pub struct Audio {
    pub start: f64,
    pub step: f64,
    pub volts: f64,
    pub ohms: f64,
    pub samples: std::sync::Arc<[f64; 800]>,
}
impl Audio {
    pub fn parameters(&self) -> Vec<f64> {
        let mut values = vec![self.start, self.step, self.volts, self.ohms];
        values.extend(self.samples.iter().copied());
        values
    }
    pub fn validate(&self) -> Result<(), Diagnostic> {
        if self.start < 0.0
            || !self.start.is_finite()
            || self.step != 1.0 / 16000.0
            || !self.volts.is_finite()
            || self.volts < 0.0
            || !self.ohms.is_finite()
            || self.ohms <= 0.0
            || self.samples.iter().any(|v| !v.is_finite() || v.abs() > 1.0)
        {
            Err(rejected("Invalid acoustic input block"))
        } else {
            Ok(())
        }
    }
    pub fn voltage(&self, time: f64) -> f64 {
        let sample = (time - self.start) / self.step;
        if !(0.0..800.0).contains(&sample) {
            return 0.0;
        }
        let i = sample.floor() as usize;
        let a = self.samples[i];
        let b = self.samples.get(i + 1).copied().unwrap_or(0.0);
        self.volts * (a + (b - a) * sample.fract())
    }
    pub fn evaluate(&self, x: &[f64], time: f64) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 3 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid acoustic source candidate"));
        }
        Ok(Evaluation {
            residual_units: vec![
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                ResidualUnit::Volt,
            ],
            residual: vec![
                x[2],
                -x[2],
                x[0] - x[1] - self.voltage(time) - self.ohms * x[2],
            ],
            residual_jacobian: vec![0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 1.0, -1.0, -self.ohms],
            charge: vec![0.0; 3],
            charge_jacobian: vec![0.0; 9],
            stored_joules: 0.0,
            dissipated_watts: self.ohms * x[2] * x[2],
        })
    }
}
