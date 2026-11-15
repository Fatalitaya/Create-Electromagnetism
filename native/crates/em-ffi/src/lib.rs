//! Capability queries and Java 21 JNI thin bridge.
//!
//! Bounded JNI batches used by the world coordinator and circuit verification.
//! World device coverage is validated separately from native transport availability.

pub mod bridge;

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::jbyteArray,
};

/// Synchronously copies a bounded Java byte array; no Java address escapes this call.
// SAFETY: JNI export matches NativeLinearBridge.exchange(byte[]) exactly.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_electromagnetism_simulation_NativeLinearBridge_exchange(
    mut env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<jbyteArray> {
            let length = env.get_array_length(&input)?;
            let response = if length as usize > em_protocol::wire::MAX_BYTES {
                bridge::response(1, "Batch exceeds byte limit", &[])
            } else {
                let bytes = env.convert_byte_array(&input)?;
                bridge::dispatch(&bytes)
            };
            Ok(env.byte_array_from_slice(&response)?.into_raw())
        },
    ));
    match result {
        Ok(Ok(array)) => array,
        _ => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "Native JNI conversion failed",
                );
            }
            std::ptr::null_mut()
        }
    }
}

/// Bit zero enables the isolated circuit harness; no bit enables world simulation.
// SAFETY: This uniquely prefixed symbol matches the declaration in the C header.
#[unsafe(no_mangle)]
pub extern "C" fn em_native_capabilities() -> u64 {
    1 // Isolated circuit harness only. No world simulation capability.
}
