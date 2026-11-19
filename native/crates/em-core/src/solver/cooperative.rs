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

thread_local! {
    static SLICE: Cell<Option<Slice>> = const { Cell::new(None) };
}

pub(super) async fn checkpoint() {
    poll_fn(|_| {
        SLICE.with(|slot| {
            let Some(mut slice) = slot.get() else {
                return Poll::Ready(());
            };
            if slice.until_clock_check == 0 {
                if slice.started.elapsed() >= slice.budget {
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
    future.poll(&mut Context::from_waker(Waker::noop()))
}

pub(super) fn complete<T>(future: impl Future<Output = Result<T,super::SolveError>>) -> Result<T,super::SolveError> {
    match pin!(future).poll(&mut Context::from_waker(Waker::noop())) {
        Poll::Ready(value) => value,
        Poll::Pending => unreachable!("unbudgeted solver suspended"),
    }
}
