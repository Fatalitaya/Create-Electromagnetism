use super::*;

impl Plan {
    // Advance seven eighths of the step by implicit midpoint. The final
    // backward Euler stage reconstructs a consistent algebraic endpoint, even
    // for series inductors whose individual voltages are underdetermined when
    // all currents are prescribed. The midpoint power is the exact discrete
    // work for quadratic magnetic/electric storage and linear heat storage.
    async fn midpoint_step(
        &self,
        c: &TransientCircuit,
        old: &AcceptedState,
        end: f64,
    ) -> Result<AcceptedState, SolveError> {
        let h = (end - old.time_seconds) * 0.875;
        let middle = old.time_seconds + h * 0.5;
        let mut mid = c.trial_from(old, middle).await?;
        let mut values = vec![0.; c.layout.size];
        for (i, (before, after)) in old
            .operating_point
            .branches
            .iter()
            .zip(&mid.operating_point.branches)
            .enumerate()
        {
            for (j, col) in c.layout.mappings[i].iter().enumerate() {
                if let Some(col) = col {
                    values[*col] = 2. * after.local_values[j] - before.local_values[j];
                }
            }
        }
        let stage_time = old.time_seconds + h;
        let predictor = dc::point(&c.branches, stage_time, &c.layout, &values).await?;
        mid.energy = old.energy;
        mid.energy.stored_joules = predictor.branches.iter().map(|b| b.stored_joules).sum();
        mid.measurements = old.measurements.clone();
        for (i, ((m, p), branch)) in mid
            .measurements
            .branches
            .iter_mut()
            .zip(&mid.operating_point.branches)
            .zip(c.branches.iter())
            .enumerate()
        {
            let mut periodicity = m.periodicity;
            periodicity.integrate(old.operating_point.branches[i].voltage, p.voltage, h * 0.5)?;
            periodicity.integrate(p.voltage, predictor.branches[i].voltage, h * 0.5)?;
            m.integrate(p, p, h)?;
            m.peak_voltage = m
                .peak_voltage
                .max(old.operating_point.branches[i].voltage.abs());
            m.peak_current = m
                .peak_current
                .max(old.operating_point.branches[i].current.abs());
            m.periodicity = periodicity;
            mid.energy.dissipated_joules += h * p.dissipated_watts;
            if let Element::AcShaft { omega, .. } = branch.element {
                let work = h * p.absorbed_watts;
                mid.energy.source_absorbed_joules += work.max(0.);
                mid.energy.source_supplied_joules += (-work).max(0.);
                m.torque_seconds += work / omega;
            }
        }
        mid.operating_point = predictor;
        mid.time_seconds = stage_time;
        mid.source_phases.insert(
            self.reference.clock,
            (old.source_phases[&self.reference.clock]
                + h * self.reference.omega * self.reference.direction)
                .rem_euclid(TAU),
        );
        c.trial_from(&mid, end).await
    }

    // A regulator can modulate its gain within a cycle. Compare two trajectories
    // over the requested observation window before accepting the finer one.
    pub(super) async fn checked_transient(
        &self,
        c: &TransientCircuit,
        end: f64,
    ) -> Result<Option<Trial>, SolveError> {
        let start = c.accepted.time_seconds;
        let h = end - start;
        let cycles = h * self.reference.omega / TAU;
        if !(cycles > 0. && cycles <= 1.) {
            return Ok(None);
        }
        let count = (cycles * 32.).ceil() as usize;
        let trajectory = async |steps: usize| -> Result<Option<AcceptedState>, SolveError> {
            let mut state = c.accepted.clone();
            for i in 1..=steps {
                let next = if i == steps {
                    end
                } else {
                    start + h * i as f64 / steps as f64
                };
                // Cooling can only lower temperature. The energy initially
                // in all electrical storage plus work from the sole shaft
                // bounds heating even between numerical observation points.
                let stored: f64 = c
                    .branches
                    .iter()
                    .zip(&state.operating_point.branches)
                    .filter(|(b, _)| b.element.thermal_node().is_none())
                    .map(|(_, p)| p.stored_joules)
                    .sum();
                for (branch, p) in c.branches.iter().zip(&state.operating_point.branches) {
                    if let Some((m, row)) = branch.element.thermal_node()
                        && let Some(trip) = m.trip_kelvin
                    {
                        let Some(power) = self.reference.shaft_power_bound else {
                            return Ok(None);
                        };
                        let heat = 2. * stored + (next - state.time_seconds) * power;
                        if heat
                            >= (trip - m.ambient_kelvin - p.local_values[row])
                                * m.heat_capacity_joules_per_kelvin
                        {
                            return Ok(None);
                        }
                    }
                }
                state = self.midpoint_step(c, &state, next).await?;
            }
            Ok(Some(state))
        };
        let Some(coarse) = trajectory(count).await? else {
            return Ok(None);
        };
        let Some(fine) = trajectory(2 * count).await? else {
            return Ok(None);
        };
        if self.trajectories_agree(c, &coarse, &fine, h) {
            return Ok(Some(Trial::single(fine, 0.)));
        }
        // All three trajectories start from the same accepted boundary and
        // perform the protection bound at each stage. Refine once before
        // returning the interval to the adaptive transient solver.
        let Some(finer) = trajectory(4 * count).await? else {
            return Ok(None);
        };
        Ok(self.trajectories_agree(c, &fine, &finer, h).then(|| Trial::single(finer, 0.)))
    }

