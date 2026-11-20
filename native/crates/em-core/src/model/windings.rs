//! The insulated windings couple through reciprocal flux linkages. Ports are primary dot, primary
//! return, secondary dot and secondary return. Positive mutual inductance gives like polarity at
//! the dots.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TransformerTap {
    pub magnetizing_henries: f64,
    pub leakage_henries: f64,
    pub secondary_turns_per_primary_turn: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Windings {
    pub tap: Option<TransformerTap>,
    pub primary_henries: f64,
    pub secondary_henries: f64,
    pub mutual_henries: f64,
    pub primary_ohms: f64,
    pub secondary_ohms: f64,
}
impl Windings {
    /// The linear equivalent uses primary magnetizing inductance Lm, ideal turns ratio Np/Ns and
    /// series leakage on each winding.
    pub fn transformer(
        turns_ratio: f64,
        magnetizing_henries: f64,
        primary_leakage_henries: f64,
        secondary_leakage_henries: f64,
        primary_ohms: f64,
        secondary_ohms: f64,
    ) -> Result<Self, Diagnostic> {
        if [
            turns_ratio,
            magnetizing_henries,
            primary_leakage_henries,
            secondary_leakage_henries,
        ]
        .iter()
        .any(|v| !v.is_finite() || *v <= 0.0)
        {
            return Err(rejected(
                "Transformer ratio, magnetization and leakage must be finite and positive",
            ));
        }
        let model = Self {
            tap: None,
            primary_henries: magnetizing_henries + primary_leakage_henries,
            secondary_henries: magnetizing_henries / (turns_ratio * turns_ratio)
                + secondary_leakage_henries,
            mutual_henries: magnetizing_henries / turns_ratio,
            primary_ohms,
            secondary_ohms,
        };
        model.validate()?;
        Ok(model)
    }
    pub fn tapped(magnetizing: f64, leakage: f64, gain: f64) -> Result<Self, Diagnostic> {
        if !magnetizing.is_finite() || magnetizing <= 0.0 || !leakage.is_finite()
            || leakage <= 0.0 || !gain.is_finite() || gain < 0.0
        {
            return Err(rejected("Invalid tapped transformer inductance or turns ratio"));
        }
        let model = Self {
            tap: Some(TransformerTap {
                magnetizing_henries: magnetizing,
                leakage_henries: leakage,
                secondary_turns_per_primary_turn: gain,
            }),
            primary_henries: magnetizing + leakage,
            secondary_henries: magnetizing * gain * gain + leakage,
            mutual_henries: magnetizing * gain,
            primary_ohms: 0.0,
            secondary_ohms: 0.0,
        };
        model.validate()?;
        Ok(model)
    }
    fn parameter_values(self) -> [f64; 5] {
        [
            self.primary_henries,
            self.secondary_henries,
            self.mutual_henries,
            self.primary_ohms,
            self.secondary_ohms,
        ]
    }
    pub fn parameters(self) -> Vec<f64> {
        self.parameter_values().to_vec()
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        let determinant =
            self.primary_henries * self.secondary_henries - self.mutual_henries.powi(2);
        if self.parameter_values().iter().any(|p| !p.is_finite())
            || self.primary_henries <= 0.0
            || self.secondary_henries <= 0.0
            || self.primary_ohms < 0.0
            || self.secondary_ohms < 0.0
            || !determinant.is_finite()
            || determinant <= 0.0
        {
            return Err(rejected(
                "Winding inductance matrix must be positive definite; copper losses nonnegative",
            ));
        }
        Ok(())
    }
    pub fn fluxes(self, primary: f64, secondary: f64) -> (f64, f64) {
        (
            self.primary_henries * primary + self.mutual_henries * secondary,
            self.mutual_henries * primary + self.secondary_henries * secondary,
        )
    }
    pub fn currents(self, primary: f64, secondary: f64) -> (f64, f64) {
        let det = self.primary_henries * self.secondary_henries - self.mutual_henries.powi(2);
        (
            (self.secondary_henries * primary - self.mutual_henries * secondary) / det,
            (self.primary_henries * secondary - self.mutual_henries * primary) / det,
        )
    }
    pub fn energy(self, primary: f64, secondary: f64) -> f64 {
        let (a, b) = self.fluxes(primary, secondary);
        0.5 * (a * primary + b * secondary)
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 7 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid winding local values"));
        }
        let (ip, is) = (x[2], x[4]);
        let (fp, fs) = self.fluxes(ip, is);
        let residual = vec![
            ip,
            -ip - is - x[6],
            x[0] - x[1] - self.primary_ohms * ip,
            is,
            x[3] - x[5] - self.secondary_ohms * is,
            x[6],
            is + x[6],
        ];
        let mut jac = vec![0.0; 49];
        jac[2] = 1.0;
        jac[9] = -1.0;
        jac[11] = -1.0;
        jac[13] = -1.0;
        jac[14] = 1.0;
        jac[15] = -1.0;
        jac[16] = -self.primary_ohms;
        jac[25] = 1.0;
        jac[31] = 1.0;
        jac[32] = -self.secondary_ohms;
        jac[33] = -1.0;
        jac[41] = 1.0;
        jac[46] = 1.0;
        jac[48] = 1.0;
        let mut charge = vec![0.0; 7];
        charge[2] = -fp;
        charge[4] = -fs;
        let mut qjac = vec![0.0; 49];
        qjac[16] = -self.primary_henries;
        qjac[18] = -self.mutual_henries;
        qjac[30] = -self.mutual_henries;
        qjac[32] = -self.secondary_henries;
        let stored_joules = 0.5 * (fp * ip + fs * is);
        let dissipated_watts = self.primary_ohms * ip * ip + self.secondary_ohms * is * is;
        if residual.iter().chain(&charge).any(|v| !v.is_finite())
            || !stored_joules.is_finite()
            || !dissipated_watts.is_finite()
            || stored_joules < 0.0
        {
            return Err(rejected("Non-finite winding candidate"));
        }
        Ok(Evaluation {
            residual,
            charge,
            residual_jacobian: jac,
            charge_jacobian: qjac,
            stored_joules,
            dissipated_watts,
            residual_units: vec![
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                ResidualUnit::Volt,
                ResidualUnit::Ampere,
                ResidualUnit::Volt,
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
            ],
        })
    }
}
