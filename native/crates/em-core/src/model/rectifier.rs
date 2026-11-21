//! The forward junction follows the Shockley equation. Reverse breakdown and junction losses couple
//! to a thermal body.
use super::{Evaluation, ResidualUnit, element::rejected, thermal::ThermalResistor};
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rectifier {
    pub knee_volts: f64,
    pub slope_ohms: f64,
    pub watts: f64,
    pub damage: bool,
}

impl Rectifier {
    pub(crate) const FORWARD_THERMAL_VOLTS: f64 = 0.05;
    pub(crate) const FORWARD_SATURATION_AMPERES: f64 = 1e-9;

    pub fn thermal(self) -> ThermalResistor {
        // Rated power produces a 100 K equilibrium rise with a one-second thermal time constant.
        ThermalResistor {
            ambient_ohms: 1.0,
            coefficient_per_kelvin: 0.0,
            heat_capacity_joules_per_kelvin: self.watts / 100.0,
            conductance_watts_per_kelvin: self.watts / 100.0,
            ambient_kelvin: 293.15,
            trip_kelvin: self.damage.then_some(393.15),
        }
    }

    pub fn validate(self) -> Result<(), Diagnostic> {
        if [self.knee_volts, self.slope_ohms, self.watts].iter().any(|v| !v.is_finite() || *v <= 0.0) {
            return Err(rejected("Invalid rectifier rating"));
        }
        self.thermal().validate()
    }

    pub(crate) fn characteristic(self, u: f64) -> (f64, f64) {
        let (excess, exp) = super::junction_exponent(u / Self::FORWARD_THERMAL_VOLTS);
        // Match current and differential conductance where breakdown meets the rated linear slope
        // so small reverse currents remain resolvable with junction capacitance.
        let width = 0.04;
        let overdrive = -u - self.knee_volts;
        let (reverse_volts, reverse_gain) = if overdrive >= width {
            (overdrive, 1.0)
        } else {
            let gain = (overdrive / width - 1.0).exp();
            (width * gain, gain)
        };
        let zero_bias = super::catalog::coefficients!([self.knee_volts],
            width * (-self.knee_volts / width - 1.0).exp(), 1, f64);
        let amps = Self::FORWARD_SATURATION_AMPERES * excess - (reverse_volts - zero_bias) / self.slope_ohms;
        let slope = Self::FORWARD_SATURATION_AMPERES * exp / Self::FORWARD_THERMAL_VOLTS + reverse_gain / self.slope_ohms;
        (amps, slope)
    }

    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        let u = x[0] - x[1];
        let (amps, slope) = self.characteristic(u);
        let thermal = self.thermal();
        let g = thermal.conductance_watts_per_kelvin;
        let c = thermal.heat_capacity_joules_per_kelvin;
        let cooling = g * x[3];
        let heat = c * x[3];
        let power_slope = amps + u * slope;
        let residual = vec![x[2], -x[2], x[2] - amps, cooling - u * amps];
        let jac = vec![0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0, 0.0,
                       -slope, slope, 1.0, 0.0, -power_slope, power_slope, 0.0, g];
        if residual.iter().chain(&jac).any(|v| !v.is_finite()) || !heat.is_finite() {
            return Err(rejected("Non-finite rectifier candidate"));
        }
        let mut qjac = vec![0.0; 16];
        qjac[15] = c;
        Ok(Evaluation {
            residual, residual_jacobian: jac, charge: vec![0.0, 0.0, 0.0, heat],
            charge_jacobian: qjac,
            residual_units: vec![ResidualUnit::Ampere, ResidualUnit::Ampere, ResidualUnit::Ampere, ResidualUnit::Watt],
            stored_joules: heat, dissipated_watts: cooling,
        })
    }
}
