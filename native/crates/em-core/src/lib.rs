//! Solve the electrical, thermal and electromechanical equations in SI units. Java supplies world
//! inputs and delivers accepted results through JNI.

#![forbid(unsafe_code)]

pub mod model;
pub mod observation;
pub mod session;
pub mod solver;
pub mod state;
pub mod topology;
