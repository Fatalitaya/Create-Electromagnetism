//! Fixed-temperature reciprocal Ebers–Moll model with linear junction charge. Ports are collector, emitter and base. Local values are `[vc, ve, ic, vb, ib]`. Currents enter the device, so ie = −ic − ib. The model omits breakdown, the Early effect and self-heating.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Bipolar {
    /// Saturation current of the reciprocal transport term.
    pub saturation_amperes: f64,
    pub thermal_volts: f64,
    pub forward_beta: f64,
    pub reverse_beta: f64,
    pub base_emitter_farads: f64,
    pub base_collector_farads: f64,
    pub pnp: bool,
}

impl Bipolar {
    pub fn parameters(self) -> Vec<f64> {
        vec![
            self.saturation_amperes,
            self.thermal_volts,
            self.forward_beta,
            self.reverse_beta,
            self.base_emitter_farads,
            self.base_collector_farads,
        ]
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        if self
            .parameters()
            .iter()
            .any(|v| !v.is_finite() || *v <= 0.0)
        {
            return Err(rejected(
                "Bipolar transport, gain and junction capacitances must be finite and positive",
            ));
        }
        Ok(())
    }

    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 5 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid bipolar local values"));
        }
        let s = if self.pnp { -1.0 } else { 1.0 };
        let vbe = x[3] - x[1];
        let vbc = x[3] - x[0];
        let (forward_excess, ef) = super::junction_exponent(s * vbe / self.thermal_volts);
        let (reverse_excess, er) = super::junction_exponent(s * vbc / self.thermal_volts);
        let forward = self.saturation_amperes * forward_excess;
        let reverse = self.saturation_amperes * reverse_excess;
        let gf = self.saturation_amperes * ef / self.thermal_volts;
        let gr = self.saturation_amperes * er / self.thermal_volts;
        let reverse_factor = 1.0 + 1.0 / self.reverse_beta;
        let ic = s * (forward - reverse_factor * reverse);
        let ib = s * (forward / self.forward_beta + reverse / self.reverse_beta);
        let mut jac = vec![0.0; 25];
        jac[2] = 1.0;
        jac[7] = -1.0;
        jac[9] = -1.0;
        jac[10] = -reverse_factor * gr;
        jac[11] = gf;
        jac[12] = 1.0;
        jac[13] = -gf + reverse_factor * gr;
        jac[19] = 1.0;
        jac[20] = gr / self.reverse_beta;
        jac[21] = gf / self.forward_beta;
        jac[23] = -gf / self.forward_beta - gr / self.reverse_beta;
        jac[24] = 1.0;
        let cbe = self.base_emitter_farads;
        let cbc = self.base_collector_farads;
        let mut charge = vec![0.0; 5];
        charge[2] = cbc * vbc; // Collector charge contributes −Qc to this row.
        charge[4] = -cbe * vbe - cbc * vbc; // Base charge contributes −Qb to this row.
        let mut qjac = vec![0.0; 25];
        qjac[10] = -cbc;
        qjac[13] = cbc;
        qjac[20] = cbc;
        qjac[21] = cbe;
        qjac[23] = -cbe - cbc;
        let residual = vec![x[2], -x[2] - x[4], x[2] - ic, x[4], x[4] - ib];
        let stored_joules = 0.5 * cbe * vbe * vbe + 0.5 * cbc * vbc * vbc;
        let dissipated_watts = (x[0] - x[1]) * ic + vbe * ib;
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
            return Err(rejected("Non-finite or non-passive bipolar candidate"));
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
