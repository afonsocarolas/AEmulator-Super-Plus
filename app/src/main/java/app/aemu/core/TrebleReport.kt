/* AEmulator Super Plus, 2026-10-11: Android 8.x (Treble) boot diagnostics. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * Android 8.0+ firmware is not bootable yet: the engine (binderd + patched QEMU, prebuilt without source) emulates
 * /dev/binder only, while every Treble system also needs /dev/hwbinder (hwservicemanager and the HIDL HALs) and
 * /dev/vndbinder (vndservicemanager). Until that exists, the VM log at least records what this firmware expects,
 * so the point where boot stops can be matched against it.
 */
object TrebleReport {
    /** HALs whose absence stops an 8.x boot before the UI: SurfaceFlinger, then system_server, wait for them. */
    private val BOOT_CRITICAL = listOf(
        "android.hardware.configstore", "android.hardware.graphics.allocator",
        "android.hardware.graphics.composer", "android.hardware.power", "android.hardware.audio",
    )

    private val MANIFESTS = listOf("vendor/manifest.xml", "vendor/etc/vintf/manifest.xml", "system/manifest.xml")

    fun isHidl(s: GuestService): Boolean {
        val bin = s.argv.firstOrNull().orEmpty()
        return s.name == "hwservicemanager" || s.name == "vndservicemanager" ||
            bin.contains("/bin/hw/") || bin.substringAfterLast('/').startsWith("android.hardware.")
    }

    fun lines(api: Int, root: File, plan: List<GuestService>): List<String> {
        if (api < 26) return emptyList()
        val out = ArrayList<String>()
        out += "treble: Android 8+ (API $api) is experimental: /dev/hwbinder and /dev/vndbinder are served by the app " +
            "(libaemuhwb.so + HwBinderDaemon); the engine's /dev/binder still speaks only the pre-8.0 32-bit format"
        val vendor = File(root, "vendor/build.prop").isFile || File(root, "vendor/lib").isDirectory
        val manifest = MANIFESTS.firstOrNull { File(root, it).isFile }
        out += "treble: vendor partition ${if (vendor) "present" else "missing"}, VINTF manifest ${manifest?.let { "/$it" } ?: "missing"}"
        val hidl = plan.filter(::isHidl)
        out += "treble: ${hidl.size} HIDL service(s) in the boot plan" + if (hidl.isEmpty()) "" else ": " + hidl.joinToString { it.name }
        for (hal in BOOT_CRITICAL) if (hidl.none { it.argv.first().contains(hal) }) out += "treble: no $hal service (boot needs it)"
        return out
    }
}
