use super::{
    AcceptedState, Accuracy, DcBranch, Element, EnergyLedger, Measurements, TransientCircuit,
    Trial, dc,
};
use crate::solver::{SolveError, cooperative::checkpoint};
use crate::topology::Layout;
use std::{collections::BTreeMap, sync::Arc};

mod rc;

pub(crate) struct Plans(Vec<Island>);

struct Island {
    indices: Vec<usize>,
    branches: Arc<Vec<DcBranch>>,
    layout: Arc<Layout>,
    rc: Option<rc::Response>,
}

impl Plans {
    async fn compile(circuit: &TransientCircuit) -> Result<Self, SolveError> {
        // These models have no protection transitions. Others need the joint
        // intermediate trajectory for protection localization.
        if !circuit.branches.iter().all(|b| {
            matches!(
                b.element,
                Element::Resistor { .. }
                    | Element::DcVoltageSource { .. }
                    | Element::Capacitor { .. }
                    | Element::Inductor { .. }
                    | Element::Windings(_)
                    | Element::SineVoltageSource { .. }
                    | Element::Diode { .. }
            )
        }) {
            return Ok(Self(Vec::new()));
        }
        // Electrical components define voltage gauges. A constitutive model
        // can couple several gauges (notably primary and secondary windings).
        fn root(parents: &mut [usize], mut n: usize) -> usize {
            while parents[n] != n {
                parents[n] = parents[parents[n]];
                n = parents[n];
            }
            n
        }
        let components = &circuit.layout.components;
        let mut parents: Vec<_> = (0..circuit.layout.references.len()).collect();
        for b in circuit.branches.iter() {
            checkpoint().await;
            let a = root(&mut parents, components[&b.positive]);
            for port in b.ports() {
                let other = root(&mut parents, components[&port]);
                parents[other] = a;
            }
        }
        let mut groups = BTreeMap::<usize, Vec<usize>>::new();
        for (i, b) in circuit.branches.iter().enumerate() {
            let island = root(&mut parents, components[&b.positive]);
            groups.entry(island).or_default().push(i);
        }
        let single = groups.len() == 1;
        let mut islands = Vec::new();
        for indices in groups.into_values() {
            checkpoint().await;
            let branches: Arc<Vec<DcBranch>> = if single {
                circuit.branches.clone()
            } else {
                Arc::new(
                    indices
                        .iter()
                        .map(|&i| circuit.branches[i].clone())
                        .collect(),
                )
            };
            let layout = if single {
                circuit.layout.clone()
            } else {
                Arc::new(Layout::compile(&branches)?)
            };
            let rc = rc::Response::compile(&branches, &layout).await?;
            if single && rc.is_none() {
                break;
            }
            islands.push(Island {
                indices,
                branches,
                layout,
                rc,
            });
        }
        Ok(Self(islands))
    }
}

impl TransientCircuit {
    pub(crate) fn exact_rc_plan(&self) -> bool {
        self.plans.get().is_some_and(|plans| {
            !plans.0.is_empty() && plans.0.iter().all(|island| island.rc.is_some())
        })
    }

    pub(super) async fn island_trial(
        &self,
        end: f64,
        accuracy: Accuracy,
    ) -> Result<Option<Trial>, SolveError> {
        if self.plans.get().is_none() {
            let plans = Plans::compile(self).await?;
            let _ = self.plans.set(plans);
        }
        let islands = &self.plans.get().expect("compiled island layouts").0;
        if islands.is_empty() {
            return Ok(None);
        }
        let before = &self.accepted;
        let mut candidate = before.clone();
        candidate.time_seconds = end;
        candidate.energy.stored_joules = 0.0;
        candidate.operating_point.power_balance_watts = 0.0;
        let mut error = 0.0_f64;
        for island in islands {
            checkpoint().await;
            let branches: Vec<_> = island
                .indices
                .iter()
                .map(|&i| before.operating_point.branches[i].clone())
                .collect();
            let stored_joules = branches.iter().map(|b| b.stored_joules).sum();
            let point = dc::DcOperatingPoint {
                voltages: island
                    .layout
                    .rows
                    .keys()
                    .map(|p| (*p, before.operating_point.voltages[p]))
                    .collect(),
                references: island.layout.references.clone(),
                power_balance_watts: branches.iter().map(|p| p.absorbed_watts).sum(),
                branches,
            };
            let local = Self {
                layout: island.layout.clone(),
                branches: island.branches.clone(),
                plans: Default::default(),
                accepted: AcceptedState {
                    time_seconds: before.time_seconds,
                    operating_point: point,
                    energy: EnergyLedger {
                        initial_stored_joules: stored_joules,
                        stored_joules,
                        ..Default::default()
                    },
                    measurements: Measurements {
                        window_start: before.measurements.window_start,
                        branches: island
                            .indices
                            .iter()
                            .map(|&i| before.measurements.branches[i].clone())
                            .collect(),
                    },
                    source_phases: island
                        .branches
                        .iter()
                        .filter_map(|b| {
                            before
                                .source_phases
                                .get(&b.clock_id())
                                .map(|p| (b.clock_id(), *p))
                        })
                        .collect(),
                    sampler: Default::default(),
                },
            };
            let state = if let Some(response) = &island.rc {
                response.advance(&local, end).await?
            } else {
                // Apportion the domain's absolute energy tolerance across islands.
                let local_accuracy = Accuracy {
                    energy: accuracy.energy / islands.len() as f64,
                    ..accuracy
                };
                let trial = local.refine_local(end, local_accuracy).await?;
                error = error.max(trial.error);
                trial.end
            };
            let energy = state.energy;
            candidate.energy.stored_joules += energy.stored_joules;
            candidate.energy.source_supplied_joules += energy.source_supplied_joules;
            candidate.energy.source_absorbed_joules += energy.source_absorbed_joules;
            candidate.energy.dissipated_joules += energy.dissipated_joules;
            candidate.energy.numerical_damping_joules += energy.numerical_damping_joules;
            candidate.operating_point.power_balance_watts +=
                state.operating_point.power_balance_watts;
            candidate
                .operating_point
                .voltages
                .extend(state.operating_point.voltages);
            candidate.source_phases.extend(state.source_phases);
            for ((&i, p), moments) in island
                .indices
                .iter()
                .zip(state.operating_point.branches)
                .zip(state.measurements.branches)
            {
                candidate.operating_point.branches[i] = p;
                candidate.measurements.branches[i] = moments;
            }
        }
        if !candidate.energy.is_finite()
            || !candidate.operating_point.power_balance_watts.is_finite()
        {
            return Err(
                crate::model::element::rejected("Non-finite combined island candidate").into(),
            );
        }
        Ok(Some(Trial::single(candidate, error)))
    }
}
