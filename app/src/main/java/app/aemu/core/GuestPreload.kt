/* Modified for AEmulator Super Plus, 2026-10-11: libaemuhwb.so for Android 8+. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * LD_PRELOAD of the guest programs.
 *
 * libaemushim.so is built against bionic (DT_NEEDED libc.so, undefined __errno@LIBC), so it cannot be loaded by a glibc
 * userland: Google TV firmware (Marvell Berlin, e.g. Hisense GX1200V) links its native binaries against /lib/ld-linux.so.3
 * + libc.so.6 and has no libc.so, and every service died with
 * "error while loading shared libraries: libc.so: cannot open shared object file". libashmemshim.so has no
 * dependencies and works with either.
 */
object GuestPreload {
    const val ASHMEM = "/system/lib/libashmemshim.so"
    const val SHIM = "/system/lib/libaemushim.so"
    /** glibc-only: emulates the Marvell /dev/shm_cache + /dev/shm_noncache devices (see shm_shim.c) */
    const val SHMSHIM = "/system/lib/libshmshim.so"
    /** Android 8+: /dev/hwbinder and /dev/vndbinder (hwbinder.c); first, so its open/ioctl/mmap see the calls before the other shims */
    const val HWB = "/system/lib/libaemuhwb.so"

    /** glibc dynamic loader in the tree and no bionic linker */
    fun isGlibcFirmware(root: File): Boolean =
        File(root, "lib/ld-linux.so.3").exists() && !File(root, "system/bin/linker").exists()

    fun value(glibc: Boolean, treble: Boolean = false): String = when {
        glibc -> "$ASHMEM:$SHMSHIM"
        treble -> "$HWB:$ASHMEM:$SHIM"
        else -> "$ASHMEM:$SHIM"
    }
}
