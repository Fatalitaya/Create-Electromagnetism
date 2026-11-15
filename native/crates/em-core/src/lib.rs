//! Pure physical kernel using SI units, without JVM or world object references.
//!
//! Isolated RLC, fixed-frequency sources and diode DAE baseline with bounded sessions.
//! World integration is not implemented.

#![forbid(unsafe_code)]

pub mod model;
pub mod observation;
pub mod session;
pub mod solver;
pub mod state;
pub mod topology;
