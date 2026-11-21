//! Evaluate linear series ports and recover the full candidate before accepting
//! the Newton residual and undamped correction in the original coordinates.
use super::*;
use crate::solver::{SolveError, cooperative::checkpoint};
use crate::topology::series::SeriesPorts;

pub(super) async fn solve(
    branches: &[DcBranch],
    layout: &Layout,
    ports: &SeriesPorts,
    step: Option<(&StepPlan, f64)>,
    x: &[f64],
    evaluate: impl Fn(&DcBranch, &[f64]) -> Result<Evaluation, SolveError>,
) -> Result<Option<Vec<f64>>, SolveError> {
    let Some(mut laws) = dc::PortLaws::compile(branches, ports, |b| evaluate(b, &[0.0; 3])).await?
    else {
        return Ok(None);
    };
    let mut compact: Vec<_> = ports.kept.iter().map(|&r| x[r]).collect();
    for path in &ports.paths {
        compact[ports.rows[path.current].unwrap()] *= path.segments[0].sign;
    }
    let compiled = step.and_then(|(p, h)| p.compact.as_deref().map(|p| (p, h)));
    let (values, correction) = iterate(
        branches,
        &ports.coordinates,
        &[],
        compiled,
        compact,
        |i, x| evaluate_port(ports, &laws, i, x, |j, local| evaluate(&branches[j], local)),
    )
    .await?;
    for path in &ports.paths {
        if let Some(k) = path.feedback {
            let i = path.segments[k].branch;
            let crate::model::element::Element::ThermalResistor(m) = branches[i].element else {
                unreachable!()
            };
            let theta = values[ports.rows[layout.mappings[i][3].unwrap()].unwrap()];
            if theta < 0.0 {
                return Ok(None);
            }
            laws.segments[i].0 =
                m.ambient_ohms + (m.ambient_ohms * m.coefficient_per_kelvin) * theta;
        }
    }
    let Some(mut full) = ports
        .recover(&values, &layout.mappings, |seg, amps| {
            let (ohms, volts) = laws.segments[seg.branch];
            ohms * amps + volts
        })
        .await
    else {
        return Ok(None);
    };
    laws.restore_shunts(ports, layout, &mut full);
    let mut valid = true;
    let mut full_correction = vec![0.0; layout.size];
    for (&r, &d) in ports.kept.iter().zip(&correction) {
        full_correction[r] = d;
    }
    ports.restore_sensors(&layout.mappings, &mut full_correction);
    for path in &ports.paths {
        let amps = correction[ports.rows[path.current].unwrap()];
        let mut dv = path.terminals[0].map_or(0.0, |r| full_correction[r]);
        for (k, seg) in path.segments.iter().enumerate() {
            checkpoint().await;
            let map = &layout.mappings[seg.branch];
            full_correction[map[2].unwrap()] = seg.sign * amps;
            dv -= laws.segments[seg.branch].0 * amps;
            if Some(k) == path.feedback {
                let crate::model::element::Element::ThermalResistor(m) =
                    branches[seg.branch].element
                else {
                    unreachable!()
                };
                dv -= seg.sign
                    * m.ambient_ohms
                    * m.coefficient_per_kelvin
                    * full[map[2].unwrap()]
                    * full_correction[map[3].unwrap()];
            }
            if k + 1 < path.segments.len() {
                let end = map[usize::from(seg.sign > 0.0)].unwrap();
                full_correction[end] = dv;
            }
            let local = layout.local(seg.branch, &full);
            let e = evaluate(&branches[seg.branch], &local)?;
            valid &= balanced_local(&e, &local, 2);
            if Some(k) == path.feedback {
                valid &= balanced_local(&e, &local, 3);
            }
        }
    }
    for &i in &ports.shunts {
        let map = &layout.mappings[i];
        let dv =
            map[0].map_or(0.0, |r| full_correction[r]) - map[1].map_or(0.0, |r| full_correction[r]);
        full_correction[map[2].unwrap()] = laws.segments[i].0 * dv;
        let local = layout.local(i, &full);
        valid &= balanced_local(&evaluate(&branches[i], &local)?, &local, 2);
    }
    for &i in &ports.thermal {
        let mut local = layout.local(i, &full);
        let cold = evaluate(&branches[i], &local)?;
        local[3] = -cold.residual[3] / cold.residual_jacobian[15];
        if !local[3].is_finite() || local[3] < 0.0 {
            valid = false;
            break;
        }
        let row = layout.mappings[i][3].unwrap();
        full[row] = local[3];
        let e = evaluate(&branches[i], &local)?;
        valid &= balanced_local(&e, &local, 3);
        let di = full_correction[layout.mappings[i][2].unwrap()];
        full_correction[row] = -e.residual_jacobian[14] * di / e.residual_jacobian[15];
    }
    valid &= correction_norm(layout, &full, &full_correction) <= 1.0;
    Ok(valid.then_some(full))
}

fn affine(residual: [f64; 3], jacobian: [f64; 9]) -> Evaluation {
    Evaluation {
        residual: residual.to_vec(),
        residual_jacobian: jacobian.to_vec(),
        residual_units: vec![
            ResidualUnit::Ampere,
            ResidualUnit::Ampere,
            ResidualUnit::Volt,
        ],
        charge: vec![0.0; 3],
        charge_jacobian: vec![0.0; 9],
        stored_joules: 0.0,
        dissipated_watts: 0.0,
    }
}

fn evaluate_port(
    ports: &SeriesPorts,
    laws: &dc::PortLaws,
    i: usize,
    values: &[f64],
    evaluate: impl Fn(usize, &[f64]) -> Result<Evaluation, SolveError>,
) -> Result<Evaluation, SolveError> {
    if let Some(j) = ports.path_for[i] {
        let (ohms, volts) = laws.ports[j];
        // A pure source uses the same KCL rows and voltage pivot as a resistor.
        let amps = values[2];
        if let Some(k) = ports.paths[j].feedback {
            let seg = &ports.paths[j].segments[k];
            let v = seg.sign * (values[0] - values[1] - volts - ohms * amps);
            let mut e = evaluate(seg.branch, &[v, 0.0, seg.sign * amps, values[3]])?;
            e.residual[0] = amps;
            e.residual[1] = -amps;
            e.residual[2] *= seg.sign;
            e.residual_jacobian[10] -= ohms;
            e.residual_jacobian[11] *= seg.sign;
            e.residual_jacobian[14] *= seg.sign;
            return Ok(e);
        }
        Ok(affine(
            [amps, -amps, values[0] - values[1] - volts - ohms * amps],
            [0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 1.0, -1.0, -ohms],
        ))
    } else if ports.coordinates.mappings[i][2].is_none() {
        let (g, offset) = laws.segments[i];
        let amps = g * (values[0] - values[1]) + offset;
        Ok(affine(
            [amps, -amps, 0.0],
            [g, -g, 0.0, -g, g, 0.0, 0.0, 0.0, 0.0],
        ))
    } else if let Some((output, input)) = ports.returns[i] {
        let mut local = [0.0; 7];
        let local = &mut local[..values.len()];
        local.copy_from_slice(values);
        if let Some(s) = ports.sensors[i] {
            local[4] *= s.sign;
        }
        local[output] = input.map_or(0.0, |j| -local[j]);
        let mut e = evaluate(i, local)?;
        ports.project(i, &mut e);
        Ok(e)
    } else {
        evaluate(i, values)
    }
}
