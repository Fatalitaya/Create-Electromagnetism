//! Observations over explicit accepted time windows, energy ledgers, and waveforms.
//! Invalid readings must remain distinct from valid zero measurements.

use crate::{model::element::rejected, solver::dc::BranchOperatingPoint};
use em_protocol::contract::{Diagnostic, Reading};

pub mod periodicity;
pub mod waveform;

/// Endpoint quadrature, shared with backward Euler's accepted energy ledger.
/// Peaks are maxima of accepted integration samples, not guaranteed continuous extrema.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct BranchMoments {
    pub torque_seconds: f64,
    pub voltage_seconds: f64,
    pub current_seconds: f64,
    pub voltage_squared_seconds: f64,
    pub current_squared_seconds: f64,
    pub peak_voltage: f64,
    pub peak_current: f64,
    pub absorbed_joules: f64,
    pub returned_joules: f64,
    pub dissipated_joules: f64,
    pub total_absorbed_joules: f64,
    pub total_returned_joules: f64,
    pub total_dissipated_joules: f64,
    pub periodicity: periodicity::Periodicity,
}

impl BranchMoments {
    pub fn fields(&self) -> [f64; 12] {
        [
            self.voltage_seconds,
            self.current_seconds,
            self.voltage_squared_seconds,
            self.current_squared_seconds,
            self.peak_voltage,
            self.peak_current,
            self.absorbed_joules,
            self.returned_joules,
            self.dissipated_joules,
            self.total_absorbed_joules,
            self.total_returned_joules,
            self.total_dissipated_joules,
        ]
    }

    pub fn from_fields(v: [f64; 12], duration: f64) -> Result<Self, Diagnostic> {
        if v.iter().any(|x| !x.is_finite())
            || v[2..].iter().any(|x| *x < 0.0)
            || !duration.is_finite()
            || duration < 0.0
            || (duration == 0.0 && v[..9].iter().any(|x| *x != 0.0))
            || (0..3).any(|i| v[6 + i] > v[9 + i])
        {
            return Err(rejected("Invalid observation moments"));
        }
        if duration > 0.0 {
            for (sum, square, peak) in [(v[0], v[2], v[4]), (v[1], v[3], v[5])] {
                let mean = sum / duration;
                let rms = (square / duration).sqrt();
                if !mean.is_finite()
                    || !rms.is_finite()
                    || mean.abs() > rms + 1e-10 * rms.max(1.0)
                    || rms > peak + 1e-10 * peak.max(1.0)
                {
                    return Err(rejected("Observation moments violate mean/RMS/peak bounds"));
                }
            }
        }
        Ok(Self {
            torque_seconds: 0.0,
            voltage_seconds: v[0],
            current_seconds: v[1],
            voltage_squared_seconds: v[2],
            current_squared_seconds: v[3],
            peak_voltage: v[4],
            peak_current: v[5],
            absorbed_joules: v[6],
            returned_joules: v[7],
            dissipated_joules: v[8],
            total_absorbed_joules: v[9],
            total_returned_joules: v[10],
            total_dissipated_joules: v[11],
            periodicity: periodicity::Periodicity::default(),
        })
    }

    fn reset_window(&mut self) {
        *self = Self {
            periodicity: self.periodicity,
            total_absorbed_joules: self.total_absorbed_joules,
            total_returned_joules: self.total_returned_joules,
            total_dissipated_joules: self.total_dissipated_joules,
            ..Self::default()
        };
    }

    pub(crate) fn integrate(
        &mut self,
        before: &BranchOperatingPoint,
        after: &BranchOperatingPoint,
        h: f64,
    ) -> Result<(), Diagnostic> {
        self.integrate_stage(before, after, h, h)
    }

