//! Enhancement MOS model with a symmetric square-law channel, body diode and linear gate charges. Ports are drain, source and gate. Local values are `[vd, vs, id, vg, ig]`. The model omits avalanche and channel-length modulation.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Mosfet {
    pub beta_amperes_per_volt_squared: f64,
    pub threshold_volts: f64,
    pub saturation_amperes: f64,
    pub thermal_volts: f64,
    pub gate_source_farads: f64,
    pub gate_drain_farads: f64,
    pub p_channel: bool,
}
impl Mosfet {
    pub fn parameters(self) -> Vec<f64> {
        vec![
            self.beta_amperes_per_volt_squared,
            self.threshold_volts,
            self.saturation_amperes,
            self.thermal_volts,
            self.gate_source_farads,
            self.gate_drain_farads,
        ]
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        let parameters = self.parameters();
        if parameters.iter().any(|p| !p.is_finite())
            || self.threshold_volts < 0.0
            || [
                parameters[0],
                parameters[2],
                parameters[3],
                parameters[4],
                parameters[5],
            ]
            .iter()
            .any(|p| *p <= 0.0)
        {
            return Err(rejected(
                "Invalid enhancement MOS transport or junction parameters",
            ));
        }
        Ok(())
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 5 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid MOS local values"));
        }
        let s = if self.p_channel { -1.0 } else { 1.0 };
        let u = s * (x[0] - x[1]);
        let direction = if u >= 0.0 { 1.0 } else { -1.0 };
        let vds = u.abs();
        let overdrive = s * (x[3] - if u >= 0.0 { x[1] } else { x[0] }) - self.threshold_volts;
        let k = self.beta_amperes_per_volt_squared;
        let (channel, dv, dg) = if overdrive <= 0.0 {
            (0.0, 0.0, 0.0)
        } else if vds < overdrive {
            (
                k * (overdrive * vds - 0.5 * vds * vds),
                k * (overdrive - vds),
                k * vds,
            )
        } else {
            (0.5 * k * overdrive * overdrive, 0.0, k * overdrive)
        };
        let (excess, exponential) = super::junction_exponent(-u / self.thermal_volts);
        let body = -s * self.saturation_amperes * excess;
        let gb = self.saturation_amperes * exponential / self.thermal_volts;
        let drain = s * direction * channel + body;
        let gd = dv + (if u < 0.0 { dg } else { 0.0 }) + gb;
        let gs = -dv - (if u >= 0.0 { dg } else { 0.0 }) - gb;
        let gg = direction * dg;
        let mut jac = vec![0.0; 25];
        jac[2] = 1.0;
        jac[7] = -1.0;
        jac[9] = -1.0;
        jac[10] = -gd;
        jac[11] = -gs;
        jac[12] = 1.0;
        jac[13] = -gg;
        jac[19] = 1.0;
        jac[24] = 1.0;
        let cgs = self.gate_source_farads;
        let cgd = self.gate_drain_farads;
        let vgs = x[3] - x[1];
        let vgd = x[3] - x[0];
        let mut charge = vec![0.0; 5];
        charge[2] = cgd * vgd;
        charge[4] = -cgs * vgs - cgd * vgd;
        let mut qjac = vec![0.0; 25];
        qjac[10] = -cgd;
        qjac[13] = cgd;
        qjac[20] = cgd;
        qjac[21] = cgs;
        qjac[23] = -cgs - cgd;
        let residual = vec![x[2], -x[2] - x[4], x[2] - drain, x[4], x[4]];
        let stored_joules = 0.5 * cgs * vgs * vgs + 0.5 * cgd * vgd * vgd;
        let dissipated_watts = (x[0] - x[1]) * drain;
        if residual
            .iter()
            .chain(&jac)
            .chain(&charge)
            .chain(&qjac)
            .any(|v| !v.is_finite())
            || !stored_joules.is_finite()
            || !dissipated_watts.is_finite()
            || dissipated_watts < 0.0
        {
            return Err(rejected("Non-finite or non-passive MOS candidate"));
        }
        Ok(Evaluation {
            residual_units: vec![ResidualUnit::Ampere; 5],
            charge,
            residual,
            charge_jacobian: qjac,
            residual_jacobian: jac,
            stored_joules,
            dissipated_watts,
        })
    }
}
