//! Keep diagnostics per calling thread so they remain readable while the game thread waits in JNI.
//! They are separate from physical state and the wire protocol.
use em_protocol::contract::{Advance, AdvanceWork, Diagnostic, Id};
use std::{cell::RefCell, io::Write, time::{Duration, Instant}};

thread_local! {
    static ACTIVE: RefCell<Option<Progress>> = const { RefCell::new(None) };
}

/// Return the latest advance counters on this thread, including worker contributions.
/// EM_NATIVE_PROGRESS_LOG enables additional timing.
#[derive(Clone, Copy, Default)]
pub struct AdvanceProfile {
    pub newton_iterations: u64,
    pub backtracks: u64,
    pub linear_solves: u64,
    pub factorizations: u64,
    pub factor_us: u128,
    pub assembly_us: u128,
    pub substitution_us: u128,
    pub max_unknowns: usize,
    pub max_nonzeros: usize,
    pub localization_probe_us: u128,
    pub localization_validation_us: u128,
    pub localization_newton_iterations: u64,
    pub localization_factorizations: u64,
}
thread_local! {
    static LAST: std::cell::Cell<AdvanceProfile> = Default::default();
    static PROFILE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

pub fn profile_advance<T>(advance: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset { fn drop(&mut self) { PROFILE.set(self.0); } }
    let _reset = Reset(PROFILE.replace(true));
    advance()
}
pub fn last_advance_profile() -> AdvanceProfile { LAST.get() }

pub(super) struct Progress {
    request: Advance,
    started: Instant,
    reported: Instant,
    time: f64,
    step: f64,
    smallest_step: f64,
    work: AdvanceWork,
    plan: (usize, usize, usize, usize, &'static str),
    final_validation: Duration,
    failed_probes: u64,
    localization: [Duration; 2],
    localization_counts: [u64; 5],
    newton_iterations: u64,
    backtracks: u64,
    linear_solves: u64,
    fast_trials: u64,
    harmonic_trials: u64,
    mixed_intervals: u64,
    periodic_fallbacks: u64,
    newton_trials: u64,
    largest_error: f64,
    error_branch: Option<Id>,
    error_quantity: &'static str,
    step_limits: [u64; 5],
    trajectory_time: [Duration; 3],
    refinement: [u64; 5],
    harmonic_plan: &'static str,
    limiting_components: std::collections::HashMap<(Option<Id>, &'static str), u64>,
    radau_fallbacks: u64,
    last_failure: Option<String>,
    phase: &'static str,
    profile: bool,
    assembly: Duration,
    factorization: Duration,
    substitution: Duration,
    factorizations: u64,
    cache_hits: u64,
    largest_matrix: usize,
    largest_factor: usize,
    peak_nonzeros: usize,
}

pub(crate) struct Guard;

pub(crate) fn begin(request: Advance) -> Guard {
    let now = Instant::now();
    ACTIVE.with(|slot| *slot.borrow_mut() = Some(Progress {
        request, started: now, reported: now, time: request.accepted_start,
        step: 0.0, smallest_step: f64::INFINITY, work: AdvanceWork::default(),
        plan: (0,0,0,0,"unexamined"), final_validation: Duration::ZERO, failed_probes: 0, localization: [Duration::ZERO; 2], localization_counts: [0; 5],
        newton_iterations: 0, backtracks: 0, linear_solves: 0,
        fast_trials: 0, harmonic_trials: 0, mixed_intervals: 0, periodic_fallbacks: 0, newton_trials: 0, largest_error: 0.0, error_branch: None,
        error_quantity: "", step_limits: [0; 5], trajectory_time: [Duration::ZERO; 3], refinement: [0; 5], harmonic_plan: "unexamined", limiting_components: Default::default(), radau_fallbacks: 0, phase: "advance", last_failure: None,
        profile: PROFILE.get() || std::env::var_os("EM_NATIVE_PROGRESS_LOG").is_some(),
        assembly: Duration::ZERO, factorization: Duration::ZERO, substitution: Duration::ZERO,
        factorizations: 0, cache_hits: 0, largest_matrix: 0, largest_factor: 0, peak_nonzeros: 0,
    }));
    Guard
}

impl Progress {
    fn report(&mut self, force: bool) {
        if !force && self.reported.elapsed() < Duration::from_secs(1) { return; }
        self.reported = Instant::now();
        let dominant_limiter = self.limiting_components.iter().max_by_key(|((branch, quantity), count)| (**count, branch.map(|id| id.0), *quantity));
        let line = format!(
            "[em-native-progress] session={:?} sequence={} elapsed_s={:.3} phase={} accepted_time={:.17e} target={:.17e} trial_step={:.17e} min_trial_step={:.17e} work={:?} newton_iterations={} backtracks={} linear_solves={} fast_trials={} newton_trials={} largest_error={:.9e} error_branch={:?} last_failure={:?} physical_seconds={:.9e} assembly_us={} factor_us={} substitution_us={} factorizations={} cache_hits={} max_unknowns={} max_factor_dimension={} max_nonzeros={} harmonic_trials={} mixed_intervals={} periodic_fallbacks={} error_quantity={} adaptive_limited={} max_step_limited={} sample_limited={} source_limited={} window_limited={} full_us={} halves_us={} quarters_us={} dominant_limiter={:?} radau_fallbacks={} localization_probe_us={} localization_validation_us={} localization_probes={} localization_validations={} localization_newton_iterations={} localization_factorizations={} localization_backtracks={} electrical_component_count={} protected_component_count={} largest_component_unknowns={} layout_unknowns={} island_plan={} localization_final_validation_us={} localization_rejected_probes={} harmonic_plan={} refined_trials={} full_step_solves={} half_step_solves={} quarter_step_solves={} refinement_error_retries={}",
            self.request.revision.session, self.request.revision.sequence,
            self.started.elapsed().as_secs_f64(), self.phase, self.time,
            self.request.requested_end, self.step, self.smallest_step, self.work,
            self.newton_iterations, self.backtracks, self.linear_solves,
            self.fast_trials, self.newton_trials, self.largest_error, self.error_branch, self.last_failure,
            self.time - self.request.accepted_start, self.assembly.as_micros(), self.factorization.as_micros(),
            self.substitution.as_micros(), self.factorizations, self.cache_hits,
            self.largest_matrix, self.largest_factor, self.peak_nonzeros, self.harmonic_trials, self.mixed_intervals, self.periodic_fallbacks, self.error_quantity, self.step_limits[0], self.step_limits[1], self.step_limits[2], self.step_limits[3], self.step_limits[4], self.trajectory_time[0].as_micros(), self.trajectory_time[1].as_micros(), self.trajectory_time[2].as_micros(), dominant_limiter, self.radau_fallbacks, self.localization[0].as_micros(), self.localization[1].as_micros(), self.localization_counts[0], self.localization_counts[1], self.localization_counts[2], self.localization_counts[3], self.localization_counts[4], self.plan.0, self.plan.1, self.plan.2, self.plan.3, self.plan.4, self.final_validation.as_micros(), self.failed_probes, self.harmonic_plan, self.refinement[0], self.refinement[1], self.refinement[2], self.refinement[3], self.refinement[4],
        );
        // Finish the physical transaction even if the diagnostic output stream closes.
        let _ = writeln!(std::io::stderr().lock(), "{line}");
        if let Some(path) = std::env::var_os("EM_NATIVE_PROGRESS_LOG")
            && let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(path)
        {
            let _ = writeln!(file, "{line}");
        }
    }
}

fn update(f: impl FnOnce(&mut Progress)) {
    ACTIVE.with(|slot| {
        if let Some(progress) = slot.borrow_mut().as_mut() {
            f(progress);
            progress.report(false);
        }
    });
}

pub(crate) fn trial(start: f64, end: f64, work: AdvanceWork) {
    update(|p| {
        p.time = start; p.step = end - start; p.smallest_step = p.smallest_step.min(p.step);
        p.work = work; p.phase = "trial";
    });
}
pub(super) fn refinement(index: usize) { update(|p| p.refinement[index] += 1); }
pub(super) fn harmonic_plan(reason: &'static str) { update(|p| p.harmonic_plan = reason); }

pub(super) fn island_layout(components: usize, protected: usize, largest: usize, unknowns: usize, mode: &'static str) {
    update(|p| p.plan = (components, protected, largest, unknowns, mode));
}

pub(crate) fn localization_start() -> Option<(Instant, u64, u64, u64)> {
    ACTIVE.with(|slot| slot.borrow().as_ref().map(|p| (Instant::now(), p.newton_iterations, p.factorizations, p.backtracks)))
}

pub(crate) fn localization_end(start: Option<(Instant, u64, u64, u64)>, probe: bool, committing: bool, ready: bool, rejected: bool) {
    if let Some((time, newton, factors, backtracks)) = start {
        update(|p| {
            let i = usize::from(!probe);
            let elapsed = time.elapsed();
            p.localization[i] += elapsed;
            if committing { p.final_validation += elapsed; }
            if probe && rejected { p.failed_probes += 1; }
            p.localization_counts[i] += u64::from(ready);
            p.localization_counts[2] += p.newton_iterations - newton;
            p.localization_counts[3] += p.factorizations - factors;
            p.localization_counts[4] += p.backtracks - backtracks;
        });
    }
}

pub(super) fn timer() -> Option<Instant> {
    ACTIVE.with(|slot| slot.borrow().as_ref().filter(|p| p.profile).map(|_| Instant::now()))
}

pub(super) fn matrix_size(unknowns: usize, nonzeros: usize) {
    ACTIVE.with(|slot| {
        if let Some(p) = slot.borrow_mut().as_mut() {
            p.largest_matrix = p.largest_matrix.max(unknowns);
            p.peak_nonzeros = p.peak_nonzeros.max(nonzeros);
        }
    });
}

pub(super) fn assembled(start: Option<Instant>) {
    if let Some(start) = start { update(|p| p.assembly += start.elapsed()); }
}

pub(super) fn factored(start: Option<Instant>, dimension: usize) {
    if let Some(start) = start { update(|p| { p.factorization += start.elapsed(); p.factorizations += 1; p.largest_factor = p.largest_factor.max(dimension); }); }
}

pub(super) fn substituted(start: Option<Instant>, cached: bool) {
    if let Some(start) = start {
        update(|p| { p.substitution += start.elapsed(); p.cache_hits += u64::from(cached); });
    }
}

pub(crate) fn newton_iteration() {
    update(|p| { p.newton_iterations += 1; p.phase = "newton"; });
}
pub(crate) fn backtrack() {
    update(|p| { p.backtracks += 1; p.phase = "line-search"; });
}
pub(crate) fn linear_solve() {
    update(|p| { p.linear_solves += 1; p.phase = "factorization"; });
}
pub(crate) fn harmonic_trial() { update(|p| p.harmonic_trials += 1); }
pub(crate) fn mixed_interval() { update(|p| p.mixed_intervals += 1); }
pub(crate) fn periodic_fallback() { update(|p| p.periodic_fallbacks += 1); }
pub(crate) fn fast_trial() { update(|p| p.fast_trials += 1); }
pub(crate) fn newton_trial() { update(|p| p.newton_trials += 1); }
pub(crate) fn error(branch: Option<Id>, quantity: &'static str, error: f64) {
    update(|p| {
        if p.profile && error > 0.0 { *p.limiting_components.entry((branch, quantity)).or_default() += 1; }
        if error > p.largest_error {
            p.largest_error = error;
            p.error_branch = branch;
            p.error_quantity = quantity;
        }
    });
}

pub(super) fn radau_fallback() { update(|p| p.radau_fallbacks += 1); }

pub(crate) fn step_limit(index: usize) { update(|p| p.step_limits[index] += 1); }

pub(super) async fn timed_trajectory<T>(index: usize, future: impl std::future::Future<Output=T>) -> T {
    let mut future = std::pin::pin!(future);
    std::future::poll_fn(|cx| {
        let started = timer();
        let result = future.as_mut().poll(cx);
        if let Some(start) = started { update(|p| p.trajectory_time[index] += start.elapsed()); }
        result
    }).await
}
pub(crate) fn failure(reason: &str) { update(|p| p.last_failure = Some(reason.to_owned())); }
pub(crate) fn complete(time: f64, work: AdvanceWork, diagnostic: &Diagnostic) {
    update(|p| {
        p.time = time; p.work = work; p.phase = "complete";
        LAST.set(AdvanceProfile {
            newton_iterations: p.newton_iterations, backtracks: p.backtracks, linear_solves: p.linear_solves,
            factorizations: p.factorizations, factor_us: p.factorization.as_micros(),
            assembly_us: p.assembly.as_micros(), substitution_us: p.substitution.as_micros(),
            max_unknowns: p.largest_matrix, max_nonzeros: p.peak_nonzeros,
            localization_probe_us: p.localization[0].as_micros(), localization_validation_us: p.localization[1].as_micros(),
            localization_newton_iterations: p.localization_counts[2], localization_factorizations: p.localization_counts[3],
        });
        if p.profile || p.started.elapsed() >= Duration::from_secs(1)
            || (diagnostic.status != em_protocol::contract::Status::Ready
                && std::env::var_os("EM_NATIVE_PROGRESS_LOG").is_some()) {
            p.report(true);
            let _ = writeln!(std::io::stderr().lock(), "[em-native-progress] result={diagnostic:?}");
        }
    });
}
impl Drop for Guard {
    fn drop(&mut self) { ACTIVE.with(|slot| { slot.borrow_mut().take(); }); }
}

// Initialization fill counts include all Newton solves used to obtain the first operating point.
thread_local! {
    static INIT_FILL: std::cell::Cell<Option<usize>> = const { std::cell::Cell::new(None) };
}

pub(crate) struct InitializationProfile {
    previous: Option<usize>,
}

impl InitializationProfile {
    pub(crate) fn begin() -> Option<Self> {
        std::env::var_os("EM_NATIVE_PROFILE_INIT")?;
        Some(Self { previous: INIT_FILL.replace(Some(0)) })
    }

    pub(crate) fn timer() -> Option<Instant> {
        INIT_FILL.get().map(|_| Instant::now())
    }

    pub(crate) fn fill(entries: usize) {
        if let Some(count) = INIT_FILL.get() { INIT_FILL.set(Some(count + entries)); }
    }

    pub(crate) fn report_point(&self, started: Instant, branches: usize) {
        let _ = writeln!(std::io::stderr().lock(),
            "[em-native-init] branches={branches} operating_point_us={} numeric_fill_insertions={}",
            started.elapsed().as_micros(), INIT_FILL.get().unwrap_or_default());
    }

    pub(crate) fn report_layout(started: Instant, ordering: Instant, size: usize) {
        let _ = writeln!(std::io::stderr().lock(),
            "[em-native-init] unknowns={size} layout_us={} symbolic_us={}",
            ordering.duration_since(started).as_micros(), ordering.elapsed().as_micros());
    }
}

impl Drop for InitializationProfile {
    fn drop(&mut self) { INIT_FILL.set(self.previous); }
}

pub(super) fn request() -> Option<(Advance, bool)> {
    ACTIVE.with(|slot| slot.borrow().as_ref().map(|p| (p.request, p.profile)))
}

pub(super) fn on_worker<T>(request: Option<(Advance, bool)>, work: impl FnOnce() -> T) -> (T, Option<Progress>) {
    let run = || {
        let _guard = request.map(|(advance, _)| begin(advance));
        (work(), ACTIVE.with(|slot| slot.take()))
    };
    if request.is_some_and(|(_, profile)| profile) { profile_advance(run) } else { run() }
}

pub(super) fn merge_worker(worker: Option<Progress>) {
    if let Some(w) = worker {
        update(|p| {
            p.newton_iterations += w.newton_iterations;
            p.newton_trials += w.newton_trials;
            p.fast_trials += w.fast_trials;
            p.backtracks += w.backtracks;
            p.linear_solves += w.linear_solves;
            p.factorizations += w.factorizations;
            p.cache_hits += w.cache_hits;
            p.trajectory_time[0] += w.trajectory_time[0];
            for (total, count) in p.refinement.iter_mut().zip(w.refinement) { *total += count; }
            p.assembly += w.assembly;
            p.factorization += w.factorization;
            p.substitution += w.substitution;
            p.largest_matrix = p.largest_matrix.max(w.largest_matrix);
            p.largest_factor = p.largest_factor.max(w.largest_factor);
            p.peak_nonzeros = p.peak_nonzeros.max(w.peak_nonzeros);
        });
    }
}
