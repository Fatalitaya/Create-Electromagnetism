//! World-independent samples for device constitutive laws and prescribed phases.
use std::{f64::consts::TAU, sync::LazyLock};

const PHASES: usize = 256;
const SCALE: f64 = 16.0;
const LIMIT: i32 = 32 * 16;

struct Catalog {
    four_terminal: [f64; 49],
    filter_rate: f64,
    phase: Box<[(f64, f64)]>,
    junction: Box<[(f64, f64)]>,
}

static CATALOG: LazyLock<Catalog> = LazyLock::new(|| Catalog {
    // Two port pairs with a common KCL return, in the model local-unknown order.
    four_terminal: {
        let mut matrix = [0.0; 49];
        for i in [2, 25, 41, 46, 48] {
            matrix[i] = 1.0;
        }
        for i in [9, 11, 13] {
            matrix[i] = -1.0;
        }
        matrix
    },
    filter_rate: TAU / (2.0_f64.powf(0.25) - 1.0).sqrt(),
    phase: (0..PHASES)
        .map(|i| (TAU * i as f64 / PHASES as f64).sin_cos())
        .collect(),
    junction: (-LIMIT..=LIMIT)
        .map(|i| {
            let x = i as f64 / SCALE;
            (x.exp_m1(), x.exp())
        })
        .collect(),
});

pub fn initialize() {
    LazyLock::force(&CATALOG);
}

pub(crate) fn sin_cos(angle: f64) -> (f64, f64) {
    if angle.abs() > 4.0 * TAU || !angle.is_finite() {
        return angle.sin_cos();
    }
    let nearest = (angle * (PHASES as f64 / TAU)).round();
    let d = angle - nearest * (TAU / PHASES as f64);
    let (s, c) = CATALOG.phase[(nearest as isize as usize) % PHASES];
    let q = d * d;
    let sd = d * (1.0 + q * (-1.0 / 6.0 + q / 120.0));
    let cd = 1.0 + q * (-0.5 + q * (1.0 / 24.0 - q / 720.0));
    (s * cd + c * sd, c * cd - s * sd)
}

// Expand around the nearest tabulated voltage. Return the current and the analytic
// derivative of that same polynomial. The zero anchor preserves sub-ulp forward currents.
pub(crate) fn junction_exponent(x: f64) -> (f64, f64) {
    if !(-32.0..=32.0).contains(&x) {
        return (x.exp_m1(), x.exp());
    }
    let nearest = (x * SCALE).round();
    let d = x - nearest / SCALE;
    let (excess, slope) = CATALOG.junction[(nearest as i32 + LIMIT) as usize];
    let tail = d
        * (1.0
            + d * (0.5
                + d * (1.0 / 6.0
                    + d * (1.0 / 24.0 + d * (1.0 / 120.0 + d * (1.0 / 720.0 + d / 5040.0))))));
    let derivative = 1.0
        + d * (1.0
            + d * (0.5 + d * (1.0 / 6.0 + d * (1.0 / 24.0 + d * (1.0 / 120.0 + d / 720.0)))));
    (excess + slope * tail, slope * derivative)
}

pub(crate) fn four_terminal_jacobian() -> Vec<f64> {
    CATALOG.four_terminal.to_vec()
}

pub(crate) fn pole_rate(bandwidth_hertz: f64) -> f64 {
    CATALOG.filter_rate * bandwidth_hertz
}

// Each call site has a bounded shared table and a per-worker last hit. Cache only
// coefficients derived from fixed physical parameters. Samples and operating points
// never enter the table.
macro_rules! coefficients {
    ($keys:expr, $value:expr, $n:literal, $t:ty) => {{
        type Entry = ([u64; $n], $t);
        std::thread_local! {
            static LAST: std::cell::Cell<Option<Entry>> = const { std::cell::Cell::new(None) };
        }
        static SHARED: std::sync::Mutex<Vec<Entry>> = std::sync::Mutex::new(Vec::new());
        let key = $keys.map(f64::to_bits);
        LAST.with(|slot| {
            if let Some((previous, value)) = slot.get()
                && key == previous
            {
                return value;
            }
            let mut entries = SHARED.lock().unwrap();
            let value = if let Some((_, value)) = entries.iter().find(|(k, _)| *k == key) {
                *value
            } else {
                let value = $value;
                if entries.len() == 64 {
                    entries.remove(0);
                }
                entries.push((key, value));
                value
            };
            slot.set(Some((key, value)));
            value
        })
    }};
}
pub(crate) use coefficients;
