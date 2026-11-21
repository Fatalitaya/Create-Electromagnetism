//! Device models define port orientation and validate SI parameters here. DC, transient and
//! frequency-domain solvers share their equations.

use em_protocol::contract::Diagnostic;

pub mod bipolar;
pub mod element;
mod parameters;
mod storage;
pub mod mosfet;

pub mod catalog;
use catalog::junction_exponent;

/// Unit of F + dQ/dt. Multiply by seconds to obtain the unit of Q.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResidualUnit {
    Ampere,
    Volt,
    VoltSquared,
    Watt,
    Kelvin,
}

/// Physical unit of one solution unknown.
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

/// Solvers share this constitutive evaluation. The Jacobian has unknown_count² entries in row-major
/// order.
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
    /// Candidate evaluation reads history. Energy is committed when the candidate is accepted.
    fn evaluate(&self, values: &[f64], time_seconds: f64) -> Result<Evaluation, Diagnostic>;
}

pub mod windings;

pub mod arc;
pub mod audio;
pub mod regulator;
pub mod rectifier;
pub mod tapped;
pub mod thermal;
