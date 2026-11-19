use super::{
    AcceptedState, Accuracy, DcBranch, Element, EnergyLedger, Measurements, TransientCircuit,
    Trial, dc,
};
use crate::solver::{SolveError, cooperative::checkpoint};
use crate::topology::Layout;
use em_protocol::contract::{Id, PortId};
use std::{collections::{BTreeMap, HashMap}, sync::Arc};

mod rc;

pub(crate) struct Plans(Vec<Island>);

struct Island {
    indices: Vec<usize>,
    branches: Arc<Vec<DcBranch>>,
    layout: Arc<Layout>,
    rc: Option<rc::Response>,
    same_equations_as_previous: bool,
    ports: Vec<PortId>,
    clocks: Vec<Id>,
}

impl Island {
    // The domain clock, tolerances and quadrature window are shared. Compare the
    // remaining numerical inputs before reusing an independent island trajectory.
    fn same_inputs(&self, other: &Self, state: &AcceptedState) -> bool {
        self.indices.iter().zip(&other.indices).all(|(&a, &b)| {
            let left = &state.operating_point.branches[a];
            let right = &state.operating_point.branches[b];
            left.local_values == right.local_values
                && left.voltage == right.voltage && left.current == right.current
                && left.absorbed_watts == right.absorbed_watts && left.observed_watts == right.observed_watts
                && left.stored_joules == right.stored_joules && left.dissipated_watts == right.dissipated_watts
                && state.measurements.branches[a] == state.measurements.branches[b]
        }) && self.ports.iter().zip(&other.ports).all(|(p, q)|
            state.operating_point.voltages[p] == state.operating_point.voltages[q])
            && self.clocks.iter().zip(&other.clocks).all(|(a, b)| state.source_phases.get(a) == state.source_phases.get(b))
    }

    fn copy_trajectory(&self, source: &Self, state: &AcceptedState, candidate: &mut AcceptedState) {
        for (i, (&target, branch)) in self.indices.iter().zip(self.branches.iter()).enumerate() {
            let mut point = state.operating_point.branches[i].clone();
            point.id = branch.id;
            candidate.operating_point.branches[target] = point;
            candidate.measurements.branches[target].clone_from(&state.measurements.branches[i]);
        }
        for (&p, q) in self.ports.iter().zip(&source.ports) {
            candidate.operating_point.voltages.insert(p, state.operating_point.voltages[q]);
        }
        for (&clock, original) in self.clocks.iter().zip(&source.clocks) {
            if let Some(&phase) = state.source_phases.get(original) {
                candidate.source_phases.insert(clock, phase);
            }
        }
    }
}

fn accumulate(candidate: &mut AcceptedState, state: &AcceptedState) {
    candidate.energy.stored_joules += state.energy.stored_joules;
    candidate.energy.source_supplied_joules += state.energy.source_supplied_joules;
    candidate.energy.source_absorbed_joules += state.energy.source_absorbed_joules;
    candidate.energy.dissipated_joules += state.energy.dissipated_joules;
    candidate.energy.numerical_damping_joules += state.energy.numerical_damping_joules;
    candidate.operating_point.power_balance_watts += state.operating_point.power_balance_watts;
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
        let mut previous_port_pattern = Vec::new();
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
            // Number ports by their first terminal occurrence. The pattern also
            // distinguishes multiple grounded references with no matrix row.
            let mut ports = Vec::new();
            let mut clocks = Vec::new();
            let mut port_pattern = Vec::new();
            {
                let mut slots = HashMap::new();
                for branch in branches.iter() {
                    if branch.element.source_clock().is_some() { clocks.push(branch.clock_id()); }
                    for port in branch.ports() {
                        let slot = slots.entry(port).or_insert_with(|| {
                            ports.push(port);
                            ports.len() - 1
                        });
                        port_pattern.push(*slot);
                    }
                }
            }
            let same_equations_as_previous = islands.last().is_some_and(|previous: &Island| {
                previous_port_pattern == port_pattern && previous.layout.mappings == layout.mappings
                    && previous.branches.iter().zip(branches.iter()).all(|(a, b)| a.element == b.element)
                    && match (&rc, &previous.rc) {
                        (Some(a), Some(b)) => a.same_response(b, &ports, &previous.ports),
                        (None, None) => true,
                        _ => false,
                    }
            });
            previous_port_pattern = port_pattern;
            islands.push(Island {
                indices,
                branches,
                layout,
                rc,
                same_equations_as_previous,
                ports,
                clocks,
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
        let mut remaining = islands.iter().peekable();
        while let Some(island) = remaining.next() {
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
                game_interval: false,
                harmonic: Default::default(),
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
                response.advance(local, end).await?
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
            accumulate(&mut candidate, &state);
            // Every reused result belongs to this trial only. Distinct inputs,
            // parameter edits and different observation histories take the normal solve.
            while let Some(other) = remaining.next_if(|other| {
                other.same_equations_as_previous && island.same_inputs(other, before)
            }) {
                checkpoint().await;
                other.copy_trajectory(island, &state, &mut candidate);
                accumulate(&mut candidate, &state);
            }
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
