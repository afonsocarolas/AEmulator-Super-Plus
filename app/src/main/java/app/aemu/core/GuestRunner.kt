/* Modified for AEmulator Super Plus, 2026-10-11: libaemuhwb.so preload for Android 8+. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Собирает командную строку и окружение гостевого процесса:
 *   dhdrun <область свойств> 30 qemu-arm -L <root> -0 <argv0> <root/argv0> args…
 * dhdrun отображает область свойств на fd 30 (ANDROID_PROPERTY_WORKSPACE), создаёт сокеты init
 * (DHD_SOCK_<имя>=путь,права → ANDROID_SOCKET_<имя>) и переносит DHD_ENV_X в X перед exec qemu.
 */
class GuestRunner(
    val paths: VmPaths,
    val img: GuestImage,
    val net: NetConfig = NetConfig(),
) {
    data class NetConfig(val http80: Int = 0, val https443: Int = 0, val dns53: Int = 0, val glPath: String? = null)

    var qemuLog = false
    var qemuStrace = false
    var tbFlush = 0
    var noSmc = false
    var noTcgOpt = false
    /** qemu presents the touchscreen as single-touch (BTN_TOUCH + ABS_X/ABS_Y): Android 1.5 knows no multitouch */
    var singleTouch = CupcakeCompat.applies(img.api)
    var binderVerbose = false
    var sdcardHost: File? = null
    /** user's extra qemu options from firmware settings: KEY=VALUE tokens become env vars, the rest go to qemu */
    var userQemuArgs: String = ""

    private val userTokens: List<String> get() = Regex("""("[^"]*"|\S+)""").findAll(userQemuArgs).map { it.value.trim('"') }.toList()
    private fun isEnvToken(t: String) = !t.startsWith("-") && Regex("^[A-Z_][A-Z0-9_]*=").containsMatchIn(t)

    val engine: Engine get() = img.engine
    val qemu: File get() = paths.nativeBin(engine.qemu)
    val runner: File get() = paths.nativeBin(engine.runner)

    fun cmdline(argv: List<String>, propsFile: File? = null): List<String> {
        val prog = argv.first()
        val host = File(paths.root, prog.removePrefix("/")).absolutePath
        val cmd = ArrayList<String>()
        val props = propsFile ?: paths.props
        if (runner.canExecute() && props.isFile) {
            cmd += runner.absolutePath; cmd += props.absolutePath; cmd += "30"
        }
        cmd += qemu.absolutePath
        cmd += listOf("-L", paths.root.absolutePath, "-0", prog)
        if (qemuLog || qemuStrace) {
            if (qemuLog) cmd += listOf("-d", "unimp,guest_errors")
            if (qemuStrace) cmd += "-strace"
            cmd += listOf("-D", File(paths.bin, "qemu-${prog.substringAfterLast('/')}.log").absolutePath)
        }
        cmd += userTokens.filterNot(::isEnvToken)
        cmd += host
        cmd += argv.drop(1)
        return cmd
    }

    fun env(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val s = img.settings
        val e = LinkedHashMap<String, String>()
        // переменные из init.rc прошивки (export …), кроме тех, что задаём сами
        for ((k, v) in img.exports) if (k !in EXPORTS_SKIP) e[k] = v
        e["PATH"] = img.exports["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        e["DHD_ENV_LD_LIBRARY_PATH"] = img.exports["LD_LIBRARY_PATH"] ?: "/vendor/lib:/system/lib"
        // ashmem через memfd (движок) + наша прослойка от запрещённых в песочнице вызовов (mount, reboot…).
        // glibc firmware (Google TV) cannot load the bionic-linked libaemushim.so (DT_NEEDED libc.so): ashmem shim only.
        e["DHD_ENV_LD_PRELOAD"] = GuestPreload.value(GuestPreload.isGlibcFirmware(paths.root), treble = img.api >= 26)
        e["ANDROID_ROOT"] = img.exports["ANDROID_ROOT"] ?: "/system"
        e["ANDROID_DATA"] = img.exports["ANDROID_DATA"] ?: "/data"
        e["ANDROID_ASSETS"] = img.exports["ANDROID_ASSETS"] ?: "/system/app"
        e["EXTERNAL_STORAGE"] = img.exports["EXTERNAL_STORAGE"] ?: img.sdcardPath
        e["ASEC_MOUNTPOINT"] = img.exports["ASEC_MOUNTPOINT"] ?: "/mnt/asec"
        e["LOOP_MOUNTPOINT"] = img.exports["LOOP_MOUNTPOINT"] ?: "/mnt/obb"
        e["BOOTCLASSPATH"] = img.bootclasspath
        // 4.2+: зигота монтирует карту для каждого пользователя и без этих переменных роняет приложения
        // 5.0+: Environment.isExternalStorageEmulated() ищет путь /storage/emulated/<user> среди томов MountService (storage_list.xml).
        // У прошивки с единственным физическим томом (SDK/goldfish: /storage/sdcard) такого тома нет, и ActivityManager не может
        // запустить ни один процесс приложения ("Failed to find storage device at /storage/emulated/0"), так что эти переменные не задаём.
        val ext = img.exports["EXTERNAL_STORAGE"]
        val physicalOnly = img.api >= 21 && ext != null && !ext.startsWith("/storage/emulated")
        if (img.api >= 17 && !physicalOnly) {
            e.putIfAbsent("EMULATED_STORAGE_SOURCE", "/mnt/shell/emulated")
            e.putIfAbsent("EMULATED_STORAGE_TARGET", "/storage/emulated")
        }
        e["HOME"] = "/data"
        e["TMPDIR"] = "/data/local/tmp"
        e["ASHMEM_SHIM_DIR"] = "/data/local/tmp"
        e["TZ"] = "UTC"
        // uname -r / os.version: qemu-user reports this release instead of the host kernel's
        if (img.settings.kernel.isNotBlank()) e["QEMU_UNAME"] = VmSettings.kernelRelease(img.settings.kernel)
        e["DHD_FB_W"] = s.width.toString()
        e["DHD_FB_H"] = s.height.toString()
        e["DHD_IN_W"] = s.width.toString()
        e["DHD_IN_H"] = s.height.toString()
        e["DHD_FB_HZ"] = s.fbHz.toString()
        e["DHD_OWNERS"] = paths.owners.absolutePath
        e["DHD_NET_LOG"] = File(paths.bin, "net.log").absolutePath
        e["DHD_ASHMEM_LOG"] = File(paths.bin, "ashmem.log").absolutePath
        e["DHD_BINDER"] = paths.binderSock.absolutePath
        e["DHD_BINDER_LOG"] = File(paths.bin, "binder-warn.log").absolutePath
        e["DHD_CREDS"] = paths.creds.absolutePath
        e["DHD_INPUT"] = paths.inputSock.absolutePath
        if (singleTouch) e["DHD_INPUT_ST"] = "1"
        if (binderVerbose) e["DHD_BINDER_VERBOSE"] = "1"
        sdcardHost?.let { e["DHD_SDCARD"] = it.absolutePath }
        if (net.http80 > 0) e["DHD_HTTP80"] = net.http80.toString()
        if (net.https443 > 0) e["DHD_HTTPS443"] = net.https443.toString()
        if (net.dns53 > 0) {
            e["DHD_DNS53"] = net.dns53.toString()
            e["DHD_ENV_ANDROID_DNS_MODE"] = "local"
        }
        net.glPath?.let { e["DHD_GL"] = it }
        // GL-мост отдаёт гостю ES 3.0, но hwui ≤4.3 и загрузка текстур с шагом строки (ROW_LENGTH)
        // через мост не работают — для них сообщаем ES 2.0. На Adreno 4.4 с ES 3 и PBO работает правильно.
        if (img.api < 19 || !HostInfo.gpu().contains("adreno")) e["DHD_GL3"] = "0"
        // 4.3+: SurfaceFlinger (GLConsumer) re-targets its EGLImages itself; forcing it again blanks the screen
        if (img.api >= 18) { e["AEMU_GL_RETARGET"] = "0"; e["AEMU_GL_CLIENT_ARRAYS"] = "0" }
        if (tbFlush > 0) e["DHD_TBFLUSH"] = tbFlush.toString()
        if (noSmc) e["DHD_NO_SMC"] = "1"
        if (noTcgOpt) e["DHD_NO_TCGOPT"] = "1"
        for (t in userTokens.filter(::isEnvToken)) e[t.substringBefore('=')] = t.substringAfter('=')
        e.putAll(extra)
        return e
    }

    /** Одноразовый запуск гостевой команды (am, pm, settings, sh -c …) с ожиданием вывода. */
    fun run(argv: List<String>, timeoutMs: Long = 120_000, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        if (!qemu.canExecute()) return -1 to "no qemu"
        val pb = ProcessBuilder(cmdline(argv)).directory(paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env(extra))
        val p = pb.start()
        val sb = StringBuilder()
        val t = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { if (sb.length < 256_000) sb.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        t.join(2000)
        val code = runCatching { p.exitValue() }.getOrDefault(-9)
        return code to sb.toString()
    }

    companion object {
        /** PATH for an interactive/adb shell: /system/bin and /system/xbin first (the firmware's /sbin-first PATH breaks pm/am) */
        fun shellPath(path: String?): String {
            val rest = (path ?: "").split(':').filter { it.isNotEmpty() && it != "/system/bin" && it != "/system/xbin" }
            return (listOf("/system/bin", "/system/xbin") + rest).joinToString(":")
        }

        /**
         * qemu cannot exec a script (even with #!: "Error while loading .../bin/pm: Exec format error"), so typing
         * `pm`, `am`, `input`... in the shell failed. Shell functions run them through `sh <script>` instead.
         * Only for files that are really scripts (no ELF magic) and only in the shell the user types into.
         */
        fun scriptFns(root: File): String {
            val sb = StringBuilder()
            for (n in listOf("am", "pm", "input", "monkey", "svc", "ime", "bmgr", "wm", "content", "settings")) {
                val f = File(root, "system/bin/$n")
                if (!f.isFile) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(4).also { b -> s.read(b) } } }.getOrNull() ?: continue
                if (head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte()) continue
                sb.append(n).append("() { /system/bin/sh /system/bin/").append(n).append(" \"\$@\"; }; ")
            }
            return sb.toString()
        }

        private val EXPORTS_SKIP = setOf("PATH", "LD_LIBRARY_PATH", "BOOTCLASSPATH", "LD_PRELOAD")
    }
}
