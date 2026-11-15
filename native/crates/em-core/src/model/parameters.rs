//! Model parameter encoding and validation at batch boundaries.
use super::element::{Element, rejected};
use em_protocol::contract::Diagnostic;
impl Element {
    pub fn parameters(&self) -> (u32, Vec<f64>) {
        match *self {
            Self::FieldAcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            } => (28, vec![k, omega, pole_pairs, phase]),
            Self::FieldShaft { k, omega } => (27, vec![k, omega]),
            Self::RadioTransmitter {
                hertz,
                phase,
                ref audio,
            } => {
                let mut p = vec![hertz, phase];
                p.extend(audio.parameters());
                (26, p)
            }
            Self::Mixer {
                hertz,
                phase,
                ratio,
            } => (25, vec![hertz, phase, ratio]),
            Self::Audio(ref model) => (24, model.parameters()),
            Self::Impulse {
                start,
                peak,
                rise,
                fall,
                ohms,
                continuing,
                duration,
            } => (
                23,
                vec![start, peak, rise, fall, ohms, continuing, duration],
            ),
            Self::Regulator(model) => (
                22,
                vec![model.target, model.seconds, model.minimum, model.maximum],
            ),
            Self::Arc(model) => (21, model.parameters()),
            Self::TappedWire(model) => (20, model.parameters()),
            Self::Relay {
                coil_ohms,
                closed_ohms,
                open_ohms,
                pickup_volts,
                dropout_volts,
                inverted,
                active,
            } => (
                19,
                vec![
                    coil_ohms,
                    closed_ohms,
                    open_ohms,
                    pickup_volts,
                    dropout_volts,
                    f64::from(u8::from(inverted)),
                    f64::from(u8::from(active)),
                ],
            ),
            Self::AcShaft {
                k,
                omega,
                pole_pairs,
                phase,
            } => (18, vec![k, omega, pole_pairs, phase]),
            Self::Converter {
                output_volts,
                knee_volts,
                efficiency,
            } => (17, vec![output_volts, knee_volts, efficiency]),
            Self::BatteryContact {
                ohms,
                full_volts,
                taper_volts,
            } => (16, vec![ohms, full_volts, taper_volts]),
            Self::ShaftBoundary {
                newton_metres_per_ampere: k,
                radians_per_second: omega,
            } => (15, vec![k, omega]),
            Self::ThermalResistor(model) => (
                if model.trip_kelvin.is_some() { 13 } else { 12 },
                model.parameters(),
            ),
            Self::Windings(model) => (11, model.parameters()),
            Self::Bipolar(model) => (if model.pnp { 8 } else { 7 }, model.parameters()),
            Self::Mosfet(model) => (if model.p_channel { 10 } else { 9 }, model.parameters()),
            Self::Resistor { ohms } => (1, vec![ohms]),
            Self::Meter { ohms } => (14, vec![ohms]),
            Self::DcVoltageSource { volts } => (2, vec![volts]),
            Self::Capacitor { farads } => (3, vec![farads]),
            Self::Inductor { henries } => (4, vec![henries]),
            Self::Diode {
                saturation_amperes,
                thermal_volts,
                junction_farads,
            } => (5, vec![saturation_amperes, thermal_volts, junction_farads]),
            Self::SineVoltageSource {
                offset,
                peak,
                hertz,
                phase,
            } => (6, vec![offset, peak, hertz, phase]),
        }
    }

    pub fn from_parameters(tag: u32, p: &[f64]) -> Result<Self, Diagnostic> {
        let element = match (tag, p) {
            (28, [k, omega, pole_pairs, phase]) => Self::FieldAcShaft {
                k: *k,
                omega: *omega,
                pole_pairs: *pole_pairs,
                phase: *phase,
            },
            (27, [k, omega]) => Self::FieldShaft {
                k: *k,
                omega: *omega,
            },
            (26, values) if values.len() == 806 => {
                let Self::Audio(audio) = Self::from_parameters(24, &values[2..])? else {
                    unreachable!()
                };
                Self::RadioTransmitter {
                    hertz: values[0],
                    phase: values[1],
                    audio,
                }
            }
            (25, [hertz, phase, ratio]) => Self::Mixer {
                hertz: *hertz,
                phase: *phase,
                ratio: *ratio,
            },
            (24, values) if values.len() == 804 => {
                let mut samples = [0.0; 800];
                samples.copy_from_slice(&values[4..]);
                Self::Audio(super::audio::Audio {
                    start: values[0],
                    step: values[1],
                    volts: values[2],
                    ohms: values[3],
                    samples: samples.into(),
                })
            }
            (23, [start, peak, rise, fall, ohms, continuing, duration]) => Self::Impulse {
                start: *start,
                peak: *peak,
                rise: *rise,
                fall: *fall,
                ohms: *ohms,
                continuing: *continuing,
                duration: *duration,
            },
            (22, [target, seconds, minimum, maximum]) => {
                Self::Regulator(super::regulator::Regulator {
                    target: *target,
                    seconds: *seconds,
                    minimum: *minimum,
                    maximum: *maximum,
                })
            }
            (
                21,
                [
                    closed,
                    contact_ohms,
                    leakage_siemens,
                    hot_siemens,
                    breakdown_volts,
                    ignition_rise,
                    capacity,
                    cooling,
                    ambient,
                ],
            ) if *closed == 0.0 || *closed == 1.0 => Self::Arc(super::arc::Arc {
                closed: *closed == 1.0,
                contact_ohms: *contact_ohms,
                leakage_siemens: *leakage_siemens,
                hot_siemens: *hot_siemens,
                breakdown_volts: *breakdown_volts,
                ignition_rise: *ignition_rise,
                capacity: *capacity,
                cooling: *cooling,
                ambient: *ambient,
            }),
            (20, values)
                if values.len() == 39
                    && values[6] >= 2.0
                    && values[6] <= 32.0
                    && values[6].fract() == 0.0 =>
            {
                let mut positions = [0.0; 32];
                positions.copy_from_slice(&values[7..]);
                Self::TappedWire(super::tapped::TappedWire {
                    ports: values[6] as usize,
                    positions,
                    thermal: super::thermal::ThermalResistor {
                        ambient_ohms: values[0],
                        coefficient_per_kelvin: values[1],
                        heat_capacity_joules_per_kelvin: values[2],
                        conductance_watts_per_kelvin: values[3],
                        ambient_kelvin: values[4],
                        trip_kelvin: Some(values[5]),
                    },
                })
            }
            (
                19,
                [
                    coil_ohms,
                    closed_ohms,
                    open_ohms,
                    pickup_volts,
                    dropout_volts,
                    inverted,
                    active,
                ],
            ) if (*inverted == 0.0 || *inverted == 1.0) && (*active == 0.0 || *active == 1.0) => {
                Self::Relay {
                    coil_ohms: *coil_ohms,
                    closed_ohms: *closed_ohms,
                    open_ohms: *open_ohms,
                    pickup_volts: *pickup_volts,
                    dropout_volts: *dropout_volts,
                    inverted: *inverted == 1.0,
                    active: *active == 1.0,
                }
            }
            (18, [k, omega, pole_pairs, phase]) => Self::AcShaft {
                k: *k,
                omega: *omega,
                pole_pairs: *pole_pairs,
                phase: *phase,
            },
            (17, [output_volts, knee_volts, efficiency]) => Self::Converter {
                output_volts: *output_volts,
                knee_volts: *knee_volts,
                efficiency: *efficiency,
            },
            (16, [ohms, full_volts, taper_volts]) => Self::BatteryContact {
                ohms: *ohms,
                full_volts: *full_volts,
                taper_volts: *taper_volts,
            },
            (15, [k, omega]) => Self::ShaftBoundary {
                newton_metres_per_ampere: *k,
                radians_per_second: *omega,
            },
            (13, [r, alpha, c, g, ambient, trip]) => {
                Self::ThermalResistor(super::thermal::ThermalResistor {
                    ambient_ohms: *r,
                    coefficient_per_kelvin: *alpha,
                    heat_capacity_joules_per_kelvin: *c,
                    conductance_watts_per_kelvin: *g,
                    ambient_kelvin: *ambient,
                    trip_kelvin: Some(*trip),
                })
            }
            (
                12,
                [
                    ambient_ohms,
                    coefficient_per_kelvin,
                    heat_capacity_joules_per_kelvin,
                    conductance_watts_per_kelvin,
                    ambient_kelvin,
                ],
            ) => Self::ThermalResistor(super::thermal::ThermalResistor {
                ambient_ohms: *ambient_ohms,
                coefficient_per_kelvin: *coefficient_per_kelvin,
                heat_capacity_joules_per_kelvin: *heat_capacity_joules_per_kelvin,
                conductance_watts_per_kelvin: *conductance_watts_per_kelvin,
                ambient_kelvin: *ambient_kelvin,
                trip_kelvin: None,
            }),
            (
                11,
                [
                    primary_henries,
                    secondary_henries,
                    mutual_henries,
                    primary_ohms,
                    secondary_ohms,
                ],
            ) => Self::Windings(super::windings::Windings {
                primary_henries: *primary_henries,
                secondary_henries: *secondary_henries,
                mutual_henries: *mutual_henries,
                primary_ohms: *primary_ohms,
                secondary_ohms: *secondary_ohms,
            }),
            (
                9 | 10,
                [
                    beta_amperes_per_volt_squared,
                    threshold_volts,
                    saturation_amperes,
                    thermal_volts,
                    gate_source_farads,
                    gate_drain_farads,
                ],
            ) => Self::Mosfet(super::mosfet::Mosfet {
                beta_amperes_per_volt_squared: *beta_amperes_per_volt_squared,
                threshold_volts: *threshold_volts,
                saturation_amperes: *saturation_amperes,
                thermal_volts: *thermal_volts,
                gate_source_farads: *gate_source_farads,
                gate_drain_farads: *gate_drain_farads,
                p_channel: tag == 10,
            }),
            (
                7 | 8,
                [
                    saturation_amperes,
                    thermal_volts,
                    forward_beta,
                    reverse_beta,
                    base_emitter_farads,
                    base_collector_farads,
                ],
            ) => Self::Bipolar(super::bipolar::Bipolar {
                saturation_amperes: *saturation_amperes,
                thermal_volts: *thermal_volts,
                forward_beta: *forward_beta,
                reverse_beta: *reverse_beta,
                base_emitter_farads: *base_emitter_farads,
                base_collector_farads: *base_collector_farads,
                pnp: tag == 8,
            }),
            (1, [ohms]) => Self::Resistor { ohms: *ohms },
            (14, [ohms]) => Self::Meter { ohms: *ohms },
            (2, [volts]) => Self::DcVoltageSource { volts: *volts },
            (3, [farads]) => Self::Capacitor { farads: *farads },
            (4, [henries]) => Self::Inductor { henries: *henries },
            (5, [saturation_amperes, thermal_volts, junction_farads]) => Self::Diode {
                saturation_amperes: *saturation_amperes,
                thermal_volts: *thermal_volts,
                junction_farads: *junction_farads,
            },
            (6, [offset, peak, hertz, phase]) => Self::SineVoltageSource {
                offset: *offset,
                peak: *peak,
                hertz: *hertz,
                phase: *phase,
            },
            _ => return Err(rejected("Unsupported model or parameter count")),
        };
        element.validate()?;
        Ok(element)
    }
}
