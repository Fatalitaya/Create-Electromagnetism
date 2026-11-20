use super::*;
use crate::model::{DeviceModel, element::rejected};

pub(super) struct Response {
    equilibrium: dc::DcOperatingPoint,
    tau_seconds: f64,
}

impl Response {
    pub(super) fn same_response(&self, other: &Self, ports: &[PortId], other_ports: &[PortId]) -> bool {
        self.tau_seconds == other.tau_seconds
            && self.equilibrium.branches.iter().zip(&other.equilibrium.branches).all(|(a, b)| {
                a.local_values == b.local_values && a.voltage == b.voltage && a.current == b.current
            })
            && ports.iter().zip(other_ports).all(|(p, q)|
                self.equilibrium.voltages[p] == other.equilibrium.voltages[q])
    }

    pub(super) async fn compile(
        branches: &[DcBranch],
        layout: &Layout,
    ) -> Result<Option<Self>, SolveError> {
        let mut capacitor = None;
        for (i, branch) in branches.iter().enumerate() {
            match branch.element {
                Element::Resistor { .. } | Element::DcVoltageSource { .. } => {}
                Element::Capacitor { farads } if capacitor.is_none() => {
                    capacitor = Some((i, farads))
                }
                _ => return Ok(None),
            }
        }
        let Some((index, farads)) = capacitor else {
            return Ok(None);
        };
        // Suppress independent sources and apply 1 V at the capacitor. The resulting delivered
        // current gives driving-point conductance in siemens.
        let solve = async |test_voltage: bool| {
            dc::solve_compiled_async(branches, 0.0, layout, |branch| {
                let element = match (&branch.element, test_voltage) {
                    (Element::DcVoltageSource { .. }, true) => {
                        &Element::DcVoltageSource { volts: 0.0 }
                    }
                    (Element::Capacitor { .. }, true) => &Element::DcVoltageSource { volts: 1.0 },
                    (model, _) => model,
                };
                Ok(element.evaluate(&[0.0; 3], 0.0)?)
            })
            .await
        };
        // Use the constrained transient solver for floating DC operating points and ideal voltage
        // clamps.
        let Ok(test) = solve(true).await else {
            return Ok(None);
        };
        let tau_seconds = -farads / test.branches[index].current;
        if !tau_seconds.is_finite() || tau_seconds <= 0.0 {
            return Ok(None);
        }
        let Ok(equilibrium) = solve(false).await else {
            return Ok(None);
        };
        Ok(Some(Self {
            equilibrium,
            tau_seconds,
        }))
    }