    fn trajectories_agree(
        &self,
        c: &TransientCircuit,
        coarse: &AcceptedState,
        fine: &AcceptedState,
        h: f64,
    ) -> bool {
        let close =
            |a: f64, b: f64, floor: f64| (a - b).abs() <= floor + 0.006 * a.abs().max(b.abs());
        for (a, b) in coarse
            .measurements
            .branches
            .iter()
            .zip(&fine.measurements.branches)
        {
            let apparent = (b.voltage_squared_seconds * b.current_squared_seconds).sqrt();
            let work_delta =
                (a.absorbed_joules - a.returned_joules) - (b.absorbed_joules - b.returned_joules);
            if work_delta.abs() > 1e-9 + 0.01 * apparent
                || !close(
                    (a.voltage_squared_seconds / h).sqrt(),
                    (b.voltage_squared_seconds / h).sqrt(),
                    1e-3,
                )
                || !close(
                    (a.current_squared_seconds / h).sqrt(),
                    (b.current_squared_seconds / h).sqrt(),
                    1e-6,
                )
            {
                return false;
            }
        }
        // RMS alone cannot distinguish two trajectories that reach different
        // stored states at the tick boundary. Check the independent C/L and
        // winding states, detector and temperature before handing them back.
        for (i, branch) in c.branches.iter().enumerate() {
            let a = &coarse.operating_point.branches[i];
            let b = &fine.operating_point.branches[i];
            let moments = &fine.measurements.branches[i];
            match branch.element {
                Element::Capacitor { .. }
                    if (a.voltage - b.voltage).abs() > 1e-3 + 0.006 * moments.peak_voltage =>
                {
                    return false;
                }
                Element::Inductor { .. }
                    if (a.current - b.current).abs() > 1e-6 + 0.006 * moments.peak_current =>
                {
                    return false;
                }
                Element::Windings(model) => {
                    let (ap, as_) = model.fluxes(a.current, a.local_values[4]);
                    let (bp, bs) = model.fluxes(b.current, b.local_values[4]);
                    let primary = moments.peak_current;
                    let secondary = self.reference.waves[i].local[4].peak();
                    if (ap - bp).abs()
                        > 1e-9
                            + 0.006
                                * (model.primary_henries * primary
                                    + model.mutual_henries.abs() * secondary)
                        || (as_ - bs).abs()
                            > 1e-9
                                + 0.006
                                    * (model.secondary_henries * secondary
                                        + model.mutual_henries.abs() * primary)
                    {
                        return false;
                    }
                }
                Element::Regulator(_) => {
                    let row = branch.element.detector_row().unwrap();
                    if !close(a.local_values[row].sqrt(), b.local_values[row].sqrt(), 1e-3) {
                        return false;
                    }
                }
                _ => {}
            }
            if let Some((_, row)) = branch.element.thermal_node() {
                if !close(a.local_values[row], b.local_values[row], 1e-6) {
                    return false;
                }
            }
        }
        let delta_drift =
            fine.energy.balance_residual_joules() - c.accepted.energy.balance_residual_joules();
        let work = (fine.energy.source_supplied_joules - c.accepted.energy.source_supplied_joules)
            + (fine.energy.source_absorbed_joules - c.accepted.energy.source_absorbed_joules);
        delta_drift.is_finite() && delta_drift.abs() <= 1e-9 + 1e-8 * work
    }
}
