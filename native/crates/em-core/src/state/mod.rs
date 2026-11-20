//! Snapshots record stable identities and accepted physical time. Restore reconstructs
//! process-local objects.

use crate::{
    observation::{EnergyLedger, Measurements},
    solver::dc::DcOperatingPoint,
};
use em_protocol::contract::Id;
use std::collections::HashMap;

/// Supply initial storage explicitly, oriented from branch terminal a to b.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum InitialStorage {
    DetectorVoltageSquared(f64),
    ChargeCoulombs(f64),
    TemperatureKelvin(f64),
    /// Store rise above ambient to avoid subtracting nearly equal absolute temperatures during
    /// migration.
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
    /// Source phase at the accepted time, advanced from its initial value.
    pub source_phases: HashMap<Id, f64>,
}
