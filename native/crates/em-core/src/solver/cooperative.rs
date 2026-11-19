//! A trial is polled on the calling thread. Suspension retains work without
//! retaining a deadline, a cancellation reference, or access to the session.
use std::{
    cell::Cell,
    future::{Future, poll_fn},
    pin::{Pin, pin},
    task::{Context, Poll, Waker},
    time::{Duration, Instant},
};

#[derive(Clone, Copy)]
struct Slice {
    started: Instant,
    budget: Duration,
    until_clock_check: u8,
}

scoped_tls::scoped_thread_local!(static CANCELLED: std::sync::atomic::AtomicBool);
thread_local! {
    static POOL: std::cell::OnceCell<Option<rayon_core::ThreadPool>> = const { std::cell::OnceCell::new() };
    static HELPER: Cell<bool> = const { Cell::new(false) };
    static SLICE: Cell<Option<Slice>> = const { Cell::new(None) };
}

pub(super) async fn checkpoint() {
    poll_fn(|_| {
        SLICE.with(|slot| {
            let Some(mut slice) = slot.get() else {
                return Poll::Ready(());
            };
            if slice.until_clock_check == 0 {
                if slice.started.elapsed() >= slice.budget
                    || CANCELLED.with(|c| c.load(std::sync::atomic::Ordering::Acquire))
                {
                    return Poll::Pending;
                }
                slice.until_clock_check = 15;
            } else {
                slice.until_clock_check -= 1;
            }
            slot.set(Some(slice));
            Poll::Ready(())
        })
    })
    .await
}

pub(crate) fn poll<T>(
    future: Pin<&mut (impl Future<Output = T> + ?Sized)>,
    started: Instant,
    budget: Duration,
    cancelled: &std::sync::atomic::AtomicBool,
) -> Poll<T> {
    struct Reset(Option<Slice>);
    impl Drop for Reset {
        fn drop(&mut self) {
            SLICE.with(|slot| slot.set(self.0));
        }
    }
    let _reset = Reset(SLICE.with(|slot| {
        slot.replace(Some(Slice {
            started,
            budget,
            until_clock_check: 0,
        }))
    }));
    CANCELLED.set(cancelled, || {
        future.poll(&mut Context::from_waker(Waker::noop()))
    })
}

pub(super) fn complete<T>(
    future: impl Future<Output = Result<T, super::SolveError>>,
) -> Result<T, super::SolveError> {
    match pin!(future).poll(&mut Context::from_waker(Waker::noop())) {
        Poll::Ready(value) => value,
        Poll::Pending => unreachable!("unbudgeted solver suspended"),
    }
}
/// Installs the Java scheduler's spare permit for this synchronous JNI call.
pub fn with_helper<T>(available: bool, work: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            HELPER.set(self.0);
        }
    }
    let _reset = Reset(HELPER.replace(available));
    work()
}

pub(super) async fn trajectories<A: Send, B>(
    heavy: bool,
    full: impl Future<Output = Result<A, super::SolveError>> + Send,
    halves: impl Future<Output = Result<B, super::SolveError>>,
) -> Result<(A, B), super::SolveError> {
    let mut full = pin!(super::progress::timed_trajectory(0, full));
    let mut halves = pin!(super::progress::timed_trajectory(1, halves));
    if !heavy || !HELPER.get() {
        return Ok((full.await?, halves.await?));
    }
    let mut coarse = None;
    let mut fine = None;
    poll_fn(|cx| {
        let slice = SLICE.get();
        if HELPER.get()
            && coarse.is_none()
            && fine.is_none()
            && let Some(slice) = slice
        {
            let request = super::progress::request();
            let mut worker_result = None;
            POOL.with(|pool| {
                let pool = pool.get_or_init(|| {
                    rayon_core::ThreadPoolBuilder::new()
                        .num_threads(1)
                        .thread_name(|_| "electromagnetism-trajectory".into())
                        .build()
                        .ok()
                });
                if let Some(pool) = pool {
                    CANCELLED.with(|cancelled| {
                        pool.in_place_scope(|scope| {
                            scope.spawn(|_| {
                                worker_result = Some(super::progress::on_worker(request, || {
                                    poll(full.as_mut(), slice.started, slice.budget, cancelled)
                                }));
                            });
                            if let Poll::Ready(result) = halves.as_mut().poll(cx) {
                                fine = Some(result);
                            }
                        })
                    });
                } else if let Poll::Ready(result) = full.as_mut().poll(cx) {
                    coarse = Some(result);
                }
            });
            if let Some((result, counts)) = worker_result {
                super::progress::merge_worker(counts);
                if let Poll::Ready(value) = result {
                    coarse = Some(value);
                }
            }
        } else {
            if coarse.is_none() {
                match full.as_mut().poll(cx) {
                    Poll::Pending => return Poll::Pending,
                    Poll::Ready(value) => coarse = Some(value),
                }
            }
            if fine.is_none()
                && let Poll::Ready(value) = halves.as_mut().poll(cx)
            {
                fine = Some(value);
            }
        }
        match (coarse.take(), fine.take()) {
            (Some(Err(error)), _) => Poll::Ready(Err(error)),
            (Some(Ok(full)), Some(halves)) => Poll::Ready(halves.map(|halves| (full, halves))),
            (pending_full, pending_halves) => {
                coarse = pending_full;
                fine = pending_halves;
                Poll::Pending
            }
        }
    })
    .await
}
