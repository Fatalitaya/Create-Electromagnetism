//! Pure two-terminal SI evaluations. History belongs to the accepted circuit state.
//! Local unknowns are [va, vb, i_ab].
//! Residual rows are terminal KCL contributions and the branch constraint.

use super::{DeviceModel, Evaluation, ResidualUnit};
use em_protocol::contract::{Diagnostic, Status};

#[derive(Clone, Debug, PartialEq)]
pub enum Element {
    FieldAcShaft {
        k: f64,
        omega: f64,
        pole_pairs: f64,
        phase: f64,
    },
    /// Wound-field DC machine, no armature reaction. Field port is an ideal
    /// current sensor; explicit field/copper inductances own magnetic storage.
    FieldShaft {
        k: f64,
        omega: f64,
    },
    RadioTransmitter {
        hertz: f64,
        phase: f64,
        audio: super::audio::Audio,
    },
    /// Reciprocal carrier mixer. Both electrical ports exchange exactly equal
    /// power; the prescribed conversion ratio is bounded and has no energy source.
    Mixer {
        hertz: f64,
        phase: f64,
        ratio: f64,
    },
    Audio(super::audio::Audio),
    /// Finite-time external lightning current with an explicit return and channel impedance.
    Impulse {
        start: f64,
        peak: f64,
        rise: f64,
        fall: f64,
        ohms: f64,
        continuing: f64,
        duration: f64,
    },
    Regulator(super::regulator::Regulator),
    Arc(super::arc::Arc),
    TappedWire(super::tapped::TappedWire),
    Relay {
        coil_ohms: f64,
        closed_ohms: f64,
        open_ohms: f64,
        pickup_volts: f64,
        dropout_volts: f64,
        inverted: bool,
        active: bool,
    },
    /// A phase winding at prescribed shaft speed. Windings with the same owning
    /// device share one accepted rotor clock; phase is a fixed winding offset.
    AcShaft {
        k: f64,
        omega: f64,
        pole_pairs: f64,
        phase: f64,
    },
    /// Isolated, finite-ratio regulated DC converter. Ports: output +/-, buffer +/-.
    /// The ratio remains finite at zero buffer voltage; losses are nonnegative.
    Converter {
        output_volts: f64,
        knee_volts: f64,
        efficiency: f64,
    },
    /// Passive charge/discharge contact. Ports: input, cell positive, cell negative.
    /// Compose with a cell capacitor and explicit copper/polarization impedances.
    /// Conductance vanishes at the corresponding SOC boundary, never generates power.
    BatteryContact {
        ohms: f64,
        full_volts: f64,
        taper_volts: f64,
    },
    /// Lossless electrical/mechanical transducer at a prescribed shaft velocity.
    /// v_ab = k*omega; torque delivered to the shaft = k*i_ab, including omega=0.
    /// Compose winding inductance and thermal copper resistance as explicit branches.
    /// No rotor inertia is integrated: Create owns the imposed shaft speed boundary.
    ShaftBoundary {
        newton_metres_per_ampere: f64,
        radians_per_second: f64,
    },
    /// Three-terminal wattmeter contact: input, voltage reference, output.
    /// Reference port draws no current. Observe (Vin-Vref)*Iin, while physical
    /// absorption is only the finite contact's I²R loss.
    Meter {
        ohms: f64,
    },
    ThermalResistor(super::thermal::ThermalResistor),
    Windings(super::windings::Windings),
    Bipolar(super::bipolar::Bipolar),
    Mosfet(super::mosfet::Mosfet),
    Resistor {
        ohms: f64,
    },
    DcVoltageSource {
        volts: f64,
    },
    Capacitor {
        farads: f64,
    },
    Inductor {
        henries: f64,
    },
    /// Shockley junction at fixed temperature; Vt includes ideality factor.
    Diode {
        saturation_amperes: f64,
        thermal_volts: f64,
        junction_farads: f64,
    },
    /// Configured frequency and initial phase. Sessions own the evolving accepted phase.
    SineVoltageSource {
        offset: f64,
        peak: f64,
        hertz: f64,
        phase: f64,
    },
}


