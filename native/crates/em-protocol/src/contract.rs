//! Rust-side domain types. These are not C layouts or a serialized wire format.
//! Java counterparts live in `simulation/SimulationProtocol.java`.

/// Thirty-two channels, each retaining a complete 16 kHz game tick with margin.
pub const MAX_WAVEFORM_SAMPLES: usize = 32 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Id(pub [u64; 2]);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct DeviceId {
    pub instance: Id,
    pub generation: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct PortId {
    pub device: DeviceId,
    pub terminal: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Revision {
    pub session: Id,
    pub topology: u64,
    pub parameters: u64,
    pub sequence: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Unavailable,
    Unsupported,
    Ready,
    Partial,
    Rejected,
    Closed,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Diagnostic {
    pub status: Status,
    pub reason: String,
}

#[derive(Clone, Debug)]
pub struct DeviceInput {
    pub id: DeviceId,
    pub model: String,
    pub ports: Vec<PortId>,
    pub parameters: std::collections::BTreeMap<String, f64>,
}

#[derive(Clone, Debug)]
pub struct Branch {
    pub id: Id,
    pub positive: PortId,
    pub negative: PortId,
    pub additional_ports: Vec<PortId>,
    pub model: String,
    pub parameters: std::collections::BTreeMap<String, f64>,
}

#[derive(Clone, Debug)]
pub struct Topology {
    pub revision: Revision,
    pub devices: Vec<DeviceInput>,
    pub branches: Vec<Branch>,
}

#[derive(Clone, Debug)]
pub struct Parameters {
    pub revision: Revision,
    pub devices: Vec<DeviceInput>,
}

#[derive(Clone, Copy, Debug)]
pub struct Advance {
    pub revision: Revision,
    pub accepted_start: f64,
    pub requested_end: f64,
    pub max_steps: u32,
    pub budget_nanos: u64,
}

impl Advance {
    pub fn validate(&self) -> Result<(), Diagnostic> {
        if !self.accepted_start.is_finite()
            || !self.requested_end.is_finite()
            || self.accepted_start < 0.0
            || self.requested_end < self.accepted_start
            || self.max_steps == 0
            || self.budget_nanos == 0
        {
            return Err(Diagnostic {
                status: Status::Rejected,
                reason: "Advance requires a finite ordered interval and positive budgets".into(),
            });
        }
        Ok(())
    }
}

/// Per-request work, not physical state. Duplicate requests return the same counts.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AdvanceWork {
    pub attempted_steps: u32,
    pub accepted_steps: u32,
    pub rejected_steps: u32,
    pub discarded_steps: u32,
    pub localization_trials: u32,
}

#[derive(Clone, Debug)]
pub struct Accepted {
    pub revision: Revision,
    pub start: f64,
    pub end: f64,
    pub requested_end: f64,
    pub diagnostic: Diagnostic,
    pub work: AdvanceWork,
}

#[derive(Clone, Debug)]
pub struct Reading {
    pub branch: Id,
    pub window_start: f64,
    pub window_end: f64,
    pub mean_voltage: f64,
    pub rms_voltage: f64,
    pub peak_voltage: f64,
    pub mean_current: f64,
    pub rms_current: f64,
    pub peak_current: f64,
    pub active_power: f64,
    pub absorbed_energy: f64,
    pub returned_energy: f64,
    pub dissipated_energy: f64,
    pub frequency: Option<f64>,
    /// Independent sinusoidal voltage source phase at window_start, radians.
    /// Non-source waveforms require a separate estimator and report None.
    pub phase: Option<f64>,
    /// Thermal node temperature at the accepted interval end, kelvin.
    pub temperature_kelvin: Option<f64>,
    /// Accepted-window mean torque delivered to a prescribed-speed shaft (N m).
    pub torque_newton_metres: Option<f64>,
    pub total_absorbed_energy: f64,
    pub total_returned_energy: f64,
    pub total_dissipated_energy: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ProtectionEvent {
    /// 0: thermal damage, 1: relay coil released, 2: relay coil energized.
    /// Switch events do not carry a temperature; temperature_kelvin is zero.
    pub action: u32,
    pub id: Id,
    pub branch: Id,
    pub time: f64,
    pub temperature_kelvin: f64,
    pub stored_joules: f64,
}

#[derive(Clone, Debug)]
pub struct Observations {
    pub accepted: Accepted,
    pub readings: Vec<Reading>,
    pub waveforms: Vec<Waveform>,
    pub events: Vec<ProtectionEvent>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SampleQuality {
    Valid,
    Reconstructed,
    Missing,
    Unconverged,
    BandwidthLimit,
}

#[derive(Clone, Debug)]
pub struct WaveformSample {
    pub time: f64,
    pub value: Option<f64>,
    pub quality: SampleQuality,
}

#[derive(Clone, Debug)]
pub struct Waveform {
    pub channel: Id,
    pub positive: PortId,
    pub negative: PortId,
    pub samples: Vec<WaveformSample>,
    pub dropped_samples: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WaveformChannel {
    pub channel: Id,
    pub positive: PortId,
    pub negative: PortId,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WaveformSubscription {
    pub revision: Revision,
    pub channels: Vec<WaveformChannel>,
    pub samples_per_second: f64,
    /// -3 dB bandwidth of four cascaded continuous first-order low-pass poles.
    pub bandwidth_hertz: f64,
    pub max_samples: u32,
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub revision: Revision,
    pub mod_version: String,
    pub accepted_time: f64,
    pub payload: Vec<u8>,
}

/// Prescribed boundary values, addressed by stable branch and owning instance.
#[derive(Clone, Debug, PartialEq)]
pub enum IntervalValue {
    Audio { start: f64, samples: std::sync::Arc<[f64; 800]> },
    ShaftSpeed(f64),
}
#[derive(Clone, Debug, PartialEq)]
pub struct BranchInterval { pub branch: Id, pub owner: DeviceId, pub value: IntervalValue }
#[derive(Clone, Debug, PartialEq)]
pub struct IntervalInputs {
    pub revision: Revision, pub start: f64, pub end: f64, pub inputs: Vec<BranchInterval>,
}
