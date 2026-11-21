//! All resistive segments share the conductor's thermal state. Moving a collector changes its
//! electrical connections.
use super::{Evaluation, ResidualUnit, element::rejected, thermal::ThermalResistor};
use em_protocol::contract::Diagnostic;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TappedWire {
    pub thermal: ThermalResistor,
    pub ports: usize,
    /// Ports 0 and 1 are the ends. Additional ports use fractional distance along the conductor.
    pub positions: [f64; 32],
}
#[derive(Clone, Copy)]
struct Segments {
    order: [usize; 32],
    ohms: [f64; 31],
    siemens: [f64; 31],
}

fn voltage_index(port: usize) -> usize {
    if port < 2 { port } else { 2 * port - 1 }
}

impl TappedWire {
    pub fn parameters(self) -> Vec<f64> {
        let mut p = self.thermal.parameters();
        // A zero trip setting disables conductor destruction.
        if self.thermal.trip_kelvin.is_none() {
            p.push(0.0);
        }
        p.push(self.ports as f64);
        p.extend(self.positions);
        p
    }
    fn segments(self) -> Segments {
        let mut key = [0.0; 34];
        key[..32].copy_from_slice(&self.positions);
        key[32] = self.ports as f64;
        key[33] = self.thermal.ambient_ohms;
        super::catalog::coefficients!(key, {
            let mut order = std::array::from_fn(|i| i);
            order[..self.ports].sort_unstable_by(|&a,&b| self.positions[a].total_cmp(&self.positions[b]));
            let mut ohms = [0.0; 31];
            let mut siemens = [0.0; 31];
            for (j,pair) in order[..self.ports].windows(2).enumerate() {
                ohms[j] = self.thermal.ambient_ohms * (self.positions[pair[1]] - self.positions[pair[0]]);
                siemens[j] = ohms[j].recip();
            }
            Segments { order, ohms, siemens }
        }, 34, Segments)
    }

    pub(crate) fn fixed_heating_watts(self, x: &[f64]) -> f64 {
        let segments = self.segments();
        segments.order[..self.ports].windows(2).enumerate().map(|(i,p)| {
            let volts = x[voltage_index(p[0])] - x[voltage_index(p[1])];
            segments.siemens[i] * volts * volts
        }).sum()
    }
    pub fn validate(self) -> Result<(), Diagnostic> {
        self.thermal.validate()?;
        if !(2..=32).contains(&self.ports)
            || self.positions[0] != 0.0
            || self.positions[1] != 1.0
            || self.positions.iter().any(|v| !v.is_finite())
            || self.positions[self.ports..].iter().any(|v| *v != 0.0)
        {
            return Err(rejected("Invalid tapped conductor bounds"));
        }
        let order = self.segments().order;
        if order[..self.ports]
            .windows(2)
            .any(|p| self.positions[p[1]] <= self.positions[p[0]] || self.positions[p[0]] < 0.0 || self.positions[p[1]] > 1.0)
        {
            return Err(rejected(
                "Conductor taps must be distinct and lie inside the conductor",
            ));
        }
        Ok(())
    }
    pub fn evaluate(self, x: &[f64]) -> Result<Evaluation, Diagnostic> {
        self.validate()?;
        let n = self.ports * 2;
        let theta = n - 1;
        if x.len() != n || x.iter().any(|v| !v.is_finite()) {
            return Err(rejected("Invalid tapped conductor candidate"));
        }
        let multiplier = 1.0 + self.thermal.coefficient_per_kelvin * x[theta];
        if multiplier <= 0.0 {
            return Err(rejected("Nonpositive conductor resistance"));
        }
        let current = |port: usize| if port == 0 { 2 } else { 2 * port };
        let mut residual = vec![0.0; n];
        let mut jac = vec![0.0; n * n];
        let mut q = vec![0.0; n];
        let mut qjac = vec![0.0; n * n];
        let mut units = vec![ResidualUnit::Ampere; n];
        for port in 0..self.ports {
            if port == 1 {
                continue;
            }
            let row = voltage_index(port);
            let i = current(port);
            residual[row] = x[i];
            jac[row * n + i] = 1.0;
            residual[1] -= x[i];
            jac[n + i] = -1.0;
            residual[i] = x[i];
            jac[i * n + i] = 1.0;
        }
        let segments = self.segments();
        let mut power = 0.0;
        for (i,pair) in segments.order[..self.ports].windows(2).enumerate() {
            let (a, b) = (pair[0], pair[1]);
            let (va, vb) = (voltage_index(a), voltage_index(b));
            let g = if multiplier == 1.0 { segments.siemens[i] } else { 1.0 / (segments.ohms[i] * multiplier) };
            let u = x[va] - x[vb];
            let amps = g * u;
            let heat = amps * u;
            power += heat;
            for (port, sign) in [(a, 1.0), (b, -1.0)] {
                if port == 1 {
                    continue;
                }
                let row = current(port);
                residual[row] -= sign * amps;
                jac[row * n + va] -= sign * g;
                jac[row * n + vb] += sign * g;
                jac[row * n + theta] +=
                    sign * amps * self.thermal.coefficient_per_kelvin / multiplier;
            }
            jac[theta * n + va] -= 2.0 * amps;
            jac[theta * n + vb] += 2.0 * amps;
        }
        let cooling = self.thermal.conductance_watts_per_kelvin * x[theta];
        residual[theta] = cooling - power;
        jac[theta * n + theta] = self.thermal.conductance_watts_per_kelvin
            + power * self.thermal.coefficient_per_kelvin / multiplier;
        q[theta] = self.thermal.heat_capacity_joules_per_kelvin * x[theta];
        qjac[theta * n + theta] = self.thermal.heat_capacity_joules_per_kelvin;
        units[theta] = ResidualUnit::Watt;
        if residual
            .iter()
            .chain(&jac)
            .chain(&q)
            .any(|v| !v.is_finite())
        {
            return Err(rejected("Non-finite conductor candidate"));
        }
        Ok(Evaluation {
            residual_units: units,
            residual,
            residual_jacobian: jac,
            charge: q,
            charge_jacobian: qjac,
            stored_joules: self.thermal.heat_capacity_joules_per_kelvin * x[theta],
            dissipated_watts: cooling,
        })
    }
}
