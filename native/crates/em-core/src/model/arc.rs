//! Passive electrothermal air-gap approximation, not a plasma chemistry model.
//! Temperature controls conductance; electric field can ignite a cold gap. All
//! terminal power becomes stored heat or ambient cooling, including while closing.
use super::{Evaluation, ResidualUnit, element::rejected, thermal::ThermalResistor};
use em_protocol::contract::Diagnostic;
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Arc {
    pub closed: bool,
    pub contact_ohms: f64,
    pub leakage_siemens: f64,
    pub hot_siemens: f64,
    pub breakdown_volts: f64,
    pub ignition_rise: f64,
    pub capacity: f64,
    pub cooling: f64,
    pub ambient: f64,
}
impl Arc {
    pub fn parameters(self) -> Vec<f64> {
        vec![
            if self.closed { 1.0 } else { 0.0 },
            self.contact_ohms,
            self.leakage_siemens,
            self.hot_siemens,
            self.breakdown_volts,
            self.ignition_rise,
            self.capacity,
            self.cooling,
            self.ambient,
        ]
    }
    pub fn thermal(self) -> ThermalResistor {
        ThermalResistor {
            ambient_ohms: self.contact_ohms,
            coefficient_per_kelvin: 0.0,
            heat_capacity_joules_per_kelvin: self.capacity,
            conductance_watts_per_kelvin: self.cooling,
            ambient_kelvin: self.ambient,
            trip_kelvin: None,
        }
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        if self
            .parameters()
            .iter()
            .skip(1)
            .any(|v| !v.is_finite() || *v <= 0.0)
            || self.hot_siemens <= self.leakage_siemens
        {
            return Err(rejected("Invalid air-gap constitutive parameters"));
        }
        Ok(())
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if x.len() != 4 || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid air-gap candidate"));
        }
        let u = x[0] - x[1];
        let theta = x[3];
        let field = (u / self.breakdown_volts).powi(2);
        // Compact C1 transition: below 90% of the combined thermal/field
        // ignition criterion the gap has exactly its declared leakage. At the
        // criterion it is half conducting; above 110% it is fully conducting.
        // A sigmoid tail would swamp a teraohm cold insulation with hot current.
        let activation = ((theta / self.ignition_rise + field - 0.9) / 0.2).clamp(0.0, 1.0);
        let fraction = activation * activation * (3.0 - 2.0 * activation);
        let (g, du, dt) = if self.closed {
            (1.0 / self.contact_ohms, 0.0, 0.0)
        } else {
            let range = self.hot_siemens - self.leakage_siemens;
            let slope = range * 6.0 * activation * (1.0 - activation) / 0.2;
            (
                self.leakage_siemens + range * fraction,
                slope * 2.0 * u / self.breakdown_volts.powi(2),
                slope / self.ignition_rise,
            )
        };
        let conduction = g * u;
        let derivative = g + du * u;
        let power = g * u * u;
        let heat = self.capacity * theta;
        let mut jac = vec![0.0; 16];
        jac[2] = 1.0;
        jac[6] = -1.0;
        jac[8] = -derivative;
        jac[9] = derivative;
        jac[10] = 1.0;
        jac[11] = -dt * u;
        jac[12] = -2.0 * g * u - du * u * u;
        jac[13] = -jac[12];
        jac[15] = self.cooling - dt * u * u;
        let mut qjac = vec![0.0; 16];
        qjac[15] = self.capacity;
        let residual = vec![x[2], -x[2], x[2] - conduction, self.cooling * theta - power];
        if residual.iter().chain(&jac).any(|v| !v.is_finite()) || !heat.is_finite() {
            return Err(rejected("Non-finite air-gap constitutive evaluation"));
        }
        Ok(Evaluation {
            residual_units: vec![
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                ResidualUnit::Watt,
            ],
            residual,
            residual_jacobian: jac,
            charge: vec![0.0, 0.0, 0.0, heat],
            charge_jacobian: qjac,
            stored_joules: heat,
            dissipated_watts: self.cooling * theta,
        })
    }
}
