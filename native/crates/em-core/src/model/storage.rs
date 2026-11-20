//! Transfer charge, flux and temperature under the device constraints when editing a circuit.
use super::{
    Evaluation, ResidualUnit,
    element::{Element, rejected},
};
use crate::state::InitialStorage;
use em_protocol::contract::Diagnostic;

impl Element {
    pub(crate) fn constrain_storage(
        &self,
        initial: Option<&InitialStorage>,
        values: &[f64],
        e: &mut Evaluation,
        open: [bool;2],
    ) -> Result<(), Diagnostic> {
        match *self {
            Self::Regulator(_) => {
                let Some(InitialStorage::DetectorVoltageSquared(value)) = initial else {
                    return Err(rejected("Missing regulator detector state"));
                };
                if !value.is_finite() || *value < 0.0 {
                    return Err(rejected("Invalid regulator detector state"));
                }
                let row = self.detector_row().expect("regulator detector row");
                e.residual[row] = values[row] - value;
                let n = values.len();
                e.residual_jacobian[row * n..(row + 1) * n].fill(0.0);
                e.residual_jacobian[row * n + row] = 1.0;
            }
            Self::Rectifier(_) | Self::ThermalResistor(_) | Self::TappedWire(_) | Self::Arc(_) => {
                let (model, thermal_row) = self.thermal_node().unwrap();
                let theta = match initial {
                    Some(InitialStorage::TemperatureKelvin(t)) => *t - model.ambient_kelvin,
                    Some(InitialStorage::TemperatureRiseKelvin(t)) => *t,
                    _ => return Err(rejected("Missing thermal node temperature")),
                };
                if !theta.is_finite() || theta < 0.0 {
                    return Err(rejected("Initial temperature below fixed ambient"));
                }
                e.residual[thermal_row] = values[thermal_row] - theta;
                e.residual_units[thermal_row] = ResidualUnit::Kelvin;
                let n = values.len();
                e.residual_jacobian[thermal_row * n..(thermal_row + 1) * n].fill(0.0);
                e.residual_jacobian[thermal_row * n + thermal_row] = 1.0;
            }
            Self::Windings(model) => {
                let Some(InitialStorage::WindingFluxes { primary, secondary }) = initial else {
                    return Err(rejected("Missing coupled winding fluxes"));
                };
                let (ip, is) = model.currents(*primary, *secondary);
                // KCL forces current in a cut winding to zero. Its induced voltage is mutual
                // inductance times the closed winding's current derivative.
                for (index,row,current,other,ratio) in [
                    (0,2,ip,4,model.mutual_henries/model.secondary_henries),
                    (1,4,is,2,model.mutual_henries/model.primary_henries),
                ] {
                    if open[index] {
                        if current.abs()>1e-12 {
                            return Err(rejected("Nonzero initial current in an open coupled winding"));
                        }
                        if !open[1-index] {
                            let base=model.evaluate(values)?;
                            e.residual[row]=base.residual[row]-ratio*base.residual[other];
                            for col in 0..7 {
                                e.residual_jacobian[row*7+col]=base.residual_jacobian[row*7+col]-ratio*base.residual_jacobian[other*7+col];
                            }
                        }
                    } else {
                        e.residual[row]=values[row]-current;
                        e.residual_units[row]=ResidualUnit::Ampere;
                        e.residual_jacobian[row*7..row*7+7].fill(0.0);
                        e.residual_jacobian[row*7+row]=1.0;
                    }
                }
            }
            Self::Bipolar(_) | Self::Mosfet(_) => {
                let (return_cap, output_cap) = self.control_capacitances().unwrap();
                let Some(InitialStorage::PortCharges {
                    control_return,
                    control_output,
                }) = initial
                else {
                    return Err(rejected("Missing control port charges"));
                };
                e.residual[2] = values[3] - values[0] - control_output / output_cap;
                e.residual[4] = values[3] - values[1] - control_return / return_cap;
                e.residual_units[2] = ResidualUnit::Volt;
                e.residual_units[4] = ResidualUnit::Volt;
                e.residual_jacobian[10..15].fill(0.0);
                e.residual_jacobian[20..25].fill(0.0);
                e.residual_jacobian[10] = -1.0;
                e.residual_jacobian[13] = 1.0;
                e.residual_jacobian[21] = -1.0;
                e.residual_jacobian[23] = 1.0;
            }
            Self::Capacitor { farads } => {
                let Some(InitialStorage::ChargeCoulombs(q)) = initial else {
                    return Err(rejected("Missing capacitor charge"));
                };
                e.residual[2] = values[0] - values[1] - q / farads;
                e.residual_units[2] = ResidualUnit::Volt;
                e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
            }
            Self::Inductor { henries } => {
                let Some(InitialStorage::FluxWebers(flux)) = initial else {
                    return Err(rejected("Missing inductor flux"));
                };
                if open[0] {
                    if *flux != 0.0 {
                        return Err(rejected("Nonzero initial flux in an open inductor"));
                    }
                    e.residual[2] = values[0] - values[1];
                    e.residual_units[2] = ResidualUnit::Volt;
                    e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
                } else {
                    e.residual[2] = values[2] - flux / henries;
                    e.residual_units[2] = ResidualUnit::Ampere;
                    e.residual_jacobian[6..9].copy_from_slice(&[0.0, 0.0, 1.0]);
                }
            }
            Self::Diode {
                junction_farads, ..
            } if junction_farads > 0.0 => {
                let Some(InitialStorage::ChargeCoulombs(q)) = initial else {
                    return Err(rejected("Missing diode junction charge"));
                };
                e.residual[2] = values[0] - values[1] - q / junction_farads;
                e.residual_units[2] = ResidualUnit::Volt;
                e.residual_jacobian[6..9].copy_from_slice(&[1.0, -1.0, 0.0]);
            }
            _ => {}
        }
        Ok(())
    }

