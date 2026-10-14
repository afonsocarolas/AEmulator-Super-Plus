/* AEmulator Super Plus, 2026-10-13: Android 1.5 (Cupcake) support; 2026-10-14: 1.6 (Donut). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * Android 1.5 (API 3: HTC Dream / ADP1, Magic, Hero...) predates the pieces the engine's graphics path plugs into: the
 * EGL driver loader that reads /system/lib/egl (Donut) and the gralloc HAL (Eclair). Cupcake's SurfaceFlinger runs inside
 * system_server and draws itself into /dev/graphics/fb0 as RGB565, which qemu's framebuffer emulation and the app's
 * software screen path already handle.
 *
 * What Cupcake still needs (checked against the Cupcake sources, frameworks/base @ 9066cfe, March 2009):
 *  - its EGL wrapper loads the software renderer libagl.so and then the vendor one, libhgl.so (Qualcomm on the Dream),
 *    unless debug.egl.hw is 0 (opengl/libs/EGL/egl.cpp); the vendor driver wants the phone's /dev/hw3d and pmem;
 *  - the in-app GPU bridge never gets a client (nothing loads /system/lib/egl), so the screen must come from fb0;
 *  - no density support yet (1.6): the phones were HVGA, 320x480 at 160 dpi;
 *  - no multitouch (2.0): EventHub only takes a device with BTN_TOUCH + ABS_X/ABS_Y as a touchscreen, and
 *    KeyInputQueue reads ABS_X/ABS_Y/ABS_PRESSURE, so the screen is reported and driven as single-touch.
 *
 * Android 1.6 (API 4, Donut) shares the rest: its libpixelflinger is the same source (only the code generator's
 * headers differ, checked against android-1.6_r1) with the same malloc()ed code cache, multitouch only came with 2.0,
 * and it also runs with software graphics: with the GPU bridge its screen stayed black once the renderer stopped
 * crashing, while Cupcake's software path shows the UI.
 */
object CupcakeCompat {
    const val MAX_API = 3

    fun applies(api: Int) = api in 1..MAX_API

    /** Android 1.5-1.6: the engine's PixelFlinger build and a single-touch screen */
    const val MAX_PRE_ECLAIR_API = 4
    fun preEclair(api: Int) = api in 1..MAX_PRE_ECLAIR_API

    /** Settings a 1.5/1.6 system runs with, whatever an earlier import or the settings screen stored. */
    fun adjust(img: GuestImage): GuestImage {
        if (!preEclair(img.api)) return img
        val s = img.settings
        val next = s.copy(gpu = false, hwui = false, mtMode = SINGLE_TOUCH)
        return if (next != s) img.copy(settings = next) else img
    }

    /** InputService.mtMode for ABS_X/ABS_Y/ABS_PRESSURE + BTN_TOUCH events */
    const val SINGLE_TOUCH = 4

    /** Settings for a newly imported Cupcake firmware. */
    fun importDefaults(s: VmSettings): VmSettings = s.copy(width = 320, height = 480, density = 160, gpu = false, hwui = false, mtMode = SINGLE_TOUCH)

    /** Boot properties on top of the usual ones. */
    fun props(api: Int): Map<String, String> = if (applies(api)) mapOf("debug.egl.hw" to "0") else emptyMap()

    /**
     * When zygote dies before the first boot completes: what the guest said about it. On every Android up to 4.x zygote
     * kills itself (SIGKILL, exit 137) as soon as system_server dies, so the host only sees that. [recs] is the end of the
     * guest log; [processOut] the end of zygote's own stdout/stderr (run/zygote.log), shared with the system_server it
     * forked: with no debuggerd running, qemu's "uncaught target signal" there is the only trace of a native crash.
     */
    fun crashSummary(recs: List<GuestLog.Rec>, processOut: List<String> = emptyList(), max: Int = 20): List<String> {
        val out = ArrayList<String>()
        // zygote's own words: "Process 91 terminated by signal (11)", "Exit zygote because system server (91) has terminated"
        val zygote = recs.filter { it.tag == "Zygote" && (it.msg.contains("terminated by signal") || it.msg.contains("exited cleanly") ||
            it.msg.contains("Exit zygote")) }.takeLast(3).map(::line)
        // a Java crash in system_server: the exception and the first frames of its stack
        val fatal = recs.indexOfLast { it.msg.contains("FATAL EXCEPTION IN SYSTEM PROCESS") }
        if (fatal >= 0) recs.drop(fatal).filter { it.tag == "AndroidRuntime" }.take(max).forEach { out += line(it) }
        // a native crash reported by debuggerd (signal, fault address, backtrace), when the firmware runs one
        val native = recs.indexOfLast { it.tag == "DEBUG" && it.msg.contains("signal ") }
        if (native >= 0) recs.drop(native).filter { it.tag == "DEBUG" }.take(max).forEach { out += line(it) }
        // the guest shim's crash report (aemu_crash): what the guest logged just before it, the signal, pc and lr
        val shim = recs.indexOfLast { it.tag == "aemu_crash" && it.msg.startsWith("*** SIG") }
        if (out.isEmpty() && shim >= 0) {
            recs.take(shim).filter { it.tag != "aemu_crash" }.takeLast(6).forEach { out += line(it) }
            recs.drop(shim).take(5).filter { it.tag == "aemu_crash" && !it.msg.startsWith("thread") && !it.msg.startsWith("maps") }.forEach { out += line(it) }
        }
        // otherwise everything the guest logged last before zygote noticed, whatever its level
        if (out.isEmpty()) {
            val end = recs.indexOfLast { it.tag == "Zygote" && it.msg.contains("Exit zygote") }.let { if (it < 0) recs.size else it }
            recs.take(end).takeLast(max).forEach { out += line(it) }
        }
        // zygote's own output: the program's last words before a crash report ("app_process: Unable to ..."), qemu's verdict
        val crashAt = processOut.indexOfLast { it.startsWith("*** SIG") }
        val said = if (crashAt > 0) processOut.take(crashAt).filter { it.isNotBlank() && !it.startsWith("dhdrun:") && !it.startsWith("===") }.takeLast(3)
            else emptyList()
        val qemu = processOut.filter { it.contains("uncaught target signal") || it.contains("Segmentation") || it.contains("Illegal instruction") }.takeLast(2)
        return (zygote + out).distinct() + (said + qemu).distinct().map { "zygote output: ${it.trim().take(220)}" }
    }

    private fun line(r: GuestLog.Rec) = "${r.prio}/${r.tag}: ${r.msg.trim().take(220)}"
}
