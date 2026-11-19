//! Linear-harness wire format, little-endian, bounded and exact length.
//! Compatibility is tied only to the existing mod metadata.
use crate::contract::{Advance, DeviceId, Id, PortId, Revision};

pub const MAGIC: u32 = 0x424e4d45; // EMNB: format marker, not a version
pub const MAX_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_BRANCHES: usize = 4096;

#[derive(Clone, Debug)]
pub struct BranchInput {
    pub id: Id,
    pub positive: PortId,
    pub negative: PortId,
    /// 1 R, 2 DC source, 3 C, 4 L, 5 diode, 6 sine source, 7 NPN, 8 PNP.
    pub model: u32,
    pub parameter: f64,
    /// Explicit initial storage: coulombs for C/junction C, webers for L; zero otherwise.
    pub initial: f64,
    pub auxiliary: Vec<f64>,
    pub additional_ports: Vec<PortId>,
    pub initial_extra: f64,
}

#[derive(Clone, Debug)]
pub enum Command {
    Repartition(crate::contract::Repartition),
    SetInterval(crate::contract::IntervalInputs),
    ExchangeEnergy {
        revision: Revision,
        branch: Id,
        delta: f64,
        maximum: f64,
        commit: bool,
    },
    Info,
    Create {
        revision: Revision,
        max_step: f64,
        accuracy: Option<[f64; 7]>,
        explicit_initial: bool,
        branches: Vec<BranchInput>,
    },
    Advance(Advance),
    Tick { revision: Revision, world_tick: u64, offset: f64, seconds: f64, edits: Vec<Command> },
    Read,
    ReadObservations(Revision),
    AcknowledgeEvents {
        revision: Revision,
        ids: Vec<Id>,
        destroyed: Vec<Id>,
    },
    SubscribeWaveforms(crate::contract::WaveformSubscription),
    Destroy,
    Cancel,
    Snapshot,
    Restore(Vec<u8>),
    AnalyzeAc {
        hertz: f64,
        excitations: Vec<(Id, [f64; 2])>,
    },
    SetParameters {
        revision: Revision,
        accepted_time: f64,
        changes: Vec<(Id, u32, Vec<f64>)>,
    },
    ApplyTopology {
        revision: Revision,
        accepted_time: f64,
        branches: Vec<BranchInput>,
        export_removed_energy: bool,
        parameter_edit: bool,
    },
}

pub struct Request {
    pub handle: u64,
    pub command: Command,
}

pub struct Reader<'a> {
    bytes: &'a [u8],
    cursor: usize,
}
impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Result<Self, String> {
        if bytes.len() > MAX_BYTES {
            return Err("Batch exceeds byte limit".into());
        }
        Ok(Self { bytes, cursor: 0 })
    }
    fn take<const N: usize>(&mut self) -> Result<[u8; N], String> {
        let end = self.cursor.checked_add(N).ok_or("Offset overflow")?;
        let data = self.bytes.get(self.cursor..end).ok_or("Truncated batch")?;
        self.cursor = end;
        Ok(data.try_into().expect("checked fixed width"))
    }
    pub fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.take()?))
    }
    pub fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_le_bytes(self.take()?))
    }
    pub fn f64(&mut self) -> Result<f64, String> {
        let value = f64::from_le_bytes(self.take()?);
        if !value.is_finite() {
            return Err("Non-finite numeric field".into());
        }
        Ok(value)
    }
    pub fn id(&mut self) -> Result<Id, String> {
        Ok(Id([self.u64()?, self.u64()?]))
    }
    pub fn revision(&mut self) -> Result<Revision, String> {
        Ok(Revision {
            session: self.id()?,
            topology: self.u64()?,
            parameters: self.u64()?,
            sequence: self.u64()?,
        })
    }
    pub fn port(&mut self) -> Result<PortId, String> {
        Ok(PortId {
            device: DeviceId {
                instance: self.id()?,
                generation: self.u64()?,
            },
            terminal: self.u32()?,
        })
    }
    pub fn finish(self) -> Result<(), String> {
        if self.cursor == self.bytes.len() {
            Ok(())
        } else {
            Err("Trailing batch bytes".into())
        }
    }
    pub fn blob(&mut self) -> Result<Vec<u8>, String> {
        let length = self.u32()? as usize;
        let end = self
            .cursor
            .checked_add(length)
            .ok_or("Blob length overflow")?;
        let bytes = self.bytes.get(self.cursor..end).ok_or("Truncated blob")?;
        self.cursor = end;
        Ok(bytes.to_vec())
    }
    pub fn string(&mut self) -> Result<String, String> {
        String::from_utf8(self.blob()?).map_err(|_| "Invalid UTF-8".into())
    }
}

