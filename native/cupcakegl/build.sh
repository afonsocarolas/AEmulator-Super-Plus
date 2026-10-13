#!/bin/sh
# AEmulator Super Plus, 2026-10-13: Android 1.5 (Cupcake) software renderer without runtime code generation.
#
# Cupcake's libpixelflinger writes ARM code for its pixel pipelines into a malloc()ed buffer and jumps into it
# (codeflinger/CodeCache.cpp). Real phones ran it because old binaries got READ_IMPLIES_EXEC; qemu-user does not
# make heap pages executable, so SurfaceFlinger (inside system_server) died with SIGSEGV on its first draw.
# This builds the same library from the Cupcake sources (system/core @ dd7bc33, March 2009, Apache-2.0) with
# AEMU_NO_CODEGEN: it keeps the C and hand-written assembly pipelines and never generates code. TreeFixer
# installs it in place of the firmware's copy on Android 1.5 only (the original is parked, not deleted).
set -eu
cd "$(dirname "$0")"
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || [ -x "$BIN/clang.exe" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$BIN/clang"
OUT=${1:-../../app/src/main/assets/engines/common/libpixelflinger_cupcake.so}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
P=aosp/libpixelflinger
TARGET="--target=armv7a-linux-androideabi16 -march=armv7-a -mfloat-abi=softfp -marm"
CFLAGS="$TARGET -O2 -fPIC -fno-exceptions -fno-rtti -fno-threadsafe-statics -fno-stack-protector -fno-unwind-tables
  -fno-asynchronous-unwind-tables -fstrict-aliasing -fomit-frame-pointer -DNDEBUG -DANDROID -DAEMU_NO_CODEGEN
  -D_FORTIFY_SOURCE=0 -include aosp/include/arch/linux-arm/AndroidConfig.h -Ishim -Iaosp/include -I$P -w"

for f in tinyutils/SharedBuffer.cpp tinyutils/VectorImpl.cpp fixed.cpp picker.cpp pixelflinger.cpp trap.cpp \
         scanline.cpp format.cpp clear.cpp raster.cpp buffer.cpp t32cb16blend.S rotate90CW_4x4_16v6.S; do
  case $f in *.cpp) std="-std=gnu++98 -include time.h" ;; *) std= ;; esac
  "$CC" $CFLAGS $std -c "$P/$f" -o "$TMP/$(echo "$f" | tr / _).o"
done
"$CC" $CFLAGS -c shim/pure_virtual.c -o "$TMP/pure_virtual.o"
# -fno-builtin: these wrappers must call libc, not be turned back into __aeabi_* calls
"$CC" $CFLAGS -fno-builtin -c shim/aeabi_mem.c -o "$TMP/aeabi_mem.o"

# link-time stand-ins: at run time the guest's own libcutils.so and liblog.so provide these
STUB="$TARGET -shared -nostdlib -fPIC -Wl,--hash-style=sysv -Wl,--build-id=none"
"$CC" $STUB -Wl,-soname,libcutils.so -o "$TMP/libcutils.so" shim/stub_cutils.c
"$CC" $STUB -Wl,-soname,liblog.so -o "$TMP/liblog.so" shim/stub_log.c

# only the compiler's plain helpers (__aeabi_idiv, __aeabi_memcpy...): the NDK's libgcc would also bring an unwinder that
# needs libdl's dl_unwind_find_exidx and the stack protector, which Cupcake's linker and libc may lack
BUILTINS=$(find "$BIN/../lib64/clang" -name libclang_rt.builtins-arm-android.a | head -n 1)
[ -n "$BUILTINS" ] || { echo "libclang_rt.builtins-arm-android.a not found in the NDK" >&2; exit 1; }

# Cupcake's linker: classic ELF hash table only, no RELRO
"$CC" $TARGET -shared -nostdlib -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined -Wl,--build-id=none \
  -Wl,--exclude-libs,ALL -Wl,-soname,libpixelflinger.so -o "$OUT" "$TMP"/*.o -L"$TMP" -lcutils -llog -lc "$BUILTINS"
"$BIN/llvm-strip" "$OUT"
echo "built $OUT"
