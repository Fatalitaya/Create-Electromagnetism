use em_protocol::contract::*;

#[test]
fn rejects_invalid_times_and_unbounded_work() {
    let request = Advance {
        revision: Revision {
            session: Id([1, 2]),
            topology: 0,
            parameters: 0,
            sequence: 0,
        },
        accepted_start: 1.0,
        requested_end: 1.05,
        max_steps: 100,
        budget_nanos: 1_000_000,
    };
    assert!(request.validate().is_ok());
    for invalid in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -1.0, 0.5] {
        assert_eq!(
            Advance {
                requested_end: invalid,
                ..request
            }
            .validate()
            .unwrap_err()
            .status,
            Status::Rejected
        );
    }
    assert!(
        Advance {
            max_steps: 0,
            ..request
        }
        .validate()
        .is_err()
    );
    assert!(
        Advance {
            budget_nanos: 0,
            ..request
        }
        .validate()
        .is_err()
    );
}