pub fn decode(bytes: &[u8]) -> Result<Request, String> {
    decode_request(bytes, None)
}
fn decode_request(bytes: &[u8], tick_handle: Option<u64>) -> Result<Request, String> {
    let mut r = Reader::new(bytes)?;
    if r.u32()? != MAGIC || r.string()? != crate::MOD_VERSION {
        return Err("Wire format or mod metadata mismatch".into());
    }
    let operation = r.u32()?;
    let handle = r.u64()?;
    if (operation <= 1 || operation == 8 || operation == 21) != (handle == 0) {
        return Err("Invalid handle for operation".into());
    }
    if let Some(expected) = tick_handle
        && (handle != expected || !matches!(operation, 10 | 13 | 16 | 17 | 20)) {
        return Err("Invalid tick edit".into());
    }
    let command = match operation {
        21 => {
            let count = r.u32()? as usize;
            if count == 0 || count > 64 { return Err("Invalid repartition source count/time".into()); }
            let mut sources = Vec::with_capacity(count);
            for _ in 0..count { sources.push((r.u64()?, r.revision()?)); }
            let count = r.u32()? as usize;
            if count == 0 || count > 64 { return Err("Invalid repartition target count".into()); }
            let mut targets = Vec::with_capacity(count);let mut total = 0;
            for _ in 0..count {
                let revision = r.revision()?;let count = r.u32()? as usize;
                total += count;if total > MAX_BRANCHES { return Err("Repartition branch limit exceeded".into()); }
                let mut branches = Vec::with_capacity(count);for _ in 0..count { branches.push(r.id()?); }
                targets.push((revision, branches));
            }
            Command::Repartition(crate::contract::Repartition { sources, targets })
        }
        20 => Command::SetInterval(read_interval_fields(&mut r, tick_handle.is_some())?),
        0 => Command::Info,
        1 | 8 => {
            let revision = r.revision()?;
            let max_step = r.f64()?;
            if max_step <= 0.0 || revision.sequence != 0 {
                return Err("Create requires positive step and sequence zero".into());
            }
            let accuracy = if operation == 8 {
                Some([
                    r.f64()?,
                    r.f64()?,
                    r.f64()?,
                    r.f64()?,
                    r.f64()?,
                    r.f64()?,
                    r.f64()?,
                ])
            } else {
                None
            };
            let explicit_initial = match r.u32()? {
                0 => false,
                1 => true,
                _ => return Err("Unknown initialization mode".into()),
            };
            let count = r.u32()? as usize;
            if count > MAX_BRANCHES {
                return Err("Too many branches".into());
            }
            let mut branches = Vec::with_capacity(count);
            for _ in 0..count {
                let id = r.id()?;
                let positive = r.port()?;
                let negative = r.port()?;
                let model = r.u32()?;
                if !(1..=29).contains(&model) {
                    return Err("Unsupported model tag".into());
                }
                let parameter = r.f64()?;
                let initial = r.f64()?;
                if model != 2
                    && model != 6
                    && model != 17
                    && model != 21
                    && model != 23
                    && model != 24
                    && model != 25
                    && parameter <= 0.0
                {
                    return Err("R/C/L must be positive".into());
                }
                if (!explicit_initial
                    || model <= 2
                    || model == 15
                    || model == 16
                    || model == 17
                    || model == 18
                    || model == 19
                    || model == 22
                    || model == 23
                    || model == 24
                    || model == 25
                    || model == 26
                    || model == 27
                    || model == 28)
                    && initial != 0.0
                {
                    return Err("Unexpected initial storage".into());
                }
                let mut auxiliary = Vec::new();
                for _ in 0..match model {
                    15 => 1,
                    16 | 17 | 29 => 2,
                    5 => 2,
                    6 | 18 => 3,
                    7..=10 => 5,
                    19 => 6,
                    20 => 38,
                    21 => 8,
                    22 => 3,
                    23 => 6,
                    24 => 803,
                    25 => 2,
                    26 => 805,
                    27 => 1,
                    28 => 3,
                    11 | 12 => 4,
                    13 => 5,
                    _ => 0,
                } {
                    auxiliary.push(r.f64()?);
                }
                let mut additional_ports = Vec::new();
                if model == 20 {
                    let ports = auxiliary[5];
                    if ports.fract() != 0.0 || !(2.0..=32.0).contains(&ports) {
                        return Err("Invalid tapped wire port count".into());
                    }
                    for _ in 2..ports as usize {
                        additional_ports.push(r.port()?);
                    }
                }
                let initial_extra = if (7..=11).contains(&model) || model == 29
                    || (model == 14
                        || model == 16
                        || model == 17
                        || model == 19
                        || model == 22
                        || model == 25
                        || model == 26
                        || model == 27
                        || model == 28)
                {
                    additional_ports.push(r.port()?);
                    if model == 11 || model == 29
                        || model == 17
                        || model == 19
                        || model == 25
                        || model == 26
                        || model == 27
                        || model == 28
                    {
                        additional_ports.push(r.port()?);
                    }
                    r.f64()?
                } else {
                    0.0
                };
                if (!explicit_initial
                    || (model == 14
                        || model == 16
                        || model == 17
                        || model == 19
                        || model == 22
                        || model == 25
                        || model == 26
                        || model == 27
                        || model == 28))
                    && initial_extra != 0.0
                {
                    return Err("Unexpected junction initial charge".into());
                }
                branches.push(BranchInput {
                    id,
                    positive,
                    negative,
                    model,
                    parameter,
                    initial,
                    auxiliary,
                    additional_ports,
                    initial_extra,
                });
            }
            Command::Create {
                revision,
                max_step,
                accuracy,
                explicit_initial,
                branches,
            }
        }
        2 => {
            let request = Advance {
                revision: r.revision()?,
                accepted_start: r.f64()?,
                requested_end: r.f64()?,
                max_steps: r.u32()?,
                budget_nanos: r.u64()?,
            };
            request.validate().map_err(|d| d.reason)?;
            Command::Advance(request)
        }
        22 => {
            let revision = r.revision()?;
            let world_tick = r.u64()?;
            let offset = r.f64()?;
            let seconds = r.f64()?;
            if !(0.0..=0.050000001).contains(&seconds) || !(0.0..=0.050000001).contains(&offset) || offset + seconds > 0.050000001 || world_tick > (1<<50) {
                return Err("Invalid tick interval".into());
            }
            let count = r.u32()?;
            if count > 5 { return Err("Too many tick edits".into()); }
            let mut edits = Vec::new();
            for _ in 0..count { edits.push(decode_request(&r.blob()?, Some(handle))?.command); }
            Command::Tick { revision, world_tick, offset, seconds, edits }
        }
        3 => Command::Read,
        12 => Command::ReadObservations(r.revision()?),
        15 | 18 => {
            let revision = r.revision()?;
            let count = r.u32()? as usize;
            if count == 0 || count > MAX_BRANCHES {
                return Err("Invalid event acknowledgement boundary/count".into());
            }
            let mut ids = Vec::with_capacity(count);
            for _ in 0..count {
                ids.push(r.id()?);
            }
            let mut destroyed = Vec::new();
            if operation == 18 {
                let count = r.u32()? as usize;
                if count > MAX_BRANCHES {
                    return Err("Too many destroyed physical branches".into());
                }
                for _ in 0..count {
                    destroyed.push(r.id()?);
                }
            }
            Command::AcknowledgeEvents {
                revision,
                ids,
                destroyed,
            }
        }
        13 => {
            let revision = r.revision()?;
            let samples_per_second = r.f64()?;
            let bandwidth_hertz = r.f64()?;
            let max_samples = r.u32()?;
            let count = r.u32()? as usize;
            if count > 32
                || count
                    .checked_mul(max_samples as usize)
                    .is_none_or(|n| n > crate::contract::MAX_WAVEFORM_SAMPLES)
            {
                return Err("Waveform subscription resource limit exceeded".into());
            }
            let mut channels = Vec::with_capacity(count);
            for _ in 0..count {
                channels.push(crate::contract::WaveformChannel {
                    channel: r.id()?,
                    positive: r.port()?,
                    negative: r.port()?,
                });
            }
            Command::SubscribeWaveforms(crate::contract::WaveformSubscription {
                revision,
                channels,
                samples_per_second,
                bandwidth_hertz,
                max_samples,
            })
        }
        4 => Command::Destroy,
        5 => Command::Cancel,
        6 => Command::Snapshot,
        7 => Command::Restore(r.blob()?),
        9 => {
            let hertz = r.f64()?;
            let count = r.u32()? as usize;
            if hertz < 0.0 || count > MAX_BRANCHES {
                return Err("Invalid AC frequency or excitation count".into());
            }
            let mut excitations = Vec::with_capacity(count);
            for _ in 0..count {
                excitations.push((r.id()?, [r.f64()?, r.f64()?]));
            }
            Command::AnalyzeAc { hertz, excitations }
        }
        10 => {
            let revision = r.revision()?;
            let accepted_time = if tick_handle.is_some() { 0.0 } else { r.f64()? };
            let count = r.u32()? as usize;
            if accepted_time < 0.0 || count > MAX_BRANCHES {
                return Err("Invalid parameter boundary or target count".into());
            }
            let mut changes = Vec::with_capacity(count);
            for _ in 0..count {
                let id = r.id()?;
                let model = r.u32()?;
                let parameter_count = match model {
                    1..=4 | 14 => 1,
                    15 => 2,
                    16 | 17 | 29 => 3,
                    5 => 3,
                    6 | 18 => 4,
                    7..=10 => 6,
                    19 => 7,
                    20 => 39,
                    21 => 9,
                    22 => 4,
                    23 => 7,
                    24 => 804,
                    25 => 3,
                    26 => 806,
                    27 => 2,
                    28 => 4,
                    11 | 12 => 5,
                    13 => 6,
                    _ => return Err("Unknown parameter model".into()),
                };
                let mut parameters = Vec::with_capacity(parameter_count);
                for _ in 0..parameter_count {
                    parameters.push(r.f64()?);
                }
                changes.push((id, model, parameters));
            }
            Command::SetParameters {
                revision,
                accepted_time,
                changes,
            }
        }
        11 | 14 | 16 | 17 => {
            let revision = r.revision()?;
            let accepted_time = if tick_handle.is_some() { 0.0 } else { r.f64()? };
            let count = r.u32()? as usize;
            if accepted_time < 0.0 || count > MAX_BRANCHES {
                return Err("Invalid topology boundary or branch count".into());
            }
            let mut branches = Vec::with_capacity(count);
            for _ in 0..count {
                let id = r.id()?;
                let positive = r.port()?;
                let negative = r.port()?;
                let model = r.u32()?;
                let auxiliary_count = match model {
                    1..=4 | 14 => 0,
                    15 => 1,
                    16 | 17 | 29 => 2,
                    5 => 2,
                    6 | 18 => 3,
                    7..=10 => 5,
                    19 => 6,
                    20 => 38,
                    21 => 8,
                    22 => 3,
                    23 => 6,
                    24 => 803,
                    25 => 2,
                    26 => 805,
                    27 => 1,
                    28 => 3,
                    11 | 12 => 4,
                    13 => 5,
                    _ => return Err("Unknown topology model".into()),
                };
                let parameter = r.f64()?;
                let mut auxiliary = Vec::with_capacity(auxiliary_count);
                for _ in 0..auxiliary_count {
                    auxiliary.push(r.f64()?);
                }
                let mut additional_ports = Vec::new();
                if (7..=11).contains(&model) || model == 29
                    || (model == 14
                        || model == 16
                        || model == 17
                        || model == 19
                        || model == 22
                        || model == 25
                        || model == 26
                        || model == 27
                        || model == 28)
                {
                    additional_ports.push(r.port()?);
                    if model == 11 || model == 29
                        || model == 17
                        || model == 19
                        || model == 25
                        || model == 26
                        || model == 27
                        || model == 28
                    {
                        additional_ports.push(r.port()?);
                    }
                }
                if model == 20 {
                    let ports = auxiliary[5];
                    if ports.fract() != 0.0 || !(2.0..=32.0).contains(&ports) {
                        return Err("Invalid tapped wire port count".into());
                    }
                    for _ in 2..ports as usize {
                        additional_ports.push(r.port()?);
                    }
                }
                branches.push(BranchInput {
                    id,
                    positive,
                    negative,
                    model,
                    parameter,
                    initial: 0.0,
                    auxiliary,
                    additional_ports,
                    initial_extra: 0.0,
                });
            }
            Command::ApplyTopology {
                revision,
                accepted_time,
                branches,
                export_removed_energy: operation == 14 || operation == 17,
                parameter_edit: operation == 16 || operation == 17,
            }
        }
        19 => {
            let revision = r.revision()?;
            let branch = r.id()?;
            let delta = r.f64()?;
            let maximum = r.f64()?;
            let commit = match r.u32()? {
                0 => false,
                1 => true,
                _ => return Err("Invalid exchange commit flag".into()),
            };
            Command::ExchangeEnergy {
                revision,
                branch,
                delta,
                maximum,
                commit,
            }
        }
        _ => return Err("Unknown operation".into()),
    };
    r.finish()?;
    Ok(Request { handle, command })
}