    pub(super) async fn advance(
        &self,
        circuit: TransientCircuit,
        end: f64,
    ) -> Result<AcceptedState, SolveError> {
        let mut state = circuit.accepted;
        let seconds = end - state.time_seconds;
        if !seconds.is_finite() || seconds <= 0.0 {
            return Err(rejected("Invalid RC interval").into());
        }
        let x = seconds / self.tau_seconds;
        if !x.is_finite() {
            return Err(rejected("RC interval exceeds time constant range").into());
        }
        let advance = |initial: f64, steady: f64| {
            if x < 0.5 {
                initial + (steady - initial) * -(-x).exp_m1()
            } else {
                steady + (initial - steady) * (-x).exp()
            }
        };
        state.time_seconds = end;
        let point = &mut state.operating_point;
        for (port, voltage) in &mut point.voltages {
            *voltage = advance(*voltage, self.equilibrium.voltages[port]);
        }
        point.power_balance_watts = 0.0;
        state.energy.stored_joules = 0.0;
        let (initial_weight, variance) = shape(x);
        for (i, p) in point.branches.iter_mut().enumerate() {
            checkpoint().await;
            let before = (p.voltage, p.current);
            let steady = &self.equilibrium.branches[i];
            for (value, &limit) in p.local_values.iter_mut().zip(&steady.local_values) {
                *value = advance(*value, limit);
            }
            p.voltage = p.local_values[0] - p.local_values[1];
            p.current = p.local_values[2];
            p.observed_watts = p.voltage * p.current;
            p.absorbed_watts = p.observed_watts;
            point.power_balance_watts += p.absorbed_watts;
            let model = &circuit.branches[i].element;
            let evaluation = model.evaluate(&p.local_values, end)?;
            p.dissipated_watts = evaluation.dissipated_watts;
            p.stored_joules = evaluation.stored_joules;
            state.energy.stored_joules += p.stored_joules;
            let dv = p.voltage - before.0;
            let di = p.current - before.1;
            let v = p.voltage - initial_weight * dv;
            let current = p.current - initial_weight * di;
            let net = seconds * (v * current + variance * dv * di);
            let mut absorbed = net.max(0.0);
            let mut returned = (-net).max(0.0);
            // During RC relaxation the source voltage is constant, capacitor current keeps its
            // direction, and the resistor absorbs power.
            let crossing = match model {
                Element::DcVoltageSource { .. } => Some((before.1, p.current, steady.current)),
                Element::Capacitor { .. } => Some((before.0, p.voltage, steady.voltage)),
                _ => None,
            };
            if let Some((start, finish, limit)) = crossing
                && ((start < 0.0 && finish > 0.0) || (start > 0.0 && finish < 0.0))
            {
                let ratio = (start / limit).abs();
                let crossing_x = if ratio.is_finite() {
                    ratio.ln_1p()
                } else {
                    start.abs().ln() - limit.abs().ln()
                };
                let fraction = -start / (finish - start);
                let (initial_weight, variance) = shape(crossing_x);
                let mean_change = (1.0 - initial_weight) * fraction;
                let first = self.tau_seconds
                    * crossing_x
                    * ((before.0 + mean_change * dv) * (before.1 + mean_change * di)
                        + variance * fraction * dv * fraction * di);
                if !first.is_finite() {
                    return Err(rejected("Non-finite RC energy at power reversal").into());
                }
                let last = net - first;
                absorbed = first.max(0.0) + last.max(0.0);
                returned = (-first).max(0.0) + (-last).max(0.0);
            }
            let heat = if matches!(model, Element::Resistor { .. }) {
                absorbed
            } else {
                0.0
            };
            state.energy.dissipated_joules += heat;
            if matches!(model, Element::DcVoltageSource { .. }) {
                state.energy.source_absorbed_joules += absorbed;
                state.energy.source_supplied_joules += returned;
            }
            let m = &mut state.measurements.branches[i];
            m.periodicity.integrate(before.0, p.voltage, seconds)?;
            m.voltage_seconds += seconds * v;
            m.current_seconds += seconds * current;
            m.voltage_squared_seconds += seconds * (v * v + variance * dv * dv);
            m.current_squared_seconds += seconds * (current * current + variance * di * di);
            m.peak_voltage = m.peak_voltage.max(before.0.abs()).max(p.voltage.abs());
            m.peak_current = m.peak_current.max(before.1.abs()).max(p.current.abs());
            m.absorbed_joules += absorbed;
            m.returned_joules += returned;
            m.dissipated_joules += heat;
            m.total_absorbed_joules += absorbed;
            m.total_returned_joules += returned;
            m.total_dissipated_joules += heat;
            if !net.is_finite() || !m.fields().iter().all(|v| v.is_finite()) {
                return Err(rejected("Non-finite analytic RC observation").into());
            }
        }
        Ok(state)
    }
}

// Compute the interval mean and variance of the initial endpoint weight. Near zero, use Taylor
// forms to avoid subtracting two O(1/x²) terms.
fn shape(x: f64) -> (f64, f64) {
    if x < 0.01 {
        let x2 = x * x;
        (
            0.5 - x * (1.0 / 12.0 - x2 / 720.0),
            1.0 / 12.0 - x2 / 720.0 + x2 * x2 / 30240.0,
        )
    } else {
        let inverse = 1.0 / x;
        let change = -(-x).exp_m1();
        (
            inverse - (-x).exp() / change,
            inverse * ((1.0 + (-x).exp()) / (2.0 * change) - inverse),
        )
    }
}