pub(crate) fn rejected(reason: &str) -> Diagnostic {
    Diagnostic {
        status: Status::Rejected,
        reason: reason.into(),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct InternalState {
    pub row: usize,
    pub unit: super::UnknownUnit,
    /// Affine thermal resistances resolve even positive near-zero candidates.
    pub reconstruct_always: bool,
}

impl Element {
    /// Explicit classification: new variants must choose their AC bias contract.
    pub fn supports_stationary_ac_bias(&self) -> bool {
        match self {
            Self::Audio(_) | Self::Impulse { .. } | Self::SineVoltageSource { .. }
            | Self::AcShaft { .. } | Self::FieldAcShaft { .. }
            | Self::RadioTransmitter { .. } | Self::Mixer { .. } => false,
            Self::FieldShaft { .. } | Self::Regulator(_) | Self::Arc(_)
            | Self::TappedWire(_) | Self::Relay { .. } | Self::Converter { .. }
            | Self::BatteryContact { .. } | Self::ShaftBoundary { .. } | Self::Meter { .. }
            | Self::ThermalResistor(_) | Self::Windings(_) | Self::Bipolar(_)
            | Self::Mosfet(_) | Self::Resistor { .. } | Self::DcVoltageSource { .. }
            | Self::Capacitor { .. } | Self::Inductor { .. } | Self::Diode { .. } => true,
        }
    }

    /// Local ordering mirrors evaluate(): terminal potentials, port currents,
    /// then model-owned internal states. Equation units must not be used here.
    pub fn unknown_units(&self) -> Vec<super::UnknownUnit> {
        use super::UnknownUnit::{Ampere, Volt};
        let mut units = vec![Ampere; self.unknown_count()];
        units[0] = Volt;
        units[1] = Volt;
        for port in 2..self.port_count() { units[2 * port - 1] = Volt; }
        if let Some(state) = self.internal_state() { units[state.row] = state.unit; }
        units
    }

    pub(crate) fn detector_row(&self) -> Option<usize> {
        matches!(self, Self::Regulator(_)).then_some(super::regulator::DETECTOR_ROW)
    }

    pub(crate) fn internal_state(&self) -> Option<InternalState> {
        if let Some((_, row)) = self.thermal_node() {
            Some(InternalState {
                row,
                unit: super::UnknownUnit::Kelvin,
                reconstruct_always: matches!(self, Self::ThermalResistor(_)),
            })
        } else {
            self.detector_row().map(|row| InternalState {
                row,
                unit: super::UnknownUnit::VoltSquared,
                reconstruct_always: false,
            })
        }
    }

    pub(crate) fn negative_internal_state(&self, values: &[f64]) -> Option<InternalState> {
        self.internal_state().filter(|state| values[state.row] < 0.0)
    }

    pub fn thermal_node(&self) -> Option<(super::thermal::ThermalResistor, usize)> {
        match *self {
            Self::Arc(model) => Some((model.thermal(), 3)),
            Self::ThermalResistor(model) => Some((model, 3)),
            Self::TappedWire(model) => Some((model.thermal, 2 * model.ports - 1)),
            _ => None,
        }
    }
    pub fn relay_transition(&self, coil_voltage: f64) -> Option<bool> {
        if let Self::Relay {
            pickup_volts,
            dropout_volts,
            active,
            ..
        } = *self
        {
            if !active && coil_voltage.abs() >= pickup_volts {
                Some(true)
            } else if active && coil_voltage.abs() <= dropout_volts {
                Some(false)
            } else {
                None
            }
        } else {
            None
        }
    }
    pub fn source_clock(&self) -> Option<(f64, f64)> {
        match *self {
            Self::FieldAcShaft {
                omega, pole_pairs, ..
            } => Some((omega * pole_pairs / std::f64::consts::TAU, 0.0)),
            Self::RadioTransmitter { hertz, phase, .. } => Some((hertz, phase)),
            Self::Mixer { hertz, phase, .. } => Some((hertz, phase)),
            Self::SineVoltageSource { hertz, phase, .. } => Some((hertz, phase)),
            Self::AcShaft {
                omega, pole_pairs, ..
            } => Some((omega * pole_pairs / std::f64::consts::TAU, 0.0)),
            _ => None,
        }
    }
    pub fn source_voltage(&self, clock: f64) -> Option<f64> {
        match *self {
            Self::SineVoltageSource { offset, peak, .. } => Some(offset + peak * clock.sin()),
            Self::AcShaft {
                k, omega, phase, ..
            } => Some(k * omega * (clock + phase).sin()),
            _ => None,
        }
    }
    pub fn impulse_current(&self, time: f64) -> Option<f64> {
        if let Self::Impulse {
            start,
            peak,
            rise,
            fall,
            continuing,
            duration,
            ..
        } = *self
        {
            let t = time - start;
            if t < 0.0 || t > duration {
                return Some(0.0);
            }
            let maximum = rise * fall / (fall - rise) * (fall / rise).ln();
            let normalization = (-maximum / fall).exp() - (-maximum / rise).exp();
            Some(
                peak * ((-t / fall).exp() - (-t / rise).exp()) / normalization
                    + continuing * (std::f64::consts::PI * t / duration).sin().powi(2),
            )
        } else {
            None
        }
    }
    pub fn port_count(&self) -> usize {
        if matches!(self, Self::FieldAcShaft { .. }) {
            return 4;
        }
        if let Self::TappedWire(model) = *self {
            return model.ports;
        }
        if matches!(
            self,
            Self::Windings(_)
                | Self::Converter { .. }
                | Self::Relay { .. }
                | Self::Mixer { .. }
                | Self::RadioTransmitter { .. }
                | Self::FieldShaft { .. }
        ) {
            return 4;
        }
        if matches!(
            self,
            Self::Bipolar(_)
                | Self::Mosfet(_)
                | Self::Meter { .. }
                | Self::BatteryContact { .. }
                | Self::Regulator(_)
        ) {
            3
        } else {
            2
        }
    }
    pub fn electrical_edges(&self) -> Vec<(usize, usize)> {
        if matches!(self, Self::FieldAcShaft { .. }) {
            return vec![(0, 1), (2, 3)];
        }
        if matches!(self, Self::BatteryContact { .. }) {
            vec![(0, 1)]
        } else if matches!(self, Self::Meter { .. }) {
            vec![(0, 2)]
        } else if matches!(
            self,
            Self::Windings(_)
                | Self::Converter { .. }
                | Self::Relay { .. }
                | Self::Mixer { .. }
                | Self::RadioTransmitter { .. }
                | Self::FieldShaft { .. }
        ) {
            vec![(0, 1), (2, 3)]
        } else {
            (1..self.port_count()).map(|j| (0, j)).collect()
        }
    }
    pub fn is_dynamic(&self) -> bool {
        matches!(self, Self::Regulator(_))
            || self.thermal_node().is_some()
            || self.capacitance().is_some()
            || matches!(
                self,
                Self::Inductor { .. }
                    | Self::Bipolar(_)
                    | Self::Mosfet(_)
                    | Self::Windings(_)
                    | Self::ThermalResistor(_)
            )
    }
    pub fn control_capacitances(&self) -> Option<(f64, f64)> {
        match self {
            Self::Bipolar(m) => Some((m.base_emitter_farads, m.base_collector_farads)),
            Self::Mosfet(m) => Some((m.gate_source_farads, m.gate_drain_farads)),
            _ => None,
        }
    }
    pub fn same_kind(&self, other: &Self) -> bool {
        if self.thermal_node().is_some() && other.thermal_node().is_some() {
            return true;
        }
        matches!(
            (self, other),
            (Self::ThermalResistor(_), Self::ThermalResistor(_))
        ) || self.parameters().0 == other.parameters().0
    }
    pub fn validate(&self) -> Result<(), Diagnostic> {
        match *self {
            Self::FieldShaft { k, omega }
                if k.is_finite() && k.abs() <= 1e6 && omega.is_finite() && omega.abs() <= 1e6 =>
            {
                Ok(())
            }
            Self::FieldAcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            } => Self::AcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            }
            .validate(),
            Self::RadioTransmitter {
                hertz,
                phase,
                ref audio,
            } => {
                audio.validate()?;
                Self::Mixer {
                    hertz,
                    phase,
                    ratio: 1.0,
                }
                .validate()
            }
            Self::Mixer {
                hertz,
                phase,
                ratio,
            } if [hertz, phase, ratio].iter().all(|v| v.is_finite())
                && (0.0..=20000.0).contains(&hertz)
                && ratio > 0.0
                && ratio <= 100.0 =>
            {
                Ok(())
            }
            Self::Audio(ref model) => model.validate(),
            Self::Impulse {
                start,
                peak,
                rise,
                fall,
                ohms,
                continuing,
                duration,
            } if [start, peak, rise, fall, ohms, continuing, duration]
                .iter()
                .all(|v| v.is_finite())
                && start >= 0.0
                && peak >= 0.0
                && continuing >= 0.0
                && rise > 0.0
                && fall > rise
                && ohms > 0.0
                && duration >= fall =>
            {
                Ok(())
            }
            Self::Regulator(model) => model.validate(),
            Self::Arc(model) => model.validate(),
            Self::TappedWire(model) => model.validate(),
            Self::Relay {
                coil_ohms,
                closed_ohms,
                open_ohms,
                pickup_volts,
                dropout_volts,
                ..
            } if [
                coil_ohms,
                closed_ohms,
                open_ohms,
                pickup_volts,
                dropout_volts,
            ]
            .iter()
            .all(|v| v.is_finite())
                && coil_ohms > 0.0
                && closed_ohms > 0.0
                && open_ohms > closed_ohms
                && dropout_volts >= 0.0
                && pickup_volts > dropout_volts =>
            {
                Ok(())
            }
            Self::AcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            } if [k, omega, pole_pairs, phase].iter().all(|x| x.is_finite())
                && k >= 0.0
                && k <= 1e6
                && omega.abs() <= 1e6
                && (1.0..=128.0).contains(&pole_pairs)
                && pole_pairs.fract() == 0.0 =>
            {
                Ok(())
            }
            Self::Converter {
                output_volts,
                knee_volts,
                efficiency,
            } if output_volts.is_finite()
                && output_volts >= 0.0
                && knee_volts.is_finite()
                && knee_volts > 0.0
                && efficiency.is_finite()
                && efficiency > 0.0
                && efficiency <= 1.0 =>
            {
                Ok(())
            }
            Self::BatteryContact {
                ohms,
                full_volts,
                taper_volts,
            } if [ohms, full_volts, taper_volts]
                .iter()
                .all(|x| x.is_finite() && *x > 0.0)
                && taper_volts <= full_volts =>
            {
                Ok(())
            }
            Self::ShaftBoundary {
                newton_metres_per_ampere: k,
                radians_per_second: omega,
            } if k.is_finite()
                && (0.0..=1e6).contains(&k)
                && omega.is_finite()
                && omega.abs() <= 1e6 =>
            {
                Ok(())
            }
            Self::ThermalResistor(model) => model.validate(),
            Self::Windings(model) => model.validate(),
            Self::Bipolar(model) => model.validate(),
            Self::Mosfet(model) => model.validate(),
            Self::Resistor { ohms } | Self::Meter { ohms } if ohms.is_finite() && ohms > 0.0 => {
                Ok(())
            }
            Self::DcVoltageSource { volts } if volts.is_finite() => Ok(()),
            Self::Capacitor { farads } if farads.is_finite() && farads > 0.0 => Ok(()),
            Self::Inductor { henries } if henries.is_finite() && henries > 0.0 => Ok(()),
            Self::Diode {
                saturation_amperes,
                thermal_volts,
                junction_farads,
            } if saturation_amperes.is_finite()
                && saturation_amperes > 0.0
                && thermal_volts.is_finite()
                && thermal_volts > 0.0
                && junction_farads.is_finite()
                && junction_farads >= 0.0 =>
            {
                Ok(())
            }
            Self::SineVoltageSource {
                offset,
                peak,
                hertz,
                phase,
            } if [offset, peak, hertz, phase].iter().all(|v| v.is_finite())
                && peak >= 0.0
                && hertz >= 0.0 =>
            {
                Ok(())
            }
            _ => Err(rejected(
                "Invalid constitutive parameters: expected finite values within model ranges",
            )),
        }
    }

    pub fn is_nonlinear(&self) -> bool {
        if matches!(
            self,
            Self::TappedWire(_) | Self::Arc(_) | Self::Regulator(_)
        ) {
            return true;
        }
        matches!(
            self,
            Self::Diode { .. }
                | Self::Bipolar(_)
                | Self::Mosfet(_)
                | Self::ThermalResistor(_)
                | Self::BatteryContact { .. }
                | Self::Converter { .. }
        )
    }

    /// With fixed resistance, heat depends on the electrical solution but the
    /// electrical equations do not depend on the branch temperature.
    pub(crate) fn fixed_resistance_thermal_row(&self) -> Option<usize> {
        match self {
            Self::ThermalResistor(model) if model.coefficient_per_kelvin == 0.0 => Some(3),
            Self::TappedWire(model) if model.thermal.coefficient_per_kelvin == 0.0 => {
                Some(2 * model.ports - 1)
            }
            _ => None,
        }
    }

    pub fn constraint_is_current(&self) -> bool {
        matches!(
            self,
            Self::Capacitor { .. } | Self::Diode { .. } | Self::Arc(_) | Self::Impulse { .. }
        )
    }

    pub fn capacitance(&self) -> Option<f64> {
        match *self {
            Self::Capacitor { farads } => Some(farads),
            Self::Diode {
                junction_farads, ..
            } if junction_farads > 0.0 => Some(junction_farads),
            _ => None,
        }
    }

}

