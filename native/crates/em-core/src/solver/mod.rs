//! Numerical algorithms operating on the same constitutive device models.
//! Bounded sparse assembly and pivoted elimination; no world activation is implied.

pub mod ac;
pub mod dc;
pub mod linear;
pub mod newton;
pub mod sparse;
pub mod transient;
pub(crate) mod progress;

pub(crate) mod cooperative;

use em_protocol::contract::Diagnostic;

#[derive(Debug)]
pub(crate) enum SolveError {
    Numerical(Diagnostic),
    Permanent(Diagnostic),
}

impl SolveError {
    pub(crate) fn retryable(&self) -> bool {
        matches!(self, Self::Numerical(_))
    }

    pub(crate) fn diagnostic(&self) -> &Diagnostic {
        match self {
            Self::Numerical(diagnostic) | Self::Permanent(diagnostic) => diagnostic,
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
            SolveError::Numerical(diagnostic) | SolveError::Permanent(diagnostic) => diagnostic,
        }
    }
}

pub(crate) fn permanent(reason: &str) -> SolveError {
    SolveError::Permanent(crate::model::element::rejected(reason))
}
