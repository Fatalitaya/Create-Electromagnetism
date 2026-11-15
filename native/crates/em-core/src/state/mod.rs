//! Accepted physical state and versioned snapshot/restore boundaries.
//! Snapshots must contain stable identities and accepted time, never native pointers.

use crate::{
    observation::{EnergyLedger, Measurements},
    solver::dc::DcOperatingPoint,
};
use em_protocol::contract::Id;
use std::collections::HashMap;

/// Explicit initial storage; orientations match branch a->b. No implicit zeroing.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum InitialStorage {
    DetectorVoltageSquared(f64),
    ChargeCoulombs(f64),
    TemperatureKelvin(f64),
    /// Relative state avoids subtracting nearly equal absolute temperatures during migration.
    TemperatureRiseKelvin(f64),
    FluxWebers(f64),
    WindingFluxes {
        primary: f64,
        secondary: f64,
    },
    PortCharges {
        control_return: f64,
        control_output: f64,
    },
}

pub type InitialConditions = HashMap<Id, InitialStorage>;

#[derive(Clone, Debug, PartialEq)]
pub struct AcceptedState {
    pub time_seconds: f64,
    pub operating_point: DcOperatingPoint,
    pub energy: EnergyLedger,
    pub measurements: Measurements,
    pub sampler: crate::observation::waveform::Sampler,
    /// Source phase at this accepted time, independent of the configured initial phase.
    pub source_phases: HashMap<Id, f64>,
}