#[derive(Default)]
pub struct Writer(pub Vec<u8>);
impl Writer {
    pub fn u32(&mut self, value: u32) {
        self.0.extend(value.to_le_bytes());
    }
    pub fn u64(&mut self, value: u64) {
        self.0.extend(value.to_le_bytes());
    }
    pub fn f64(&mut self, value: f64) {
        self.0.extend(value.to_le_bytes());
    }
    pub fn id(&mut self, value: Id) {
        self.u64(value.0[0]);
        self.u64(value.0[1]);
    }
    pub fn revision(&mut self, value: Revision) {
        self.id(value.session);
        self.u64(value.topology);
        self.u64(value.parameters);
        self.u64(value.sequence);
    }
    pub fn port(&mut self, value: PortId) {
        self.id(value.device.instance);
        self.u64(value.device.generation);
        self.u32(value.terminal);
    }
    pub fn string(&mut self, value: &str) {
        self.u32(value.len() as u32);
        self.0.extend(value.as_bytes());
    }
}

pub fn read_interval(r: &mut Reader<'_>) -> Result<crate::contract::IntervalInputs, String> {
    read_interval_fields(r, false)
}
fn read_interval_fields(r: &mut Reader<'_>, within_tick: bool) -> Result<crate::contract::IntervalInputs,String> {
    use crate::contract::*;
    let revision = r.revision()?;
    let (start,end) = if within_tick { (0.0,0.05) } else { (r.f64()?,r.f64()?) };
    let count = r.u32()? as usize;
    if count > MAX_BRANCHES { return Err("Too many interval inputs".into()); }
    let mut inputs = Vec::with_capacity(count);
    for _ in 0..count {
        let branch = r.id()?;
        let owner = DeviceId { instance: r.id()?, generation: r.u64()? };
        let value = match r.u32()? {
            0 => {
                let start = if within_tick { 0.0 } else { r.f64()? };
                let mut samples = [0.0; 800];
                for v in &mut samples { *v = r.f64()?; }
                IntervalValue::Audio { start, samples: samples.into() }
            },
            1 => IntervalValue::ShaftSpeed(r.f64()?),
            _ => return Err("Unknown interval input kind".into()),
        };
        inputs.push(BranchInterval { branch, owner, value });
    }
    Ok(IntervalInputs { revision, start, end, inputs })
}
pub fn write_interval(w: &mut Writer, input: &crate::contract::IntervalInputs) {
    use crate::contract::IntervalValue;
    w.revision(input.revision); w.f64(input.start); w.f64(input.end); w.u32(input.inputs.len() as u32);
    for b in &input.inputs {
        w.id(b.branch); w.id(b.owner.instance); w.u64(b.owner.generation);
        match &b.value {
            IntervalValue::Audio { start, samples } => { w.u32(0); w.f64(*start); for v in samples.iter() { w.f64(*v); } },
            IntervalValue::ShaftSpeed(v) => { w.u32(1); w.f64(*v); },
        }
    }
}
