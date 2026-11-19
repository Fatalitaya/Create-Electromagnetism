//! Out-of-band diagnostics: readable while the owning game thread is inside native.
//! Counters are per calling thread and never enter accepted physical state or the wire format.
use em_protocol::contract::{Advance, AdvanceWork, Diagnostic, Id};
use std::{cell::RefCell, io::Write, time::{Duration, Instant}};

thread_local! {
    static ACTIVE: RefCell<Option<Progress>> = const { RefCell::new(None) };
}

struct Progress {
    request: Advance,
    started: Instant,
    reported: Instant,
    time: f64,
    step: f64,
    smallest_step: f64,
    work: AdvanceWork,
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
        newton_iterations: 0, backtracks: 0, linear_solves: 0,
        fast_trials: 0, harmonic_trials: 0, mixed_intervals: 0, periodic_fallbacks: 0, newton_trials: 0, largest_error: 0.0, error_branch: None,
        phase: "advance", last_failure: None,
        profile: std::env::var_os("EM_NATIVE_PROGRESS_LOG").is_some() && request.is_unlimited(),
        assembly: Duration::ZERO, factorization: Duration::ZERO, substitution: Duration::ZERO,
        factorizations: 0, cache_hits: 0, largest_matrix: 0, largest_factor: 0, peak_nonzeros: 0,
    }));
    Guard
}

impl Progress {
    fn report(&mut self, force: bool) {
        if !force && self.reported.elapsed() < Duration::from_secs(1) { return; }
        self.reported = Instant::now();
        let line = format!(
            "[em-native-progress] session={:?} sequence={} elapsed_s={:.3} phase={} accepted_time={:.17e} target={:.17e} trial_step={:.17e} min_trial_step={:.17e} work={:?} newton_iterations={} backtracks={} linear_solves={} fast_trials={} newton_trials={} largest_error={:.9e} error_branch={:?} last_failure={:?} physical_seconds={:.9e} assembly_us={} factor_us={} substitution_us={} factorizations={} cache_hits={} max_unknowns={} max_factor_dimension={} max_nonzeros={} harmonic_trials={} mixed_intervals={} periodic_fallbacks={}",
            self.request.revision.session, self.request.revision.sequence,
            self.started.elapsed().as_secs_f64(), self.phase, self.time,
            self.request.requested_end, self.step, self.smallest_step, self.work,
            self.newton_iterations, self.backtracks, self.linear_solves,
            self.fast_trials, self.newton_trials, self.largest_error, self.error_branch, self.last_failure,
            self.time - self.request.accepted_start, self.assembly.as_micros(), self.factorization.as_micros(),
            self.substitution.as_micros(), self.factorizations, self.cache_hits,
            self.largest_matrix, self.largest_factor, self.peak_nonzeros, self.harmonic_trials, self.mixed_intervals, self.periodic_fallbacks,
        );
        // A closed diagnostic stream must never abort a physical transaction.
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
pub(crate) fn error(branch: Id, error: f64) {
    update(|p| {
        if error > p.largest_error { p.largest_error = error; p.error_branch = Some(branch); }
    });
}
pub(crate) fn global_error(error: f64) {
    update(|p| {
        if error > p.largest_error { p.largest_error = error; p.error_branch = None; }
    });
}
pub(crate) fn failure(reason: &str) { update(|p| p.last_failure = Some(reason.to_owned())); }
pub(crate) fn complete(time: f64, work: AdvanceWork, diagnostic: &Diagnostic) {
    update(|p| {
        p.time = time; p.work = work; p.phase = "complete";
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

// Optional native initialization diagnostics. Each construction on this thread
// owns its fill count, including all Newton solves used to obtain its first point.
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
