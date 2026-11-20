#ifndef ELECTROMAGNETISM_NATIVE_H
#define ELECTROMAGNETISM_NATIVE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Mod metadata determines compatibility. Java exchanges batch requests through
 * NativeLinearBridge.exchangeBatch(byte[], boolean).
 */
/* Bit 0 advertises isolated circuit batches. The world adapter determines device coverage. */
uint64_t em_native_capabilities(void);

#ifdef __cplusplus
}
#endif

#endif
