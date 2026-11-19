use super::{PI, Phasor};

impl Phasor {
    pub(super) fn at(self, phase: f64) -> f64 {
        self.real * phase.cos() - self.imaginary * phase.sin()
    }
    pub(super) fn peak(self) -> f64 {
        self.real.hypot(self.imaginary)
    }
    pub(super) fn minus(self, other: Self) -> Self {
        Self {
            real: self.real - other.real,
            imaginary: self.imaginary - other.imaginary,
        }
    }
    pub(super) fn integral(self, a: f64, b: f64, omega: f64) -> f64 {
        (self.real * (b.sin() - a.sin()) + self.imaginary * (b.cos() - a.cos())) / omega
    }
    pub(super) fn window_peak(self, a: f64, b: f64) -> f64 {
        let peak = (-self.imaginary).atan2(self.real);
        if ((a - peak) / PI).ceil() <= ((b - peak) / PI).floor() {
            self.peak()
        } else {
            self.at(a).abs().max(self.at(b).abs())
        }
    }
}

// Product of two fundamental signals, represented as DC + cos(2p) + sin(2p).
#[derive(Clone, Copy, Default)]
pub(super) struct Power {
    pub(super) dc: f64,
    pub(super) cos: f64,
    pub(super) sin: f64,
}
impl Power {
    pub(super) fn product(a: Phasor, b: Phasor) -> Self {
        Self {
            dc: (a.real * b.real + a.imaginary * b.imaginary) * 0.5,
            cos: (a.real * b.real - a.imaginary * b.imaginary) * 0.5,
            sin: -(a.imaginary * b.real + a.real * b.imaginary) * 0.5,
        }
    }
    pub(super) fn add(&mut self, other: Self) {
        self.dc += other.dc;
        self.cos += other.cos;
        self.sin += other.sin;
    }
    pub(super) fn integral(self, a: f64, b: f64, omega: f64) -> f64 {
        (self.dc * (b - a)
            + (self.cos * ((2. * b).sin() - (2. * a).sin())
                - self.sin * ((2. * b).cos() - (2. * a).cos()))
                * 0.5)
            / omega
    }
    pub(super) fn positive_negative(self, a: f64, b: f64, omega: f64) -> (f64, f64) {
        let amplitude = self.cos.hypot(self.sin);
        let mut cuts = vec![a, b];
        if self.dc.abs() < amplitude {
            let phi = self.sin.atan2(self.cos);
            let delta = (-self.dc / amplitude).acos();
            for root in [(phi - delta) / 2., (phi + delta) / 2.] {
                let mut t = root + ((a - root) / PI).ceil() * PI;
                while t < b {
                    if t > a {
                        cuts.push(t);
                    }
                    t += PI;
                }
            }
        }
        cuts.sort_by(f64::total_cmp);
        let mut pos = 0.;
        let mut neg = 0.;
        for pair in cuts.windows(2) {
            let work = self.integral(pair[0], pair[1], omega);
            pos += work.max(0.);
            neg += (-work).max(0.);
        }
        (pos, neg)
    }
    // Exact response of tau*y' + y = forcing to a DC plus second harmonic input.
    pub(super) fn filtered(self, old: f64, a: f64, b: f64, omega: f64, tau: f64) -> f64 {
        let k = 2. * omega * tau;
        let c = (self.cos - k * self.sin) / (1. + k * k);
        let s = (self.sin + k * self.cos) / (1. + k * k);
        let particular = |p: f64| self.dc + c * (2. * p).cos() + s * (2. * p).sin();
        particular(b) + (old - particular(a)) * (-(b - a) / (omega * tau)).exp()
    }
}
