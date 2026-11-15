#ifndef ELECTROMAGNETISM_NATIVE_H
#define ELECTROMAGNETISM_NATIVE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Compatibility follows existing mod metadata, not independent protocol versions.
 * The batch entry point is Java NativeLinearBridge.exchange(byte[]).
 * No pointer-based C batch entry point is exposed yet. */
/* Bit 0: isolated circuit harness. No world-ready simulation capability. */
uint64_t em_native_capabilities(void);

#ifdef __cplusplus
}
#endif

#endif /* ELECTROMAGNETISM_NATIVE_H */
