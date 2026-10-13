# cupcakegl: Android 1.5 software renderer without code generation

`libpixelflinger_cupcake.so` replaces `/system/lib/libpixelflinger.so` on Android 1.5 (API 3) systems only
(`TreeFixer.installCupcakeRenderer`, the firmware's copy is parked in `/system/.aemu-parked`).

Why: Cupcake's PixelFlinger generates ARM code for its pixel pipelines into a `malloc()`ed buffer and jumps
into it (`codeflinger/CodeCache.cpp`). Real phones of that time ran it because the kernel gave old binaries
`READ_IMPLIES_EXEC`; qemu-user does not make heap pages executable, so SurfaceFlinger (inside system_server on
1.5) crashed with SIGSEGV on its first draw.

Source: `aosp/` is AOSP `platform/system/core` at commit `dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0`
("auto import from //depot/cupcake/@135843", March 2009): `libpixelflinger/` and the headers it needs, under the
Apache License 2.0 (`aosp/libpixelflinger/NOTICE`, `MODULE_LICENSE_APACHE2`). Changes, all marked by
`AEMU_NO_CODEGEN` or listed here:

- `scanline.cpp`: `ANDROID_ARM_CODEGEN` is 0 when `AEMU_NO_CODEGEN` is defined; the code generator's headers
  are not included then (same in `pixelflinger.cpp`). The C and hand-written assembly pipelines are unchanged.
- `t32cb16blend.S`: `ldmlofd` spelled `ldmfdlo` (same instruction, syntax clang's assembler accepts).
- `codeflinger/` is not compiled.

`shim/` holds `utils/Endian.h` (Cupcake's only included `<endian.h>`), the ARM ABI memory helpers and
`__cxa_pure_virtual` that Cupcake's libc/libstdc++ lack, and link-time stand-ins for `libcutils.so`/`liblog.so`
(not shipped: the guest's own are used at run time).

Build: `NDK=/path/to/ndk/22.1.7171670 sh build.sh` (writes `app/src/main/assets/engines/common/`).
