use super::{
    AcceptedState, Accuracy, DcBranch, Element, EnergyLedger, Measurements, TransientCircuit,
    Trial, dc,
};
use crate::solver::{SolveError, cooperative::checkpoint};
use crate::topology::Layout;
use em_protocol::contract::{Id, PortId};
use std::{collections::{BTreeMap, HashMap}, sync::Arc};

mod rc;

pub(crate) struct Plans {
    islands: Vec<Island>,
    joint_trajectory: bool,
    components: usize,
    protected: usize,
    largest: usize,
}

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
    // Islands share the domain clock, tolerances and quadrature window. Match remaining numerical
    // inputs before reusing a representative trajectory.
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
    pub(crate) fn report(&self, unknowns: usize) {
        let mode = match (self.islands.is_empty(), self.joint_trajectory) {
            (true, _) => "single-coupled-component",
            (false, true) => "shared-integration-stages",
            (false, false) => "independent-trajectories",
        };
        super::super::progress::island_layout(self.components, self.protected, self.largest, unknowns, mode);
    }

    async fn compile(circuit: &TransientCircuit) -> Result<Self, SolveError> {
        // Electrical connectivity determines voltage references. Constitutive equations such as
        // transformer coupling can span multiple references.
        fn root(parents: &mut [usize], mut n: usize) -> usize {
            while parents[n] != n {
                parents[n] = parents[parents[n]];
                n = parents[n];
            }
            n
        }
        let components = &circuit.layout.components;
        let mut parents: Vec<_> = (0..circuit.layout.references.len()).collect();
        let mut clocks = HashMap::new();
        for b in circuit.branches.iter() {
            checkpoint().await;
            let a = root(&mut parents, components[&b.positive]);
            if b.element.source_clock().is_some()
                && let Some(other) = clocks.insert(b.clock_id(), a) {
                let old = root(&mut parents, other);
                parents[old] = a;
            }
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
        let components = groups.len();
        let protected = groups.values().filter(|indices| indices.iter().any(|&i|
            matches!(circuit.branches[i].element, Element::Relay { .. })
                || circuit.branches[i].element.thermal_node().is_some_and(|(model, _)| model.trip_kelvin.is_some())
        )).count();
        let single = components == 1;
        let mut joint_trajectory = false;
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
            let independent_trajectory = branches.iter().all(|b| matches!(b.element,
                Element::Resistor { .. } | Element::DcVoltageSource { .. } | Element::Capacitor { .. }
                | Element::Inductor { .. } | Element::Windings(_) | Element::SineVoltageSource { .. }
                | Element::Diode { .. }));
            joint_trajectory |= !independent_trajectory;
            if single && !independent_trajectory { break; }
            let layout = if single {
                circuit.layout.clone()
            } else {
                Arc::new(Layout::compile(&branches)?)
            };
            let rc = rc::Response::compile(&branches, &layout).await?;
            if single && rc.is_none() {
                break;
            }
            // Number ports by first terminal occurrence, distinguishing references even when they
            // have no matrix row.
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
        let largest = if single { circuit.layout.size } else { islands.iter().map(|i| i.layout.size).max().unwrap_or(0) };
        let plans = Self { islands, joint_trajectory, components, protected, largest };
        plans.report(circuit.layout.size);
        Ok(plans)
    }
}

impl TransientCircuit {
    fn island_seed(island: &Island, before: &AcceptedState) -> Self {
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
        Self {
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
        }
    }

    // Use synchronized integration stages across components, then combine observations, error
    // estimates and intermediate event states.
    pub(super) async fn component_point(&self, before: &AcceptedState, end: f64, h: f64, phases: &super::Phases)
        -> Result<Option<dc::DcOperatingPoint>, SolveError> {
        let Some(plans) = self.plans.get() else { return Ok(None); };
        if !plans.joint_trajectory || plans.islands.len() < 2 { return Ok(None); }
        let mut point = before.operating_point.clone();
        point.power_balance_watts = 0.0;
        for island in &plans.islands {
            let local = Self::island_seed(island, before);
            let solved = local.local_backward_euler_point(local.accepted(), end, h, phases).await?;
            point.power_balance_watts += solved.power_balance_watts;
            point.voltages.extend(solved.voltages);
            for (&index, branch) in island.indices.iter().zip(solved.branches) {
                point.branches[index] = branch;
            }
        }
        Ok(Some(point))
    }

    pub(crate) fn exact_rc_plan(&self) -> bool {
        self.plans.get().is_some_and(|plans| {
            !plans.islands.is_empty() && plans.islands.iter().all(|island| island.rc.is_some())
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
        let plans = self.plans.get().expect("compiled island layouts");
        let islands = &plans.islands;
        if islands.is_empty() || plans.joint_trajectory {
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
            let local = Self::island_seed(island, before);
            let state = if let Some(response) = &island.rc {
                response.advance(local, end).await?
            } else {
                // Distribute the domain's absolute energy tolerance among its islands.
                let local_accuracy = Accuracy {
                    energy: accuracy.energy / islands.len() as f64,
                    ..accuracy
                };
                // Equivalent small islands already share a representative trajectory.
                let independent = !island.same_equations_as_previous
                    && !remaining.peek().is_some_and(|other| other.same_equations_as_previous);
                let trial = local.refine_local(end, local_accuracy, independent).await?;
                error = error.max(trial.error);
                trial.end
            };
            accumulate(&mut candidate, &state);
            // Reuse is local to one trial. Solve islands separately when inputs, parameters or
            // observation histories differ.
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
