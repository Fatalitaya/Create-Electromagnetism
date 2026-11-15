//! Lossless common-return ratio controller with a finite response-time RMS detector.
//! The detector is signal state, not an electrical energy reservoir. Copper losses
//! and winding inductance are composed explicitly outside this model.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Regulator {
    pub target: f64,
    pub seconds: f64,
    pub minimum: f64,
    pub maximum: f64,
}
impl Regulator {
    pub fn validate(self) -> Result<(), Diagnostic> {
        if [self.target, self.seconds, self.minimum, self.maximum]
            .iter()
            .any(|v| !v.is_finite() || *v <= 0.0)
            || self.maximum <= self.minimum
        {
            Err(rejected("Invalid regulator parameters"))
        } else {
            Ok(())
        }
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 6 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid regulator candidate"));
        }
        let input = x[0] - x[1];
        let output = x[3] - x[1];
        let sensed = x[5].max(1e-12);
        let requested = self.target / sensed.sqrt();
        let gain = requested.clamp(self.minimum, self.maximum);
        let dg = if requested > self.minimum && requested < self.maximum && x[5] > 1e-12 {
            -0.5 * gain / sensed
        } else {
            0.0
        };
        let mut jac = vec![0.0; 36];
        jac[2] = 1.0;
        jac[8] = -1.0;
        jac[10] = -1.0;
        jac[12] = -gain;
        jac[13] = gain - 1.0;
        jac[15] = 1.0;
        jac[17] = -dg * input;
        jac[22] = 1.0;
        jac[26] = 1.0;
        jac[28] = gain;
        jac[29] = dg * x[4];
        jac[30] = -2.0 * input;
        jac[31] = 2.0 * input;
        jac[35] = 1.0;
        let mut q = vec![0.0; 6];
        q[5] = self.seconds * x[5];
        let mut qjac = vec![0.0; 36];
        qjac[35] = self.seconds;
        let mut units = vec![ResidualUnit::Ampere; 6];
        units[2] = ResidualUnit::Volt;
        units[5] = ResidualUnit::VoltSquared;
        Ok(Evaluation {
            residual_units: units,
            residual: vec![
                x[2],
                -x[2] - x[4],
                output - gain * input,
                x[4],
                x[2] + gain * x[4],
                x[5] - input * input,
            ],
            residual_jacobian: jac,
            charge: q,
            charge_jacobian: qjac,
            stored_joules: 0.0,
            dissipated_watts: 0.0,
        })
    }
}