    /// Transfer storage invariants by branch identity before solving the new boundary. Return the
    /// new model's energy at those invariants.
    pub(crate) fn transfer_storage(
        &self,
        previous: Option<(&Self, &[f64])>,
        allow_parameter_work: bool,
        charge_override: Option<f64>,
    ) -> Result<Option<(InitialStorage, f64, bool)>, Diagnostic> {
        use InitialStorage::*;
        let require_work = |changed| {
            if changed && !allow_parameter_work {
                Err(rejected("Topology cannot change storage parameters"))
            } else {
                Ok(())
            }
        };

        if self.detector_row().is_some() {
            let value = if let Some((old, x)) = previous {
                let row = old
                    .detector_row()
                    .ok_or_else(|| rejected("Retained regulator changed model"))?;
                x[row]
            } else {
                0.0
            };
            return Ok(Some((DetectorVoltageSquared(value), 0.0, false)));
        }
        if let Some((model, _)) = self.thermal_node() {
            let (theta, changed) = if let Some((old, x)) = previous {
                let (old, row) = old
                    .thermal_node()
                    .ok_or_else(|| rejected("Retained thermal branch changed model"))?;
                if old.ambient_kelvin != model.ambient_kelvin {
                    return Err(rejected(
                        "Fixed ambient bath cannot be changed on a retained thermal node",
                    ));
                }
                (
                    x[row],
                    old.heat_capacity_joules_per_kelvin != model.heat_capacity_joules_per_kelvin,
                )
            } else {
                (0.0, false)
            };
            require_work(changed)?;
            return Ok(Some((
                TemperatureRiseKelvin(theta),
                model.heat_capacity_joules_per_kelvin * theta,
                changed,
            )));
        }
        if let Self::Windings(model) = self {
            let ((primary, secondary), changed) = if let Some((old, x)) = previous {
                let Self::Windings(old) = old else {
                    return Err(rejected("Retained winding changed model"));
                };
                let changed = old.primary_henries != model.primary_henries
                    || old.secondary_henries != model.secondary_henries
                    || old.mutual_henries != model.mutual_henries;
                // A tap change preserves instantaneous winding currents. Recompute flux linkages
                // at the new turns ratio and book the energy difference as switching work.
                let retapped = model.tap.is_some_and(|tap| match old.tap {
                    Some(previous) => previous.secondary_turns_per_primary_turn != tap.secondary_turns_per_primary_turn,
                    // Restore a retained winding whose snapshot lacks tap data.
                    None => old.mutual_henries != model.mutual_henries,
                });
                let linkage_model = if retapped { model } else { old };
                (linkage_model.fluxes(x[2], x[4]), changed)
            } else {
                ((0.0, 0.0), false)
            };
            require_work(changed)?;
            let (ip, is) = model.currents(primary, secondary);
            return Ok(Some((
                WindingFluxes { primary, secondary },
                model.energy(ip, is),
                changed,
            )));
        }
        if let Some((return_cap, output_cap)) = self.control_capacitances() {
            let (control_return, control_output, changed) = if let Some((old, x)) = previous {
                if !old.same_kind(self) {
                    return Err(rejected("Retained branch changed model or polarity"));
                }
                let (old_return, old_output) =
                    old.control_capacitances().expect("same control model");
                (
                    old_return * (x[3] - x[1]),
                    old_output * (x[3] - x[0]),
                    old_return != return_cap || old_output != output_cap,
                )
            } else {
                (0.0, 0.0, false)
            };
            require_work(changed)?;
            let joules = 0.5 * control_return * (control_return / return_cap)
                + 0.5 * control_output * (control_output / output_cap);
            return Ok(Some((
                PortCharges {
                    control_return,
                    control_output,
                },
                joules,
                changed,
            )));
        }
        let (charge, flux, changed) = if let Some((old, x)) = previous {
            if !old.same_kind(self) && !matches!((old, self), (Self::Diode { junction_farads, .. }, Self::Capacitor { farads }) if junction_farads == farads) {
                return Err(rejected(
                    "Retained branch changed model or storage parameters",
                ));
            }
            let changed = old.capacitance() != self.capacitance()
                || matches!((old, self), (Self::Inductor { henries: a }, Self::Inductor { henries: b }) if a != b);
            let flux = if let Self::Inductor { henries } = old {
                henries * x[2]
            } else {
                0.0
            };
            (
                old.capacitance().unwrap_or(0.0) * (x[0] - x[1]),
                flux,
                changed,
            )
        } else {
            (0.0, 0.0, false)
        };
        require_work(changed)?;
        let charge = charge_override.unwrap_or(charge);
        if let Some(capacitance) = self.capacitance() {
            Ok(Some((
                ChargeCoulombs(charge),
                0.5 * charge * (charge / capacitance),
                changed || charge_override.is_some(),
            )))
        } else if let Self::Inductor { henries } = self {
            Ok(Some((
                FluxWebers(flux),
                0.5 * flux * (flux / henries),
                changed,
            )))
        } else if charge != 0.0 {
            Err(rejected(
                "Cannot reduce a charged junction capacitance to zero",
            ))
        } else {
            Ok(None)
        }
    }
}