impl DeviceModel for Element {
    fn unknown_count(&self) -> usize {
        2 * self.port_count() - 1
            + usize::from(self.thermal_node().is_some() || matches!(self, Self::Regulator(_)))
    }

    fn evaluate(&self, values: &[f64], time_seconds: f64) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        if values.len() != self.unknown_count()
            || values.iter().any(|v| !v.is_finite())
            || !time_seconds.is_finite()
            || time_seconds < 0.0
        {
            return Err(rejected("Invalid local values or physical time"));
        }
        if let Self::ThermalResistor(model) = self {
            return model.evaluate(values);
        }
        if let Self::TappedWire(model) = self {
            return model.evaluate(values);
        }
        if let Self::Arc(model) = self {
            return model.evaluate(values);
        }
        if let Self::Regulator(model) = self {
            return model.evaluate(values);
        }
        if let Self::Audio(model) = self {
            return model.evaluate(values, time_seconds);
        }
        if let Self::FieldAcShaft {
            k,
            omega,
            pole_pairs,
            phase,
        } = *self
        {
            return Self::FieldShaft {
                k: k * (omega * pole_pairs * time_seconds + phase).sin(),
                omega,
            }
            .evaluate(values, time_seconds);
        }
        if let Self::FieldShaft { k, omega } = *self {
            let mut jac = vec![0.0; 49];
            jac[2] = 1.0;
            jac[9] = -1.0;
            jac[11] = -1.0;
            jac[13] = -1.0;
            jac[14] = 1.0;
            jac[15] = -1.0;
            jac[18] = -k * omega;
            jac[25] = 1.0;
            jac[31] = 1.0;
            jac[33] = -1.0;
            jac[41] = 1.0;
            jac[46] = 1.0;
            jac[48] = 1.0;
            let mut units = vec![ResidualUnit::Ampere; 7];
            units[2] = ResidualUnit::Volt;
            units[4] = ResidualUnit::Volt;
            return Ok(Evaluation {
                residual_units: units,
                residual: vec![
                    values[2],
                    -values[2] - values[4] - values[6],
                    values[0] - values[1] - k * omega * values[4],
                    values[4],
                    values[3] - values[5],
                    values[6],
                    values[4] + values[6],
                ],
                residual_jacobian: jac,
                charge: vec![0.0; 7],
                charge_jacobian: vec![0.0; 49],
                stored_joules: 0.0,
                dissipated_watts: 0.0,
            });
        }
        if let Self::RadioTransmitter {
            hertz,
            phase,
            ref audio,
        } = *self
        {
            return Self::Mixer {
                hertz,
                phase,
                ratio: 0.5 * (1.0 + audio.voltage(time_seconds).clamp(-1.0, 1.0)),
            }
            .evaluate(values, time_seconds);
        }
        if let Self::Mixer {
            hertz,
            phase,
            ratio,
        } = *self
        {
            let gain = ratio * (std::f64::consts::TAU * hertz * time_seconds + phase).cos();
            let mut jac = vec![0.0; 49];
            jac[2] = 1.0;
            jac[9] = -1.0;
            jac[11] = -1.0;
            jac[13] = -1.0;
            jac[14] = -gain;
            jac[15] = gain;
            jac[17] = 1.0;
            jac[19] = -1.0;
            jac[25] = 1.0;
            jac[30] = 1.0;
            jac[32] = gain;
            jac[41] = 1.0;
            jac[46] = 1.0;
            jac[48] = 1.0;
            let mut units = vec![ResidualUnit::Ampere; 7];
            units[2] = ResidualUnit::Volt;
            return Ok(Evaluation {
                residual_units: units,
                residual: vec![
                    values[2],
                    -values[2] - values[4] - values[6],
                    values[3] - values[5] - gain * (values[0] - values[1]),
                    values[4],
                    values[2] + gain * values[4],
                    values[6],
                    values[4] + values[6],
                ],
                residual_jacobian: jac,
                charge: vec![0.0; 7],
                charge_jacobian: vec![0.0; 49],
                stored_joules: 0.0,
                dissipated_watts: 0.0,
            });
        }
        if let Self::Windings(model) = self {
            return model.evaluate(values);
        }
        if let Self::Bipolar(model) = self {
            return model.evaluate(values);
        }
        if let Self::Mosfet(model) = self {
            return model.evaluate(values);
        }
        if let Self::Relay {
            coil_ohms,
            closed_ohms,
            open_ohms,
            inverted,
            active,
            ..
        } = *self
        {
            let resistance = if active ^ inverted {
                closed_ohms
            } else {
                open_ohms
            };
            let mut jac = vec![0.0; 49];
            jac[2] = 1.0;
            jac[9] = -1.0;
            jac[11] = -1.0;
            jac[13] = -1.0;
            jac[14] = 1.0;
            jac[15] = -1.0;
            jac[16] = -coil_ohms;
            jac[25] = 1.0;
            jac[31] = 1.0;
            jac[32] = -resistance;
            jac[33] = -1.0;
            jac[41] = 1.0;
            jac[46] = 1.0;
            jac[48] = 1.0;
            let mut units = vec![ResidualUnit::Ampere; 7];
            units[2] = ResidualUnit::Volt;
            units[4] = ResidualUnit::Volt;
            return Ok(Evaluation {
                residual_units: units,
                residual: vec![
                    values[2],
                    -values[2] - values[4] - values[6],
                    values[0] - values[1] - coil_ohms * values[2],
                    values[4],
                    values[3] - values[5] - resistance * values[4],
                    values[6],
                    values[4] + values[6],
                ],
                residual_jacobian: jac,
                charge: vec![0.0; 7],
                charge_jacobian: vec![0.0; 49],
                stored_joules: 0.0,
                dissipated_watts: coil_ohms * values[2] * values[2]
                    + resistance * values[4] * values[4],
            });
        }
        if let Self::Converter {
            output_volts,
            knee_volts,
            efficiency,
        } = *self
        {
            let input = values[3] - values[5];
            let output = values[0] - values[1];
            let current = values[2];
            let ratio = output_volts / input.max(knee_volts);
            let derivative = if input > knee_volts {
                -ratio / input
            } else {
                0.0
            };
            let factor = if input * current < 0.0 {
                1.0 / efficiency
            } else {
                efficiency
            };
            let mut jac = vec![0.0; 49];
            jac[2] = 1.0;
            jac[9] = -1.0;
            jac[11] = -1.0;
            jac[13] = -1.0;
            jac[14] = 1.0;
            jac[15] = -1.0;
            jac[17] = -ratio - input * derivative;
            jac[19] = ratio + input * derivative;
            jac[25] = 1.0;
            jac[30] = factor * ratio;
            jac[31] = factor * derivative * current;
            jac[32] = 1.0;
            jac[33] = -factor * derivative * current;
            jac[41] = 1.0;
            jac[46] = 1.0;
            jac[48] = 1.0;
            let loss = (factor - 1.0) * (-ratio * input * current);
            let mut units = vec![ResidualUnit::Ampere; 7];
            units[2] = ResidualUnit::Volt;
            return Ok(Evaluation {
                residual_units: units,
                residual: vec![
                    current,
                    -current - values[4] - values[6],
                    output - ratio * input,
                    values[4],
                    values[4] + factor * ratio * current,
                    values[6],
                    values[4] + values[6],
                ],
                residual_jacobian: jac,
                charge: vec![0.0; 7],
                charge_jacobian: vec![0.0; 49],
                stored_joules: 0.0,
                dissipated_watts: loss,
            });
        }
        if let Self::BatteryContact {
            ohms,
            full_volts,
            taper_volts,
        } = *self
        {
            let u = values[0] - values[1];
            let cell = values[1] - values[3];
            let (fraction, slope) = if u >= 0.0 {
                ((full_volts - cell) / taper_volts, -1.0 / taper_volts)
            } else {
                (cell / taper_volts, 1.0 / taper_volts)
            };
            let conductance = fraction.clamp(0.0, 1.0) / ohms;
            let dg = if fraction > 0.0 && fraction < 1.0 {
                slope / ohms
            } else {
                0.0
            };
            let mut jac = vec![0.0; 25];
            jac[2] = 1.0;
            jac[7] = -1.0;
            jac[9] = -1.0;
            jac[10] = -conductance;
            jac[11] = conductance - u * dg;
            jac[12] = 1.0;
            jac[13] = u * dg;
            jac[19] = 1.0;
            jac[24] = 1.0;
            return Ok(Evaluation {
                residual_units: vec![ResidualUnit::Ampere; 5],
                residual: vec![
                    values[2],
                    -values[2] - values[4],
                    values[2] - u * conductance,
                    values[4],
                    values[4],
                ],
                charge: vec![0.0; 5],
                charge_jacobian: vec![0.0; 25],
                residual_jacobian: jac,
                stored_joules: 0.0,
                dissipated_watts: u * u * conductance,
            });
        }
        if let Self::Meter { ohms } = *self {
            let [input, _reference, current, output, return_current] =
                [values[0], values[1], values[2], values[3], values[4]];
            let mut jac = vec![0.0; 25];
            jac[2] = 1.0;
            jac[7] = -1.0;
            jac[9] = -1.0;
            jac[10] = 1.0;
            jac[12] = -ohms;
            jac[13] = -1.0;
            jac[19] = 1.0;
            jac[22] = 1.0;
            jac[24] = 1.0;
            return Ok(Evaluation {
                residual: vec![
                    current,
                    -current - return_current,
                    input - output - ohms * current,
                    return_current,
                    current + return_current,
                ],
                charge: vec![0.0; 5],
                residual_jacobian: jac,
                charge_jacobian: vec![0.0; 25],
                residual_units: vec![
                    ResidualUnit::Ampere,
                    ResidualUnit::Ampere,
                    ResidualUnit::Volt,
                    ResidualUnit::Ampere,
                    ResidualUnit::Ampere,
                ],
                stored_joules: 0.0,
                dissipated_watts: ohms * current * current,
            });
        }
        let [va, vb, current] = [values[0], values[1], values[2]];
        let voltage = va - vb;
        let mut charge = vec![0.0; 3];
        let mut charge_jacobian = vec![0.0; 9];
        let (constraint, derivatives, stored_joules, dissipated_watts) = match *self {
            Self::Impulse { ohms, .. } => (
                current - voltage / ohms + self.impulse_current(time_seconds).unwrap(),
                [-1.0 / ohms, 1.0 / ohms, 1.0],
                0.0,
                voltage * voltage / ohms,
            ),
            Self::AcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            } => {
                let angle =
                    (omega * pole_pairs * time_seconds).rem_euclid(std::f64::consts::TAU) + phase;
                (
                    voltage - k * omega * angle.sin(),
                    [1.0, -1.0, 0.0],
                    0.0,
                    0.0,
                )
            }
            Self::Bipolar(_)
            | Self::Mosfet(_)
            | Self::Windings(_)
            | Self::ThermalResistor(_)
            | Self::BatteryContact { .. }
            | Self::Converter { .. }
            | Self::Relay { .. }
            | Self::TappedWire(_)
            | Self::Arc(_)
            | Self::Regulator(_)
            | Self::Audio(_)
            | Self::Mixer { .. }
            | Self::RadioTransmitter { .. }
            | Self::FieldShaft { .. }
            | Self::FieldAcShaft { .. }
            | Self::Meter { .. } => {
                unreachable!("multiport evaluation handled above")
            }
            Self::Resistor { ohms } => (
                voltage - ohms * current,
                [1.0, -1.0, -ohms],
                0.0,
                ohms * current * current,
            ),
            Self::ShaftBoundary {
                newton_metres_per_ampere: k,
                radians_per_second: omega,
            } => {
                if !(k * current).is_finite() {
                    return Err(rejected("Non-finite shaft torque"));
                }
                (voltage - k * omega, [1.0, -1.0, 0.0], 0.0, 0.0)
            }
            Self::DcVoltageSource { volts } => (voltage - volts, [1.0, -1.0, 0.0], 0.0, 0.0),
            Self::SineVoltageSource {
                offset,
                peak,
                hertz,
                phase,
            } => {
                let cycles = hertz * time_seconds;
                if !cycles.is_finite() {
                    return Err(rejected("Non-finite source phase"));
                }
                let angle = std::f64::consts::TAU * cycles.fract()
                    + phase.rem_euclid(std::f64::consts::TAU);
                (
                    voltage - offset - peak * angle.sin(),
                    [1.0, -1.0, 0.0],
                    0.0,
                    0.0,
                )
            }
            Self::Diode {
                saturation_amperes,
                thermal_volts,
                junction_farads,
            } => {
                let scaled = voltage / thermal_volts;
                let (excess, exponential) = super::junction_exponent(scaled);
                let conduction = saturation_amperes * excess;
                let conductance = saturation_amperes * exponential / thermal_volts;
                if !conduction.is_finite() || !conductance.is_finite() {
                    return Err(rejected("Diode exponential overflow"));
                }
                charge[2] = -junction_farads * voltage;
                charge_jacobian[6] = -junction_farads;
                charge_jacobian[7] = junction_farads;
                (
                    current - conduction,
                    [-conductance, conductance, 1.0],
                    0.5 * junction_farads * voltage * voltage,
                    voltage * conduction,
                )
            }
            Self::Capacitor { farads } => {
                charge[2] = -farads * voltage;
                charge_jacobian[6] = -farads;
                charge_jacobian[7] = farads;
                (
                    current,
                    [0.0, 0.0, 1.0],
                    0.5 * farads * voltage * voltage,
                    0.0,
                )
            }
            Self::Inductor { henries } => {
                charge[2] = -henries * current;
                charge_jacobian[8] = -henries;
                (
                    voltage,
                    [1.0, -1.0, 0.0],
                    0.5 * henries * current * current,
                    0.0,
                )
            }
        };
        let residual = vec![current, -current, constraint];
        if residual.iter().chain(&charge).any(|v| !v.is_finite())
            || !dissipated_watts.is_finite()
            || !stored_joules.is_finite()
        {
            return Err(rejected("Non-finite constitutive evaluation"));
        }
        Ok(Evaluation {
            residual_units: vec![
                ResidualUnit::Ampere,
                ResidualUnit::Ampere,
                if self.constraint_is_current() {
                    ResidualUnit::Ampere
                } else {
                    ResidualUnit::Volt
                },
            ],
            charge,
            residual,
            charge_jacobian,
            residual_jacobian: vec![
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                -1.0,
                derivatives[0],
                derivatives[1],
                derivatives[2],
            ],
            stored_joules,
            dissipated_watts,
        })
    }
}
