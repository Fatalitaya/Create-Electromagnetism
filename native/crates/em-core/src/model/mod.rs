//! Device constitutive models shared by DC, transient, and spectral algorithms.
//! Models own port orientation and SI parameter validation here.

use em_protocol::contract::Diagnostic;

pub mod bipolar;
pub mod element;
mod parameters;
mod storage;
pub mod mosfet;

/// Return (exp(x) - 1, exp(x)) with one transcendental evaluation.
/// Near zero, preserve the small current with exp_m1; away from zero,
/// preserve the small reverse-bias derivative with exp. Non-finite results
/// remain visible to the constitutive model's candidate validation.
fn junction_exponent(x: f64) -> (f64, f64) {
    if x == 0.0 {
        return (x, 1.0);
    }
    if x.abs() <= 0.5 {
        let current = x.exp_m1();
        (current, current + 1.0)
    } else {
        let derivative = x.exp();
        (derivative - 1.0, derivative)
    }
}

/// SI unit of F + dQ/dt; Q therefore has this unit multiplied by seconds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResidualUnit {
    Ampere,
    Volt,
    VoltSquared,
    Watt,
    Kelvin,
}

/// Physical unit of a solution unknown, independent of its equation's unit.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UnknownUnit {
    Volt,
    Ampere,
    Kelvin,
    VoltSquared,
}

impl UnknownUnit {
    pub fn absolute_tolerance(self) -> f64 {
        match self {
            Self::Volt | Self::VoltSquared => 1e-10,
            Self::Ampere => 1e-12,
            Self::Kelvin => 1e-8,
        }
    }
}

/// A single constitutive evaluation used by all numerical representations.
/// Flattened Jacobians are row-major and have unknown_count squared entries.
pub struct Evaluation {
    pub residual_units: Vec<ResidualUnit>,
    pub charge: Vec<f64>,
    pub residual: Vec<f64>,
    pub charge_jacobian: Vec<f64>,
    pub residual_jacobian: Vec<f64>,
    pub stored_joules: f64,
    pub dissipated_watts: f64,
}

pub trait DeviceModel {
    fn unknown_count(&self) -> usize;
    /// Pure candidate evaluation: must not advance accepted history or consume energy.
    fn evaluate(&self, values: &[f64], time_seconds: f64) -> Result<Evaluation, Diagnostic>;
}

pub mod windings;

pub mod arc;
pub mod audio;
pub mod regulator;
pub mod rectifier;
pub mod tapped;
pub mod thermal;
