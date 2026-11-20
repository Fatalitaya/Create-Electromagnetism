//! DC, transient and frequency-domain algorithms share device equations and sparse assembly.

pub mod ac;
pub mod dc;
pub mod linear;
pub mod newton;
pub mod sparse;
pub mod transient;
pub(crate) mod progress;
pub use progress::{last_advance_profile, profile_advance, AdvanceProfile};

pub(crate) mod cooperative;
pub use cooperative::with_helper;

use em_protocol::contract::Diagnostic;

#[derive(Debug)]
pub(crate) enum SolveError {
    Numerical(Diagnostic),
    Subdivide(Diagnostic),
    Permanent(Diagnostic),
}

impl SolveError {
    pub(crate) fn retryable(&self) -> bool {
        matches!(self, Self::Numerical(_) | Self::Subdivide(_))
    }

    pub(crate) fn diagnostic(&self) -> &Diagnostic {
        match self {
            Self::Numerical(diagnostic) | Self::Subdivide(diagnostic) | Self::Permanent(diagnostic) => diagnostic,
        }
    }
}

impl From<Diagnostic> for SolveError {
    fn from(diagnostic: Diagnostic) -> Self {
        Self::Numerical(diagnostic)
    }
}

impl From<SolveError> for Diagnostic {
    fn from(error: SolveError) -> Self {
        match error {
            SolveError::Numerical(diagnostic) | SolveError::Subdivide(diagnostic) | SolveError::Permanent(diagnostic) => diagnostic,
        }
    }
}

pub(crate) fn permanent(reason: &str) -> SolveError {
    SolveError::Permanent(crate::model::element::rejected(reason))
}
