/* Link-time stand-in for Cupcake's libcutils.so (atomic-android-arm.S, memset32.S); never shipped. */
#include <stdint.h>
#include <stddef.h>
int32_t android_atomic_inc(volatile int32_t* addr) { return 0; }
int32_t android_atomic_dec(volatile int32_t* addr) { return 0; }
void android_memset16(uint16_t* dst, uint16_t value, size_t size) {}
void android_memset32(uint32_t* dst, uint32_t value, size_t size) {}
