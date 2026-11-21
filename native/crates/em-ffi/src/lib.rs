//! The Java 21 JNI batch entry point serves both the world coordinator and standalone checks.

pub mod bridge;

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::jbyteArray,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_electromagnetism_simulation_NativeLinearBridge_initializeCatalog(
    _env: JNIEnv, _class: JClass,
) {
    em_core::model::catalog::initialize();
}

/// Check length before copying the Java byte array synchronously. JNI references remain local to
/// this call.
// SAFETY: The export name, system ABI and parameters match
// NativeLinearBridge.exchangeBatch(byte[], boolean).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_electromagnetism_simulation_NativeLinearBridge_exchangeBatch(
    mut env: JNIEnv,
    _class: JClass,
    input: JByteArray,
    helper: jni::sys::jboolean,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<jbyteArray> {
            let length = env.get_array_length(&input)?;
            let response = if length as usize > em_protocol::wire::MAX_BYTES {
                bridge::response(1, "Batch exceeds byte limit", &[])
            } else {
                let bytes = env.convert_byte_array(&input)?;
                em_core::solver::with_helper(helper != 0, || bridge::dispatch(&bytes))
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

/// Bit 0 advertises the isolated circuit batch capability. The integration layer determines
/// world-device coverage.
// SAFETY: The unique export name and C ABI match electromagnetism_native.h.
#[unsafe(no_mangle)]
pub extern "C" fn em_native_capabilities() -> u64 {
    1 // Bit 0 selects the circuit batch capability.
}