    fn integrate_stage(&mut self, before: &BranchOperatingPoint, after: &BranchOperatingPoint,
        h: f64, physical_seconds: f64) -> Result<(), Diagnostic> {
        self.periodicity.integrate(before.voltage, after.voltage, physical_seconds)?;
        self.voltage_seconds += h * after.voltage;
        self.current_seconds += h * after.current;
        self.voltage_squared_seconds += h * after.voltage * after.voltage;
        self.current_squared_seconds += h * after.current * after.current;
        self.peak_voltage = self
            .peak_voltage
            .max(before.voltage.abs())
            .max(after.voltage.abs());
        self.peak_current = self
            .peak_current
            .max(before.current.abs())
            .max(after.current.abs());
        let absorbed = h * after.observed_watts.max(0.0);
        let returned = h * (-after.observed_watts).max(0.0);
        let dissipated = h * after.dissipated_watts;
        self.absorbed_joules += absorbed;
        self.returned_joules += returned;
        self.dissipated_joules += dissipated;
        self.total_absorbed_joules += absorbed;
        self.total_returned_joules += returned;
        self.total_dissipated_joules += dissipated;
        if self.fields().iter().any(|v| !v.is_finite()) {
            return Err(rejected("Non-finite observation candidate"));
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Measurements {
    pub window_start: f64,
    /// Same stable branch order as the accepted operating point, never matrix rows.
    pub branches: Vec<BranchMoments>,
}

impl Measurements {
    pub fn new(time: f64, count: usize) -> Self {
        Self {
            window_start: time,
            branches: vec![BranchMoments::default(); count],
        }
    }

    pub fn reset_window(&mut self, time: f64) {
        self.window_start = time;
        self.branches
            .iter_mut()
            .for_each(BranchMoments::reset_window);
    }

    /// Join adjacent windows on the same trajectory. Lifetime totals and
    /// frequency history already include the prefix through the trial seed.
    pub(crate) fn prepend_window(&mut self, prefix: &Self) -> Result<(), Diagnostic> {
        self.window_start = prefix.window_start;
        for (next, old) in self.branches.iter_mut().zip(&prefix.branches) {
            next.torque_seconds += old.torque_seconds;
            next.voltage_seconds += old.voltage_seconds;
            next.current_seconds += old.current_seconds;
            next.voltage_squared_seconds += old.voltage_squared_seconds;
            next.current_squared_seconds += old.current_squared_seconds;
            next.peak_voltage = next.peak_voltage.max(old.peak_voltage);
            next.peak_current = next.peak_current.max(old.peak_current);
            next.absorbed_joules += old.absorbed_joules;
            next.returned_joules += old.returned_joules;
            next.dissipated_joules += old.dissipated_joules;
            // Different addition orders can round the window above its
            // lifetime total when both represent the same physical energy.
            next.total_absorbed_joules = next.total_absorbed_joules.max(next.absorbed_joules);
            next.total_returned_joules = next.total_returned_joules.max(next.returned_joules);
            next.total_dissipated_joules = next.total_dissipated_joules.max(next.dissipated_joules);
            if !next.torque_seconds.is_finite() || next.fields().iter().any(|v| !v.is_finite()) {
                return Err(rejected("Non-finite merged observation window"));
            }
        }
        Ok(())
    }

    pub(crate) fn integrate_stage(
        &mut self,
        before: &[BranchOperatingPoint],
        after: &[BranchOperatingPoint],
        h: f64,
        physical_seconds: f64,
    ) -> Result<(), Diagnostic> {
        if self.branches.len() != before.len()
            || before.len() != after.len()
            || !h.is_finite()
            || h <= 0.0
        {
            return Err(rejected("Observation topology or interval mismatch"));
        }
        for ((moments, before), after) in self.branches.iter_mut().zip(before).zip(after) {
            if before.id != after.id {
                return Err(rejected(
                    "Observation branch identity changed inside an interval",
                ));
            }
            moments.integrate_stage(before, after, h, physical_seconds)?;
        }
        Ok(())
    }

    pub fn readings(
        &self,
        end: f64,
        points: &[BranchOperatingPoint],
    ) -> Result<Vec<Reading>, Diagnostic> {
        let duration = end - self.window_start;
        if duration <= 0.0 || self.branches.len() != points.len() {
            return Err(rejected("No accepted measurement window"));
        }
        self.branches
            .iter()
            .zip(points)
            .map(|(m, p)| {
                BranchMoments::from_fields(m.fields(), duration)?;
                Ok(Reading {
                    branch: p.id,
                    window_start: self.window_start,
                    window_end: end,
                    end_voltage: p.voltage,
                    end_current: p.current,
                    mean_voltage: m.voltage_seconds / duration,
                    rms_voltage: (m.voltage_squared_seconds / duration).sqrt(),
                    peak_voltage: m.peak_voltage,
                    mean_current: m.current_seconds / duration,
                    rms_current: (m.current_squared_seconds / duration).sqrt(),
                    peak_current: m.peak_current,
                    active_power: (m.absorbed_joules - m.returned_joules) / duration,
                    absorbed_energy: m.absorbed_joules,
                    returned_energy: m.returned_joules,
                    dissipated_energy: m.dissipated_joules,
                    frequency: m.periodicity.reading(duration).map(|r| r.0),
                    phase: m.periodicity.reading(duration).map(|r| r.1),
                    temperature_kelvin: None,
                    torque_newton_metres: None,
                    total_absorbed_energy: m.total_absorbed_joules,
                    total_returned_energy: m.total_returned_joules,
                    total_dissipated_energy: m.total_dissipated_joules,
                })
            })
            .collect()
    }
}

/// Electrical-only ledger, SI joules. Heat leaves this boundary as dissipation.
/// Backward Euler endpoint quadrature is first order; numerical damping is NOT heat.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct EnergyLedger {
    pub initial_stored_joules: f64,
    pub stored_joules: f64,
    /// Energy from all prescribed power boundaries, including shaft work.
    /// The boundary branch identity and its lifetime moments retain its physical kind.
    pub source_supplied_joules: f64,
    pub source_absorbed_joules: f64,
    pub dissipated_joules: f64,
    pub numerical_damping_joules: f64,
    /// Signed work supplied by an accepted parameter edit at fixed charge/flux.
    pub parameter_work_joules: f64,
    /// Stored energy exported by an explicitly requested removal boundary, not heat.
    pub removed_joules: f64,
}

impl EnergyLedger {
    pub fn balance_residual_joules(&self) -> f64 {
        self.source_supplied_joules - self.source_absorbed_joules + self.parameter_work_joules
            - (self.stored_joules - self.initial_stored_joules)
            - self.dissipated_joules
            - self.numerical_damping_joules
            - self.removed_joules
    }

    pub(crate) fn is_finite(&self) -> bool {
        [
            self.initial_stored_joules,
            self.stored_joules,
            self.source_supplied_joules,
            self.source_absorbed_joules,
            self.dissipated_joules,
            self.numerical_damping_joules,
            self.parameter_work_joules,
            self.removed_joules,
            self.balance_residual_joules(),
        ]
        .iter()
        .all(|v| v.is_finite())
    }
}
