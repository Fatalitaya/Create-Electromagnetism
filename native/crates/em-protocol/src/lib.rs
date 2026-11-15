//! Shared protocol contracts, independent of Minecraft, JNI, and solver details.
//!
//! Compatibility follows the existing mod metadata, with no independent protocol versions.

#![forbid(unsafe_code)]

pub mod contract;
pub mod wire;

/// Derived at build time from gradle.properties, the existing mod metadata source.
pub const MOD_VERSION: &str = env!("ELECTROMAGNETISM_MOD_VERSION");
