//! Transaction contracts shared by Java and Rust. Mod metadata determines compatibility.

#![forbid(unsafe_code)]

pub mod contract;
pub mod wire;

/// Read mod metadata from gradle.properties at build time.
pub const MOD_VERSION: &str = env!("ELECTROMAGNETISM_MOD_VERSION");
