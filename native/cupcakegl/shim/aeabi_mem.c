/* AEmulator Super Plus: the ARM run-time ABI memory helpers clang emits. Cupcake's libc (built with GCC 4.2, which
 * called memcpy & co. directly) does not export them, so they live inside the library. Note __aeabi_memset's
 * argument order: (dest, n, c). */
#include <stddef.h>
#include <string.h>
#define H __attribute__((visibility("hidden")))
H void __aeabi_memcpy(void* d, const void* s, size_t n) { memcpy(d, s, n); }
H void __aeabi_memcpy4(void* d, const void* s, size_t n) { memcpy(d, s, n); }
H void __aeabi_memcpy8(void* d, const void* s, size_t n) { memcpy(d, s, n); }
H void __aeabi_memmove(void* d, const void* s, size_t n) { memmove(d, s, n); }
H void __aeabi_memmove4(void* d, const void* s, size_t n) { memmove(d, s, n); }
H void __aeabi_memmove8(void* d, const void* s, size_t n) { memmove(d, s, n); }
H void __aeabi_memset(void* d, size_t n, int c) { memset(d, c, n); }
H void __aeabi_memset4(void* d, size_t n, int c) { memset(d, c, n); }
H void __aeabi_memset8(void* d, size_t n, int c) { memset(d, c, n); }
H void __aeabi_memclr(void* d, size_t n) { memset(d, 0, n); }
H void __aeabi_memclr4(void* d, size_t n) { memset(d, 0, n); }
H void __aeabi_memclr8(void* d, size_t n) { memset(d, 0, n); }
