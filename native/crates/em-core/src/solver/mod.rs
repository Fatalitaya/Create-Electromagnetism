//! Numerical algorithms operating on the same constitutive device models.
//! Bounded sparse assembly and pivoted elimination; no world activation is implied.

pub mod ac;
pub mod dc;
pub mod linear;
pub mod newton;
pub mod sparse;
pub mod transient;
pub(crate) mod progress;

/// These failures describe resources, immutable inputs, or the clock rather
/// than a transient Newton candidate. A shorter step cannot repair them.
pub(crate) fn retry_with_smaller_step(error: &em_protocol::contract::Diagnostic) -> bool {
    let reason = error.reason.as_str();
    !(reason.starts_with("Missing ")
        || reason.starts_with("Unknown ")
        || reason.starts_with("Invalid sparse ")
        || (reason.starts_with("Sparse ")
            && (reason.contains("resource limit") || reason.contains("work budget")))
        || matches!(reason,
            "Step cannot be bisected at floating-point resolution"
            | "Step end must advance finite physical time"
            | "Waveform clock exceeds time resolution"
            | "Waveform sample clock exhausted"
            | "Waveform overflow counter exhausted"
            | "Waveform candidate crossed an output boundary"))
}
