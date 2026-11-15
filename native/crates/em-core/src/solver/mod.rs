//! Numerical algorithms operating on the same constitutive device models.
//! Bounded sparse assembly and pivoted elimination; no world activation is implied.

pub mod ac;
pub mod dc;
pub mod linear;
pub mod newton;
pub mod sparse;
pub mod transient;
