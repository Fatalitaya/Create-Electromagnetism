//! Joule-heated resistance with a fixed ambient bath and one lumped thermal node.
//! Local values [va,vb,i,theta]; accepted theta=T-Tambient >=0 K. Stored heat is Cth*theta
//! relative to that fixed bath, not electrical dissipation counted a second time.
use super::{Evaluation, ResidualUnit, element::rejected};
use em_protocol::contract::Diagnostic;
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ThermalResistor {
    pub ambient_ohms: f64,
    pub coefficient_per_kelvin: f64,
    pub heat_capacity_joules_per_kelvin: f64,
    pub conductance_watts_per_kelvin: f64,
    pub ambient_kelvin: f64,
    pub trip_kelvin: Option<f64>,
}
impl ThermalResistor {
    fn base_parameters(self) -> [f64; 5] {
        [
            self.ambient_ohms,
            self.coefficient_per_kelvin,
            self.heat_capacity_joules_per_kelvin,
            self.conductance_watts_per_kelvin,
            self.ambient_kelvin,
        ]
    }
    pub fn parameters(self) -> Vec<f64> {
        let mut parameters = self.base_parameters().to_vec();
        if let Some(trip) = self.trip_kelvin {
            parameters.push(trip);
        }
        parameters
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        if self.base_parameters().iter().any(|p| !p.is_finite())
            || self.ambient_ohms <= 0.0
            || self.coefficient_per_kelvin < 0.0
            || self.heat_capacity_joules_per_kelvin <= 0.0
            || self.conductance_watts_per_kelvin <= 0.0
            || self.ambient_kelvin <= 0.0
            || self
                .trip_kelvin
                .is_some_and(|t| !t.is_finite() || t <= self.ambient_kelvin)
        {
            return Err(rejected(
                "Thermal resistance requires positive R/C/G/ambient and nonnegative temperature coefficient",
            ));
        }
        Ok(())
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 4
            || x.iter().any(|v| !v.is_finite())
            || !(self.ambient_kelvin + x[3]).is_finite()
        {
            return Err(rejected("Thermal candidate must be finite"));
        }
        let dr = self.ambient_ohms * self.coefficient_per_kelvin;
        let resistance = self.ambient_ohms + dr * x[3];
        // Newton iterates may cross the thermal state boundary while correcting
        // startup current. Validate physical temperature only on converged points;
        // clipping an iterate changes both the equation and its Jacobian.
        if resistance <= 0.0 {
            return Err(rejected(
                "Thermal candidate resistance must remain positive",
            ));
        }
        let current = x[2];
        let cooling = self.conductance_watts_per_kelvin * x[3];
        let heat = self.heat_capacity_joules_per_kelvin * x[3];
        let residual = vec![
            current,
            -current,
            x[0] - x[1] - resistance * current,
            cooling - resistance * current * current,
        ];
        let jac = vec![
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            0.0,
            -1.0,
            0.0,
            1.0,
            -1.0,
            -resistance,
            -dr * current,
            0.0,
            0.0,
            -2.0 * resistance * current,
            self.conductance_watts_per_kelvin - dr * current * current,
        ];
        let mut qjac = vec![0.0; 16];
        qjac[15] = self.heat_capacity_joules_per_kelvin;
        if residual.iter().chain(&jac).any(|v| !v.is_finite()) || !heat.is_finite() {
            return Err(rejected("Non-finite electrothermal candidate"));
        }
        Ok(Evaluation {
            residual,
            charge: vec![0.0, 0.0, 0.0, heat],
            residual_jacobian: jac,
            charge_jacobian: qjac,
            residual_units: vec![
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                ResidualUnit::Volt,
                ResidualUnit::Watt,
            ],
            stored_joules: heat,
            dissipated_watts: cooling,
        })
    }
}
