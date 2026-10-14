/* Modified for AEmulator Sunset through 2026-10-03: motion and boot-media bridges,
 * live library status; 2026-10-04: native boot of framework-less MMI/FTM builds.
 * Modified for AEmulator Super Plus, 2026-10-11: Android 8.x Treble boot report, host microphone.
 * GPL-3.0; upstream attribution retained in NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.os.Process as AProcess
import android.os.SystemClock
import android.view.Surface
import dev.lk.dhd.BinderSlot
import dev.lk.dhd.GlSlot
import dev.lk.dhd.Slot
import dev.lk.m7sense.GlBridge
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList

/** Daemons system_server cannot live without: restarted even when init.rc marks them oneshot
 * (MIUI installd dies in the LBE security hook on start). */
private val ALWAYS_RESTART = setOf("mediaserver", "installd", "netd", "keystore")

/**
 * Одна запущенная виртуальная машина. Живёт в процессе :vm (одна на процесс: GL-мост нельзя
 * поднять дважды), заменяет собой init: готовит дерево, поднимает службы хоста (свойства, ввод,
 * звук, заглушки радио и накопителя), binderd, GL-мост и службы гостя в порядке загрузки Android.
 */
class GuestVm(val ctx: Context, val img: GuestImage, val lowPowerBoot: Boolean = false) {
    enum class State { STOPPED, PREPARING, BOOTING, RUNNING, FAILED, STOPPING }

    val paths = VmPaths(ctx, img.id)
    val settings = img.settings
    /** Factory-test (MMI/FTM) build: no Java framework, only a native test UI. Booted like the charging class. */
    val nativeBoot: Boolean by lazy { !lowPowerBoot && MmiBoot.frameworkless(paths.root) && MmiBoot.service(paths.root) != null }
    /** The guest draws into fb0 itself (double-buffered minui) instead of going through SurfaceFlinger. */
    val nativeUi: Boolean get() = lowPowerBoot || nativeBoot
    private val storageLease = VmStorageLease.forVm(ctx.filesDir)
    val engine get() = img.engine

    @Volatile var state = State.STOPPED
        private set
    @Volatile var bootAt = 0L
        private set
    @Volatile var bootDoneAt = 0L
    private val noteBoot = OneTimeVmNote()
    @Volatile var pendingNote = img.oneTimeNote
        private set
    /** Guest reported boot completion; this does not prove that its launcher rendered. */
    @Volatile private var everBooted = false
    @Volatile private var zygoteRestarts = 0
    @Volatile private var imeRetried = false
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var surface: Surface? = null
        set(v) { field = v; if (engine == Engine.KK) GlBridge.surface(v) }

    private val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val logFile = File(paths.bin, "aemu.log")

    fun log(s: String) {
        val line = "${stamp()} $s"
        lines.add(line)
        while (lines.size > 2000) lines.removeAt(0)
        runCatching { logFile.appendText(line + "\n") }
        listeners.forEach { runCatching { it(line) } }
    }
    fun lines(): List<String> = lines.toList()
    fun onLog(l: (String) -> Unit) { listeners.add(l) }
    fun onState(l: (State) -> Unit) { stateListeners.add(l) }
    private val liveStatus = LiveVmStatus(ctx.filesDir)
    private fun setState(s: State) {
        state = s
        runCatching { liveStatus.publish(img.id, VmUiPolicy.active(s.name)) }
            .onFailure { log("library status: ${it.message}") }
        stateListeners.forEach { runCatching { it(s) } }
    }

    val props = PropService(paths, ::log)
    /** ADB over the LAN, switched on from the VM menu */
    val adb = AdbServer(this)
    val input = InputService(paths, ::log)
    private val vibration = VibrationBridge(ctx, paths, { settings.vibration }, ::log)
    private val battery = HostBatteryBridge(ctx, paths.root, img.api, settings.hostBattery,
        { everBooted && state == State.RUNNING && !stopping && !recoveryMode && !lowPowerBoot },
        { zygoteRestarts }, { command ->
            val result = guestRunner.run(listOf("/system/bin/sh", "-c", command), 4_000)
            check(result.first == 0) { "Guest battery update failed: ${result.first}" }
        }, ::log)
    fun setManualBattery(percent: Int, charging: Boolean) = battery.applyManual(percent, charging)
    fun resetBattery() = battery.reset(settings.hostBattery)
    val hostBatteryLevel get() = battery.latest?.percent ?: 80
    val hostBatteryCharging get() = battery.latest?.status in listOf(2, 5)
    private val motion = MotionBridge(ctx, paths, { img.api in 9..25 && !recoveryMode && !nativeUi }, { settings.motionSensors }, { settings.gyroLock }, ::log)
    fun motionVisible(visible: Boolean) { motion.visible(visible) }
    fun simulateRotation() = motion.simulateRotation()
    val cameraSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val camera = HostCameraBridge(ctx, paths, { settings.camera && cameraSupported }, ::log)
    /** Guest microphone input (audio HAL, 4.0-7.x): the phone's microphone while the VM is on screen, else silence. */
    val micSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val mic = HostMicBridge(ctx, paths, { settings.microphone && micSupported }, ::log)
    fun cameraVisible(visible: Boolean) { camera.visible(visible); mic.visible(visible) }
    val bluetoothSupported get() = engine == Engine.KK && img.api in 9..25 && !recoveryMode && !nativeUi
    private val bluetooth = BluetoothBridge(ctx, paths, { settings.bluetooth && bluetoothSupported }, ::log)
    val wifiSupported get() = WifiEmu.supported(engine, img.api, recoveryMode, nativeUi)
    // 2.x пишет в /dev/eac через AudioHardwareGeneric на 44,1 кГц, HAL 4.x движка — на 48 кГц
    val audio = AudioOut(paths, ::log, if (img.api < 14) 44100 else AudioOut.RATE,
        if (TreeFixer.isMtkAudio(paths.root)) "dev/aemu_pcm" else "dev/eac")
    val ril = RilStub(paths, ::log, img.settings.imei.ifBlank { VmSettings.DEFAULT_IMEI }, img.api,
        VmSettings.cleanBaseband(img.settings.baseband).ifBlank { VmSettings.DEFAULT_BASEBAND },
        img.settings.networkType, img.settings.sim)
    val vold = VoldStub(paths, img.sdcardPath, ::log, others = img.volumes)
    var onFrame: (() -> Unit)? = null
    val frames = FrameBell(paths, ::log) { onFrame?.invoke() }
    private val events = EventsSink(paths, ::log)
    private val logd = LogdSink(paths, ::log)
    /** Android 8+: /dev/hwbinder and /dev/vndbinder (the engine has /dev/binder only), served to libaemuhwb.so */
    private val hwbinder = HwBinderDaemon(paths.socket("aemu_hwbinder"), ::log)
    @Volatile private var lmk: GuestLmk? = null
    val net = NetProxy(ctx, paths, ::log)
    private val dns = DnsProxy(paths, img.api, ::log)
    val runner by lazy { GuestRunner(paths, img.copy(settings = settings)) }

    private val extraStubs = ArrayList<VoldStub>()
    private val procs = LinkedHashMap<String, Process>()
    private val pgids = LinkedHashSet<Int>()
    @Volatile private var stopping = false

    // ------------------------------------------------------------------ загрузка

    @Synchronized fun boot() {
        if (state == State.BOOTING || state == State.RUNNING || state == State.PREPARING) return
        try { storageLease.acquire() } catch (t: Throwable) {
            failure = "VM storage is busy: ${t.message}"
            log(failure!!)
            setState(State.FAILED)
            return
        }
        stopping = false
        failure = null
        setState(State.PREPARING)
        try {
            if (recoveryMode) doRecovery() else if (lowPowerBoot) doCharging() else if (nativeBoot) doMmi() else doBoot()
        } catch (t: Throwable) {
            vibration.stop()
            battery.stop()
            camera.stop()
            mic.stop()
            bluetooth.stop()
            motion.stop()
            failure = t.message ?: t.toString()
            log("✖ boot aborted: $failure")
            setState(State.FAILED)
        }
    }

    /** Boot the firmware's recovery (RecoveryImage) instead of Android. */
    @Volatile var recoveryMode = false
    /** recovery framebuffer format for the screen view: 0 RGB565, 1 RGBA/RGBX_8888, 2 BGRA_8888 */
    @Volatile var recoveryFormat = 0

    /** Native firmware charging class only: no Android framework or normal boot plan. */
    private fun doCharging() {
        paths.bin.mkdirs()
        logFile.writeText("")
        log("Offline charging boot: ${img.name}")
        val services = ChargingBoot.services(paths.root, img.api)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        battery.start()
        File(paths.root, "dev/tty0").delete() // minui cannot use host VT ioctls
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        if (!props.prepare(ChargingBoot.properties + ("ro.aemu.host" to "qemu-user"))) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        props.onCtl = { start, name ->
            services.firstOrNull { it.name == name }?.let { service ->
                if (start && !stopping) startService(ChargingBoot.runtimeService(paths.root, service))
                else synchronized(procs) { procs.remove(name) }?.destroyForcibly()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        input.mtMode = settings.mtMode
        input.serve()
        frames.serve()
        power.writeText("")
        bootAt = System.currentTimeMillis()
        setState(State.BOOTING)
        services.forEach { service ->
            if (stopping) return
            startService(ChargingBoot.runtimeService(paths.root, service))
        }
        setState(State.RUNNING)
        log("Charging services started: ${services.joinToString { it.name }}; Android framework not started")
        Thread({
            val renderers = services.filter { it.name != "healthd-charger" }.ifEmpty { services }
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val exited = renderers.firstOrNull { service -> synchronized(procs) { procs[service.name]?.isAlive != true } }
                if (exited != null && !stopping) {
                    failure = "Charging service ${exited.name} exited. Check its host log; vendor hardware support may be missing."
                    log(failure!!); setState(State.FAILED); break
                }
            }
        }, "charging-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Framework-less factory build (ZTE MMI, Qualcomm FFBM…): run just its native test program. Same recipe as
     * doCharging — fb0 and input come from the host; no zygote and no SurfaceFlinger, but binderd + servicemanager run because vendor libs link libbinder.
     */
    private fun doMmi() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, native MMI/FTM boot (no Android framework)")
        val service = MmiBoot.service(paths.root) ?: error("no MMI/FTM test program found in this firmware")
        val qemu = paths.nativeBin(engine.qemu)
        if (!qemu.canExecute()) error("translator ${engine.qemu} is not executable")
        val others = MmiBoot.controllable(paths.root)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        File(paths.root, "dev/tty0").delete() // minui cannot use host VT ioctls (KDSETMODE)
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        // mmi opens fonts as "system/fonts/..." (no leading slash), i.e. relative to the cwd; init runs it from "/"
        for (n in listOf("system", "vendor")) {
            val l = File(paths.bin, n)
            if (!java.nio.file.Files.exists(l.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                runCatching { android.system.Os.symlink(File(paths.root, n).path, l.path) }
        }
        val serial = VmSettings.cleanSerial(settings.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        val overrides = LinkedHashMap(MmiBoot.properties)
        overrides["ro.aemu.host"] = "qemu-user"
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        VmSettings.cleanBaseband(settings.baseband).takeIf { it.isNotEmpty() }?.let { overrides["gsm.version.baseband"] = it }
        overrides["persist.sys.timezone"] = gmtZone()
        // debugging: run/props.extra — key=value lines on top of everything else
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        if (!props.prepare(overrides)) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        // the test program starts helper daemons (ztediag, getlog…) through ctl.start
        props.onCtl = { start, name ->
            val def = others[name]
            if (def == null) log("ctl.${if (start) "start" else "stop"} $name: not started (no guest binary)")
            else {
                synchronized(procs) { procs.remove(name) }?.destroyForcibly()
                if (start && !stopping) Thread { runCatching { startService(def) } }.start()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        // factory test code is written for the device's kernel touch driver: MT type B (mode 5) unless a mode was chosen explicitly
        input.mtMode = if (settings.mtMode == 0 || settings.mtMode == 4) 5 else settings.mtMode
        input.multiPerPid = true // the factory test opens event0 twice (minui + touch probe); the second connection must not evict the first
        input.serve()
        frames.serve()
        runCatching { power.writeText(""); power.setWritable(true, false) }
        Keeper.hold(ctx, img.name)
        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        setState(State.BOOTING)
        // The vendor libs mmi pulls in (mmi_led.so, libmm-disp-apis.so, hwcomposer…) link libbinder, which starts a binder
        // thread pool. Without binderd every BINDER_WRITE_READ fails with ENOTCONN, and libbinder's joinThreadPool()
        // abort()s on any error except TIMED_OUT/ECONNREFUSED/EBADF ("returned unexpected error -107, aborting"):
        // that was the SIGABRT after ~13 s. On the device the kernel binder is simply idle, so run binderd the same way.
        paths.binderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        // FFBM starts servicemanager too (srvmag_ffbm); without it getService() would just stall or fail
        startService(GuestService("servicemanager", listOf("/system/bin/servicemanager"), optional = true))
        // The "Versions" page (mmi_product.so) calls modem_api_init()/modem_read_nv() from libmodemapi, which speak QMI to a
        // modem that does not exist here: libqmi_cci dereferences a bad client handle (SIGSEGV in the page's worker thread)
        // or the page waits forever and the UI never gets its text or its Back key. These three are all mmi_product imports.
        runCatching {
            val modemapi = File(paths.root, "system/lib/libmodemapi.so")
            if (modemapi.isFile) {
                val n = ElfPatch.returnZero(modemapi, setOf("modem_api_init", "modem_api_release", "modem_read_nv"))
                if (n > 0) log("modem API: no modem here, init/read_nv answer at once ($n func.)")
            }
        }.onFailure { log("modem API patch failed: ${it.message}") }
        // diagnostics without adb: the guest's system calls go to the service log (see dumpMmiLog)
        // Tracing slows the factory UI to a crawl (every redraw prints hundreds of lines): opt in with run/strace.keep.
        runCatching {
            val flag = File(paths.bin, "strace.${service.name}")
            if (File(paths.bin, "strace.keep").exists()) flag.createNewFile() else flag.delete()
            paths.log(service.name).delete() // one run per log, not tens of megabytes of old ones
        }
        startService(service)
        setState(State.RUNNING)
        log("MMI started: ${service.argv.joinToString(" ")}; Android framework not started")
        Thread({
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val dead = synchronized(procs) { procs[service.name]?.isAlive != true }
                if (dead && !stopping && !powerHandled) {
                    val code = synchronized(procs) { procs[service.name] }?.let { runCatching { it.exitValue() }.getOrNull() }
                    failure = "MMI program ${service.argv.first()} exited (code ${code ?: "?"}). See the ${service.name} log in the VM log folder; missing vendor hardware is expected, a crash is not."
                    log("✖ $failure"); dumpMmiLog(service.name); setState(State.FAILED); break
                }
            }
        }, "mmi-watchdog").apply { isDaemon = true; start() }
    }

    /** Copies the interesting part of the MMI program's own log into the VM log, which the app can show. */
    private fun dumpMmiLog(name: String) {
        runCatching {
            val f = paths.log(name)
            val len = f.length()
            val text = java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(len, 600_000L).toInt(); r.seek(len - n)
                val b = ByteArray(n); r.readFully(b); String(b, Charsets.ISO_8859_1)
            }
            val lines = text.lines().map { it.take(220) }
            val key = Regex("fb0|graphics|tty0|/dev/input|ioctl|mmap|openat|open\\(|ENOENT|EACCES|EINVAL|error|fail|FATAL|cannot|can't|could not|not found", RegexOption.IGNORE_CASE)
            log("── ${name} log: ${lines.size} lines, last 40 ──")
            lines.takeLast(40).forEach { log("  $it") }
            val picked = lines.dropLast(40).filter { key.containsMatchIn(it) && !it.contains("EVIOCGBIT") }.takeLast(80)
            log("── ${name} log: ${picked.size} earlier lines about display/input/errors ──")
            picked.forEach { log("  $it") }
        }.onFailure { log("cannot read the $name log: ${it.message}") }
    }

    private fun doRecovery() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": recovery mode")
        if (!RecoveryImage.installed(paths)) error("this firmware has no recovery installed")
        val qemu = paths.nativeBin(Engine.KK.qemu)
        if (!qemu.canExecute()) error("translator ${Engine.KK.qemu} is not executable")
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()   // framebuffer, input node
        battery.start()
        val sd = Sdcard.setup(ctx, paths, img, ::log)
        val prep = RecoveryImage.prepare(paths, sd, ::log)
        input.sinkProtocolB = prep.touchProtocolB
        prep.format?.let { recoveryFormat = it; log("recovery framebuffer: ${when (it) { 0 -> "RGB565"; 2 -> "BGRA_8888"; else -> "32-bit RGBX" }} (from its binary)") }
        input.rateHz = settings.touchHz
        input.mtMode = settings.mtMode
        input.trackballEnabled = false // recovery does not load the Android evdev shim
        input.serve()
        // recovery's minui opens /dev/input/event0 through openat(), which qemu does not emulate:
        // a FIFO there gets the raw input_event stream straight from InputService
        runCatching {
            val ev = File(RecoveryImage.dir(paths), "dev/input/event0")
            ev.delete()
            android.system.Os.mkfifo(ev.path, "600".toInt(8))
            val fd = android.system.Os.open(ev.path, android.system.OsConstants.O_RDWR or android.system.OsConstants.O_NONBLOCK, 0)
            input.sink = java.io.FileOutputStream(fd)
        }.onFailure { log("recovery: no input channel: ${it.message}") }
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (settings.width to settings.height)
        val r = RecoveryImage.dir(paths)
        runCatching { File(r, "tmp/recovery.log").delete() }
        // debugging: run/recovery.strace traces the guest's system calls into run/recovery.trace
        val trace = if (File(paths.bin, "recovery.strace").exists()) listOf("-strace", "-D", File(paths.bin, "recovery.trace").absolutePath) else emptyList()
        val pb = ProcessBuilder(listOf(qemu.absolutePath, "-L", r.absolutePath, "-0", "/sbin/recovery") + trace + File(r, "sbin/recovery").absolutePath)
            .directory(r).redirectErrorStream(true).redirectOutput(File(paths.bin, "recovery.out"))
        pb.environment().clear()
        pb.environment().putAll(mapOf(
            "PATH" to "/sbin:/system/bin", "LD_LIBRARY_PATH" to ".:/sbin", "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard", "TZ" to "UTC",
            "LD_PRELOAD" to "/sbin/librecshim.so:/sbin/libaemushim.so", "AEMU_MOUNTS" to "/sdcard:/data:/system:/cache:/emmc:/external_sd:/usb-otg",
            "DHD_FB_W" to "$w", "DHD_FB_H" to "$h", "DHD_IN_W" to "$w", "DHD_IN_H" to "$h",
            "DHD_INPUT" to paths.inputSock.absolutePath,
        ))
        bootAt = System.currentTimeMillis()
        val p = pb.start()
        synchronized(procs) { procs["recovery"] = p }
        setState(State.RUNNING)
        log("recovery started (${w}x$h)")
        // pixel format the recovery draws in, from its own log ("Pixel format: BGRA_8888", TWRP)
        Thread {
            val rlog = File(r, "tmp/recovery.log")
            repeat(40) {
                if (!p.isAlive || stopping) return@Thread
                val t = runCatching { rlog.readText() }.getOrDefault("")
                val fmt = Regex("Pixel format: (BGRA_8888|RGBX_8888|RGBA_8888|RGB_565)").findAll(t).lastOrNull()?.groupValues?.get(1)
                if (fmt != null) {
                    recoveryFormat = when (fmt) { "BGRA_8888" -> 2; "RGB_565" -> 0; else -> 1 }
                    log("recovery draws $fmt")
                    return@Thread
                }
                Thread.sleep(250)
            }
        }.start()
        // reboot requests of the recovery (librecshim → /dev/aemu_power)
        val power = File(r, "dev/aemu_power")
        Thread {
            while (p.isAlive && !stopping) {
                if (power.length() > 0) {
                    val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText("")
                    powerRequest(v); break
                }
                Thread.sleep(500)
            }
        }.start()
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (stopping || powerHandled) return@Thread
            log("recovery exited (code $code), booting the system")
            onPower?.invoke(true, "")
        }.start()
    }

    private fun doBoot() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, ${img.skin}, engine ${engine.title}")
        if (!File(paths.root, "system/framework").isDirectory) error("firmware tree missing")
        if (MmiBoot.frameworkless(paths.root))
            error("this firmware has no Android framework (system/framework/framework.jar) and no native MMI/FTM test program to run instead")
        val qemu = paths.nativeBin(engine.qemu)
        if (!qemu.canExecute()) error("translator ${engine.qemu} is not executable")
        killLeftovers()
        HostNative.limitStackSafe()

        // 1. дерево
        val fixer = TreeFixer(ctx, paths, img, ::log)
        fixer.fixup()
        battery.start()
        // политика звука уже подменялась раньше — обновить её файлы (обёртка/AOSP) до текущей версии
        if (File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so").isFile) swapAudioPolicy()
        GuestLog.clear(paths.root)
        val sd = Sdcard.setup(ctx, paths, img, ::log)

        // 2. свойства
        val s = settings
        val overrides = LinkedHashMap<String, String>()
        // 2.3: ro.kernel.qemu=1 включает в фреймворке режим эмулятора — ставим его только mediaserver
        overrides["ro.kernel.qemu"] = if (engine == Engine.GB) "0" else "1"
        overrides["ro.kernel.qemu.gles"] = if (s.gpu && (s.hwui || engine == Engine.GB)) "1" else "0"
        overrides["qemu.gles"] = if (s.gpu) "1" else "0"
        // 7.0+ libEGL ignores egl.cfg and takes the first libGLES_*.so it finds; without the bridge: the software one
        if (img.api >= 24 && !s.gpu) {
            overrides["ro.hardware.egl"] = "android"
        }
        overrides["ro.sf.lcd_density"] = s.density.toString()
        overrides["qemu.sf.lcd_density"] = s.density.toString()
        // Android 1.5: keep EGL on the software renderer (its vendor driver wants the real GPU)
        overrides.putAll(CupcakeCompat.props(img.api))
        // Panels mounted sideways (Galaxy Tab 10.1 GT-P7300: "ro.sf.hwrotation=90", fb 800x1280 on a landscape UI) make
        // SurfaceFlinger turn the whole UI inside the framebuffer. Here the framebuffer IS the screen the user sees at
        // the resolution they picked, so the rotation only shows up as a UI lying on its side: 1280x720 came up as a
        // sideways portrait UI, 720x1280 as a sideways landscape one. Run the panel unrotated; the UI then follows the
        // framebuffer (wide = landscape, tall = portrait). run/props.extra can still set it back.
        val hwRot = runCatching {
            File(paths.root, "system/build.prop").readLines()
                .firstOrNull { it.trim().startsWith("ro.sf.hwrotation=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        }.getOrNull()
        if (hwRot != null && hwRot != 0) {
            overrides["ro.sf.hwrotation"] = "0"
            log("display: ignoring the firmware's panel rotation ($hwRot°), the UI follows the framebuffer")
        }
        overrides["ro.aemu.host"] = "qemu-user"
        // serial number from the image settings (blank = default); real devices expose it under both names
        val serial = VmSettings.cleanSerial(s.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        // baseband from the settings; the RIL stub answers the same string
        VmSettings.cleanBaseband(s.baseband).takeIf { it.isNotEmpty() }?.let { overrides["gsm.version.baseband"] = it }
        // ro.hardware from the settings (blank = the firmware's own value); the guest picks HAL libraries by it
        VmSettings.cleanHardware(s.hardware).takeIf { it.isNotEmpty() }?.let {
            overrides["ro.hardware"] = it
            log("props: ro.hardware=$it (from settings)")
        }
        if (s.camera && cameraSupported) overrides["ro.hardware.camera"] = "aemu_host"
        if (img.api in 9..25) overrides["ro.hardware.sensors"] = "aemu_host"
        overrides["dalvik.vm.execution-mode"] = if (s.jit) "int:jit" else "int:fast"
        if (s.lowRam || s.ramMb in 1..768) overrides["ro.config.low_ram"] = "true"
        if (s.ramMb > 0) {
            // Dalvik heap sized from the guest RAM budget (like a real device of that class)
            val heap = (s.ramMb / 4).coerceIn(32, 512)
            overrides["dalvik.vm.heapsize"] = "${heap}m"
            overrides["dalvik.vm.heapgrowthlimit"] = "${(heap / 2).coerceAtLeast(24)}m"
        }
        if (!s.radio) overrides["ro.radio.noril"] = "true"
        if (!s.hwui) overrides["debug.hwui.renderer"] = "skia"
        // PBO для текстур шрифтов (hwui 4.4+ на GLES3) ломают часть драйверов (Mali) — грузим текстуры напрямую
        if (HostInfo.gpu().contains("mali")) overrides["ro.hwui.use_gpu_pixel_buffers"] = "false"
        if (img.api in 19..20 && !isLpw39) overrides["persist.sys.dalvik.vm.lib"] = "libdvm.so"
        // Glass (4.0.x): CameraService's Gcam (HDR+) path pre-allocates 2592x1944 buffers in the OMAP format 0x100
        // (TI NV12); the emulated gralloc refuses it (EINVAL), dequeueBuffer fails and libgui then dereferences a null
        // buffer: mediaserver dies with SIGSEGV on the first camera open. Without Gcam the plain HAL path is used.
        if (img.api in 9..15) overrides["persist.lab.gcam"] = "0"
        // зигота 4.4+ заранее открывает EGL; дети после fork наследуют соединение моста, и гостевая
        // библиотека моста уходит в бесконечную рекурсию (падение по стеку в каждом приложении)
        if (img.api >= 19) overrides["ro.zygote.disable_gl_preload"] = "1"
        // ART first boot compiles every app with dex2oat under qemu (minutes, single-threaded): by default only
        // skip it (verify-none: ART verifies classes lazily when an app runs) and use several threads; "full compilation" in the settings keeps machine code
        if (img.api >= 21) {
            if (!s.fullDexopt) overrides["dalvik.vm.dex2oat-filter"] = "verify-none"
            // 7.0+: PackageManager picks the filter per reason from pm.dexopt.*
            if (img.api >= 24 && !s.fullDexopt) for (r in listOf("first-boot", "boot", "install", "bg-dexopt", "ab-ota", "core-app", "forced-dexopt", "nsys-library"))
                overrides["pm.dexopt.$r"] = "verify-none"
            overrides["dalvik.vm.dex2oat-flags"] = "-j" + Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            // ART (5.0+) relocates the boot image to a random address (patchoat), which fails under qemu
            // (the relocated oat has a wrong oatdata offset, zygote then dies in dex2oat): keep it in place
            if (img.api >= 21) {
                overrides["dalvik.vm.extra-opts"] = "-Xnorelocate"
                // installd's dex2oat starts its own runtime: without this it tries patchoat too and fails every app
                overrides["dalvik.vm.dex2oat-flags"] = overrides["dalvik.vm.dex2oat-flags"] + " --runtime-arg -Xnorelocate"
            }
        }
        // Android L preview (API 20, build LPW39): ART only. Its libart has no -Xrelocate/-Xnorelocate and there is no patchoat,
        // so the 5.0 settings above must NOT be applied (an unknown runtime option aborts zygote at once).
        // The firmware has no pre-built boot image, so zygote runs dex2oat on first boot. Under qemu that dex2oat dies in
        // art/runtime/class_linker.cc:3371 (Check failed: IsExceptionPending, android.util.Log) while running
        // SQLiteDebug.<clinit> at compile time. Initialising only the core-library classes of framework.jar's preloaded-classes
        // (java.*, libcore.*, ...) avoids that; framework classes are then simply loaded lazily from the dex files.
        if (isLpw39 && img.api == 20) {
            overrides["dalvik.vm.dex2oat-flags"] = "--compiler-filter=interpret-only"
            // dex2oat finishes the image and then aborts in exit() (libsigchain: "Unable to find next sigaction"); see Lpw39Dex2oat
            if (Lpw39Dex2oat.install(paths.root)) log("LPW39: dex2oat wrapped (SIGABRT after a complete write counts as success)")
            if (writeLpw39ImageClasses()) {
                // later options win over the --image-classes* that libart puts on the dex2oat command line
                overrides["dalvik.vm.image-dex2oat-flags"] = "--image-classes-zip=/system/framework/aemu-imgclasses.zip --image-classes=preloaded-classes"
            }
        }
        // порты MIUI правят framework на smali так, что Dalvik-верификатор отвергает классы ядра
        // (зигота падает на VerifyError) — на телефонах они живут с выключенной проверкой байткода
        if (img.skin.contains("MIUI", true) && img.runtime == "dalvik") overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a,m=y"
        // MediaTek: звук, камера, радио ждут «NVRAM готов» от nvram_daemon, которого у нас нет
        if (TreeFixer.isMtkAudio(paths.root) || File(paths.root, "system/bin/nvram_daemon").isFile) overrides["nvram_init"] = "Ready"
        // своя панель кнопок у нас — экранную панель гостя (4.x) прячем, чтобы не дублировать
        overrides["qemu.hw.mainkeys"] = if (s.showNavBar) "1" else "0"
        // отладка: run/props.extra — строки ключ=значение поверх всего остального
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        overrides["net.dns1"] = "8.8.8.8"
        overrides["net.dns2"] = "1.1.1.1"
        // в прошивках старая база часовых поясов — передаём текущее смещение, а не название зоны
        overrides["persist.sys.timezone"] = gmtZone()
        if (!props.prepare(overrides)) error("property area not ready")
        fixer.skipPreBoot(props)
        // Do not let the older Sony migration un-provision data completed by the explicit option.
        if (SetupWizardOption.restoreSonyFlow(s.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile))
            fixer.restoreSonySetupFlow()
        fixer.noScreenSleep()
        props.onSet = { k, v -> onProp(k, v) }
        props.onCtl = { start, svc -> onCtl(start, svc) }

        // 3. службы хоста
        props.serve()
        input.rateHz = s.touchHz
        input.mtMode = s.mtMode
        input.detectHome(paths.root)
        input.trackballEnabled = settings.trackball
        input.serve()
        vibration.serve()
        motion.serve()
        if (s.camera && cameraSupported) camera.serve()
        if (s.microphone && micSupported) mic.serve()
        if (s.bluetooth && bluetoothSupported) bluetooth.serve()
        frames.serve()
        if (s.radio) ril.serve() else log("radio: emulation disabled in settings")
        vold.serve()
        // у vold производителей бывают дополнительные сокеты (Samsung: usbstorage, enc_report) — отвечаем «ладно»
        runCatching {
            // 7.0+ keeps vold (with its cryptd socket) in /system/etc/init
            val rcFiles = listOf(paths.root, File(paths.root, "system/etc/init"))
                .flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") } ?: emptyList() }
            val rc = InitPlan.parse(rcFiles)
            rc.services["vold"]?.sockets?.keys?.filter { it != "vold" }?.forEach { name ->
                VoldStub(paths, img.sdcardPath, ::log, name).also { it.serve(); extraStubs.add(it) }
            }
        }
        // MediaTek: libaudioflinger сам работает с устройством /dev/eac (звуковой драйвер MTK) и читает из
        // него — наш канал звука под тем же именем его вешает, а за ним mediaserver и всю систему.
        // Для MTK /dev/eac ведёт в /dev/null: без звука, но без зависания.
        if (TreeFixer.isMtkAudio(paths.root)) {
            runCatching {
                val eac = audio.fifo
                if (!isLinkTo(eac, "/dev/null")) { eac.delete(); android.system.Os.symlink("/dev/null", eac.absolutePath) }
                // голосовой канал модема (CCCI): без него AudioMTKHardware бесконечно ждёт модем
                for (n in listOf("ccci_pcm_rx", "ccci_pcm_tx")) {
                    val f = File(paths.root, "dev/$n")
                    if (!isLinkTo(f, "/dev/null")) { f.delete(); android.system.Os.symlink("/dev/null", f.absolutePath) }
                }
            }
            // our HAL replaces the MTK one (TreeFixer) and plays through its own channel
            audio.makeFifo(); audio.start()
            log("audio: MediaTek, emulator HAL via /dev/aemu_pcm")
        } else { audio.makeFifo(); audio.start() }
        events.start()
        // LPW39 (L preview, API 20) already logs through logd's /dev/socket/logdw; without the host receiver every ART message is lost
        if (img.api >= 21 || isLpw39) logd.start()
        if (img.api >= 26) hwbinder.start()
        lmk = GuestLmk(paths.root, s.lowRam, ::log, s.ramMb).also { it.start() }
        val netCfg = if (s.netProxy) net.start() else GuestRunner.NetConfig()
        runner.sdcardHost = sd
        val r = GuestRunner(paths, img.copy(settings = settings), netCfg.copy(glPath = if (s.gpu) "/dev/socket/gl" else null)).also {
            it.sdcardHost = sd
            // отладка: файл run/binder.verbose включает полную трассировку binder в binder-warn.log
            it.binderVerbose = File(paths.bin, "binder.verbose").exists()
            it.userQemuArgs = s.qemuArgs
        }
        runnerRef = r
        Keeper.hold(ctx, img.name)

        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        everBooted = false; zygoteRestarts = 0; imeRetried = false
        bootAnimCheckRunning.set(false); bootAnimFixes = 0
        touchWizMediaStarted = false
        noteBoot.interrupted()
        pendingNote = ImageStore.get(ctx, img.id)?.oneTimeNote ?: img.oneTimeNote
        setState(State.BOOTING)

        // 4. binder
        paths.binderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")

        // 5. службы гостя по плану из init.rc прошивки
        val basePlan = bootServices.filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        TrebleReport.lines(img.api, paths.root, basePlan).forEach { log(it) }
        // Samsung 2.x boot logo + PowerOn sound (playlogos1); images imported earlier have no such entry in their saved plan
        val plan = if (File(paths.bin, BootMediaServices.LEGACY_LOGO_OFF).exists()) basePlan
                   else BootMediaServices.withLegacyLogo(basePlan, paths.root, img.api)
        if (plan.any { it.name == BootMediaServices.LEGACY_LOGO }) log("boot logo: playlogos1 will run (Samsung 2.x boot animation and sound)")
        var glDone = false
        val wifiOn = settings.wifi && wifiSupported && File(paths.root, WifiEmu.JAR_PATH).isFile
        if (settings.wifi && wifiSupported && !wifiOn) log("wifi: ${WifiEmu.JAR_NAME} is not installed (build guest-wifi/build.sh), the guest keeps its normal network")
        WifiEmu.write(paths.root, settings, wifiOn)
        if (wifiOn) log("wifi: emulated network \"${settings.wifiSsid}\" (${if (WifiEmu.secured(settings)) "WPA2" else "open"})")
        for (svc in plan) {
            if (stopping) return
            // GL-мост должен ждать гостя до SurfaceFlinger/zygote
            if (!glDone && (svc.name == "surfaceflinger" || svc.name == "zygote" || svc.name == "bootanim")) {
                glUp(); glDone = true
            }
            // Samsung: native SensorService/libsensorhub wait for "sensorhubservice" forever; a placeholder binder ends the wait
            if (svc.name == "zygote" && engine == Engine.KK && File(paths.root, "system/framework/aemu-sensorhub.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-sensorhub") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-sensorhub", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-sensorhub.jar", "/system/bin",
                    "app.aemu.stub.SensorHubStub", "sensorhubservice"), optional = true))
            }
            // 4.2+: заглушка bluetooth_manager до зиготы (system_server с ro.kernel.qemu=1 свою не поднимает)
            if (svc.name == "zygote" && img.api >= 9 && engine == Engine.KK && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                // 2.2–4.1 look up "bluetooth" instead; without it BluetoothAdapter is null and the vendor's Bluetooth apps crash
                val btName = if (img.api >= 17) "bluetooth_manager" else "bluetooth"
                // Android 4.4 (API 19, incl. the KKWT Wear image): the real bridge client; everything else keeps the placeholder
                val bridgeJar = img.api == 19 && File(paths.root, "system/framework/aemu-bt.jar").isFile
                synchronized(procs) { procs.remove("aemu-bt") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-bt", listOf(appProcess,
                    "-Djava.class.path=/system/framework/${if (bridgeJar) "aemu-bt.jar" else "aemu-stubs.jar"}", "/system/bin",
                    if (bridgeJar) "app.aemu.stub.BtService" else "app.aemu.stub.BtStub", btName), optional = true))
            }
            // emulated Wi-Fi: the guest service answers WifiManager as a connected access point and, on 5.0+, registers the
            // Wi-Fi network itself (instead of the Ethernet placeholder below)
            if (svc.name == "zygote" && wifiOn) {
                synchronized(procs) { procs.remove("aemu-wifi") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-wifi", listOf(appProcess,
                    "-Djava.class.path=/${WifiEmu.JAR_PATH}", "/system/bin", "app.aemu.stub.WifiStub"), optional = true))
            }
            // 5.0–7.x: a registered network, otherwise ConnectivityService says "no active network" and browsers stay offline
            if (svc.name == "zygote" && img.api in 21..25 && !wifiOn && engine == Engine.KK && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-net") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-net", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.NetStub"), optional = true))
            }
            if (svc.name == BootMediaServices.LEGACY_LOGO) { LegacyLogoPatch.apply(paths.root, ::log); LegacyLogoPatch.provideHeadsetState(paths.root, ::log) }
            startService(svc)
            svc.waitSocket?.let { sock -> waitFor("socket $sock", 20_000) { paths.socket(sock).exists() } }
            if (svc.delayMs > 0) Thread.sleep(svc.delayMs)
            if (svc.name == "surfaceflinger") startTouchWizBootMedia()
        }
        if (!glDone) glUp()
        startTouchWizBootMedia() // no-op if already started above (e.g. a plan without surfaceflinger)
        log("system started: ${alive().joinToString()}")
        if (SetupWizardOption.runHelper(settings.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile)) {
            val setupRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = setupRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin",
                        "app.aemu.setup.SetupCtl", if (settings.skipSetupWizard) "skip" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("setup option: exit=$code ${output.trim()}")
                }.onFailure { log("setup option failed: ${it.message}") }
            }, "setup-option").start()
        }
        if (GoogleAppsOption.runHelper(img.api, settings.disableGoogleApps,
                File(paths.root, "data/system/aemu-google-disabled.properties").isFile, recoveryMode, lowPowerBoot)) {
            val googleRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = googleRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.GoogleCtl",
                        if (settings.disableGoogleApps) "disable" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("Google-app option: exit=$code ${output.trim()}")
                }.onFailure { log("Google-app option failed: ${it.message}") }
            }, "google-app-option").start()
        }
        watchdog()
    }

    @Volatile private var runnerRef: GuestRunner? = null
    val guestRunner: GuestRunner get() = runnerRef ?: runner

    private fun startBinder() {
        val sock = paths.binderSock.absolutePath
        when (engine) {
            Engine.KK -> spawn("binderd", listOf(paths.nativeBin(engine.binderd).absolutePath, "-s", sock), emptyMap())
            Engine.GB -> if (File(paths.bin, "binderd.kk").exists()) {
                // эксперимент: более новый binderd движка KK
                spawn("binderd", listOf(paths.nativeBin(Engine.KK.binderd).absolutePath, "-s", sock), emptyMap())
            } else {
                Slot.start(ctx, BinderSlot::class.java, listOf("binderd", "-s", sock), emptyMap(), paths.log("binderd"))
                log("· binderd started (:binder service)")
            }
        }
    }

    private fun glUp() {
        if (!settings.gpu) { log("GPU bridge off, software rendering"); return }
        val (w, h) = if (engine == Engine.GB) 480 to 800 else settings.width to settings.height
        paths.glSock.parentFile?.mkdirs()
        paths.glSock.delete()
        when (engine) {
            Engine.GB -> {
                Slot.start(ctx, GlSlot::class.java,
                    listOf("glserverd", "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath, "-w", "$w", "-h", "$h"),
                    emptyMap(), paths.log("glserverd"))
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
            Engine.KK -> {
                if (surface == null) waitFor("screen surface", 10_000) { surface != null }
                val sf = surface
                HostNative.relaxFdsanSafe()
                // 5.0+: hwui's GL stream has crashed the in-app bridge (SIGSEGV in serve_one_raw took the whole app down);
                // the standalone glserverd keeps such a crash inside its own process
                if (img.api < 21 && sf != null && GlBridge.start(sf, paths.glSock.absolutePath, paths.log("glbridge").absolutePath, w, h, false)) {
                    log("★ GPU bridge running in-app: frames render straight to the surface")
                    waitFor("GL bridge socket", 5_000) { paths.glSock.exists() }
                    return
                }
                log(if (img.api >= 21) "GPU bridge: standalone glserverd" else "in-app GPU bridge failed, falling back to standalone glserverd")
                spawn("glserverd", listOf(paths.nativeBin(engine.glserverd).absolutePath,
                    "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath,
                    "-notify", paths.frameSock.absolutePath, "-w", "$w", "-h", "$h"), emptyMap())
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
        }
    }

    val glInApp: Boolean get() = engine == Engine.KK && GlBridge.running()

    /**
     * The boot plan saved with the image. Images imported before InitPlan learned to ignore MediaTek's
     * advanced_meta_init.rc (the META-mode copy of init.rc) carry its service definitions instead of the real ones —
     * netd then got only its "netd" socket, no "dnsproxyd", and exited at once. Such an image gets its plan rebuilt
     * from the rc files in the tree on every boot, so it does not have to be imported again.
     */
    /** LPW39: writes system/framework/aemu-imgclasses.zip holding a core-library-only copy of framework.jar's preloaded-classes. */
    private fun writeLpw39ImageClasses(): Boolean = runCatching {
        val fw = File(paths.root, "system/framework/framework.jar")
        val out = File(paths.root, "system/framework/aemu-imgclasses.zip")
        val keep = listOf("java.", "javax.", "libcore.", "dalvik.", "sun.", "org.apache.harmony.", "com.android.org.",
            "org.json.", "org.w3c.", "org.xml.", "org.kxml2.", "org.xmlpull.", "org.apache.xml.", "junit.")
        val text = java.util.zip.ZipFile(fw).use { z ->
            val e = z.getEntry("preloaded-classes") ?: return@runCatching false
            z.getInputStream(e).bufferedReader().readText()
        }
        val lines = text.lineSequence().map { it.trim() }.filter { l -> l.isNotEmpty() && !l.startsWith("#") && keep.any { l.startsWith(it) } }.toList()
        if (lines.isEmpty()) return@runCatching false
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zo ->
            zo.putNextEntry(java.util.zip.ZipEntry("preloaded-classes"))
            zo.write((lines.joinToString("\n") + "\n").toByteArray())
            zo.closeEntry()
        }
        log("LPW39: boot image classes limited to the core libraries (${lines.size} of the preloaded classes)")
        true
    }.getOrDefault(false)

    /**
     * Android L preview build LPW39 (Google ADT-1 / molly). It reports API 20 but, unlike 4.4W (also API 20), is ART-only:
     * every L-preview workaround is keyed on this exact ro.build.id so Dalvik-based 4.4 / 4.4W images are untouched.
     */
    private val isLpw39: Boolean by lazy { img.buildId.trim().equals("LPW39", ignoreCase = true) }

    private val bootServices: List<GuestService> by lazy {
        runCatching {
            if (!File(paths.root, "advanced_meta_init.rc").isFile) return@runCatching img.services
            val files = (paths.root.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }
                ?.sortedBy { if (it.name == "init.rc") 0 else 1 } ?: emptyList()) +
                (File(paths.root, "system/etc/init").listFiles()?.filter { it.name.endsWith(".rc") } ?: emptyList())
            val fresh = InitPlan.plan(InitPlan.parse(files), img.api, paths.root)
            if (fresh.isEmpty()) img.services else {
                if (fresh != img.services) log("init plan rebuilt without advanced_meta_init.rc (${fresh.size} services)")
                fresh
            }
        }.getOrDefault(img.services)
    }

    /** app_process, or app_process32 on firmware that has only that. */
    private val appProcess: String get() = AppProcess.path(paths.root)

    private fun startService(svc: GuestService, propsFile: File? = null) {
        if (!File(paths.root, svc.argv.first().removePrefix("/")).isFile) {
            log("· ${svc.name}: ${svc.argv.first()} missing, skipping")
            return
        }
        val extra = LinkedHashMap<String, String>()
        for ((sock, mode) in svc.sockets) extra["DHD_SOCK_$sock"] = "${paths.socket(sock).absolutePath},$mode"
        if (svc.uid != 0) { extra["DHD_UID"] = svc.uid.toString(); extra["DHD_GID"] = svc.gid.toString() }
        var props = propsFile
        // звук: с ro.kernel.qemu=1 mediaserver берёт эмуляторный вывод в /dev/eac
        if (svc.name == "mediaserver") props = this.props.privateCopy("media", mapOf("ro.kernel.qemu" to "1"))
        val argv = if (svc.name == "zygote" && !settings.jit && img.runtime == "dalvik")
            svc.argv.take(1) + "-Xint:fast" + svc.argv.drop(1) else svc.argv
        // отладка: файл run/strace.<служба> включает трассировку системных вызовов qemu
        // tracing slows a service down and fills its log: only for the services named in the VM settings
        // ("mediaserver,zygote"), or by the debugging file run/strace.<service>
        if (svc.name in VmSettings.straceNames(settings.straceServices) || File(paths.bin, "strace.${svc.name}").exists())
            extra["QEMU_STRACE"] = "1"
        spawn(svc.name, guestRunner.cmdline(argv, props), guestRunner.env(extra))
    }

    private fun spawn(name: String, cmd: List<String>, env: Map<String, String>) {
        val log = paths.log(name)
        val pb = ProcessBuilder(cmd).directory(paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(if (env.isEmpty()) mapOf("PATH" to "/system/bin") else env)
        // 7.0+ zygote refuses to fork with pipes among its descriptors (it only reopens files and devices):
        // its stdio go straight to the log file and /dev/null, which the guest shim shows as /dev/null
        val direct = name == "zygote" && img.api >= 24
        if (direct) {
            runCatching { log.appendText("\n=== $name ${stamp()} ===\n") }
            pb.redirectInput(File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }
        val p = pb.start()
        synchronized(procs) { procs[name] = p }
        pidOf(p)?.let { pgids.add(it) }
        log("· $name started")
        Thread({
            if (!direct) runCatching {
                OutputStreamWriter(FileOutputStream(log, true)).use { w ->
                    w.write("\n=== $name ${stamp()} ===\n")
                    p.inputStream.bufferedReader().forEachLine { w.write(it); w.write("\n"); w.flush() }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!stopping) log("· $name exited, code $code")
        }, "log-$name").apply { isDaemon = true; start() }
    }

    private fun alive(): List<String> = synchronized(procs) { procs.filterValues { it.isAlive }.keys.toList() }

    /**
     * Pixel format the boot logo uses in fb0 (0 = RGB565, 1 = RGBA_8888, 2 = BGRA_8888). The emulated fb0 is two 32-bit
     * pages and Samsung's fb is BGRA, so 2 is the default; run/logo-format (a single digit) overrides it for testing.
     */
    val logoFormat: Int get() = runCatching { File(paths.bin, "logo-format").readText().trim().toInt() }.getOrNull()?.takeIf { it in 0..2 } ?: 2

    /** True while Samsung's playlogos1 paints the boot animation into fb0 (the UI then shows fb0 instead of the GL surface). */
    fun legacyLogoRunning(): Boolean = bootDoneAt == 0L && synchronized(procs) { procs[BootMediaServices.LEGACY_LOGO]?.isAlive == true }

    private fun waitFor(what: String, ms: Long, cond: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until && !stopping) {
            if (cond()) return true
            Thread.sleep(100)
        }
        val ok = cond()
        if (!ok) log("⚠ timed out waiting for: $what")
        return ok
    }

    // ------------------------------------------------------------------ присмотр

    /** Guest asked to reboot / power off ("reboot", "reboot,recovery", "shutdown"); the UI restarts or stops the VM. */
    @Volatile var onPower: ((reboot: Boolean, reason: String) -> Unit)? = null
    @Volatile private var powerHandled = false

    private fun powerRequest(v: String) {
        if (powerHandled || v.isEmpty()) return
        powerHandled = true
        val reboot = v.startsWith("reboot")
        val reason = v.substringAfter(',', "")
        log("guest requested ${if (reboot) "reboot" else "power off"}${if (reason.isNotEmpty()) " ($reason)" else ""}")
        if (reason == "recovery" && !RecoveryImage.installed(paths)) log("no recovery installed, booting the system again")
        onPower?.invoke(reboot, reason)
    }

    private fun onProp(k: String, v: String) {
        if (k == "sys.powerctl") { powerRequest(v); return }
        if (k == "service.bootanim.exit") {
            for ((start, name) in BootMediaServices.triggers(paths.root, k, v)) onCtl(start, name)
            if (v == "1") onCtl(false, "bootanim")
        }
        if ((k == "sys.boot_completed" || k == "dev.bootcomplete") && v == "1" && bootDoneAt == 0L) {
            if (!recoveryMode && !lowPowerBoot) noteBoot.reported(SystemClock.elapsedRealtime())
            bootDoneAt = System.currentTimeMillis()
            everBooted = true
            log("★ guest reported boot completion in ${(bootDoneAt - bootAt) / 1000} s (UI not verified)")
            setState(State.RUNNING)
            Thread { afterBoot() }.start()
        }
    }

    @Volatile private var touchWizMediaStarted = false

    /**
     * TouchWiz only: if /system/bin has samsungani (boot animation) and/or playsound (boot sound), run them during
     * boot. Missing binaries are skipped without an error, and nothing here can fail the boot.
     */
    private fun startTouchWizBootMedia() {
        if (touchWizMediaStarted || stopping || recoveryMode || lowPowerBoot) return
        touchWizMediaStarted = true
        runCatching {
            for (name in BootMediaServices.touchWizBootMedia(paths.root, img.skin)) {
                // below 4.1 the animation never quits on its own (see onCtl), so don't force it there
                if (name == "samsungani" && img.api < 16) { log("TouchWiz boot media: samsungani skipped on API ${img.api}"); continue }
                val key = if (name == "playsound") name else "bootanim"
                if (synchronized(procs) { procs[key]?.isAlive == true }) continue
                val def = BootMediaServices.resolve(paths.root, name) ?: continue
                Thread { runCatching { startService(def) } }.start()
                log("TouchWiz boot media: $name started")
            }
        }.onFailure { log("TouchWiz boot media: ${it.message}") }
    }

    private fun onCtl(start: Boolean, svc: String) {
        val plan = bootServices.filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        if (svc == "bootanim" || svc == "bootanimation" || svc == "samsungani") {
            if (!start) {
                // 4.x sets service.bootanim.exit first and the animation quits on its own, closing its audio.
                // Killing it outright left Samsung's boot sound track half-open: mediaserver hung and every
                // app creating a sound (the phone process, ToneGenerator) got an ANR.
                // Never kill it: a bootanim that is signalled (SIGTERM 143 / SIGKILL 137) dies without releasing its
                // SurfaceFlinger layer, which then stays on top of the UI. Leave it in `procs` and just wait: it
                // leaves by itself (exit 0) once its loop sees the property and calls stopProcess().
                val p = synchronized(procs) { procs["bootanim"] } ?: return
                Thread {
                    var waited = 0
                    while (!stopping && p.isAlive) {
                        if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) break
                        waited++
                        if (waited == 30) log("boot animation is still running 30 s after service.bootanim.exit=1, waiting (not killing it)")
                    }
                    // the watchdog reaps the exited process and checks the layer if the exit code was non-zero
                    // (only 2.3 / MIUI ICS animations never poll the property; see the api < 16 note below)
                }.apply { isDaemon = true; start() }
                return
            }
            // below 4.1 (2.3, MIUI ICS) the animation does not quit by itself: killed, it leaves the GL bridge on its last frame
            if (img.api < 16 || bootDoneAt != 0L || synchronized(procs) { procs.containsKey("bootanim") }) return
            InitPlan.optional(svc, img, paths.root)?.let { def ->
                Thread { runCatching { startService(def) } }.start()
                log("boot animation started")
            }
            return
        }
        val def = plan.firstOrNull { it.name == svc } ?: InitPlan.optional(svc, img, paths.root)
        if (svc == "playsound" && start && (bootDoneAt != 0L || synchronized(procs) { procs["playsound"]?.isAlive == true })) return
        if (def == null) { log("ctl.${if (start) "start" else "stop"} $svc: no such service"); return }
        synchronized(procs) { procs.remove(svc) }?.destroyForcibly()
        if (start) Thread { runCatching { startService(def) } }.start()
    }

    private fun afterBoot() {
        val r = guestRunner
        r.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; svc power stayon true"), 60_000)
        TreeFixer(ctx, paths, img, ::log).noScreenSleep()
        disableBrokenComponents()
        runCatching {
            ImageStore.update(ctx, img.id) { it.copy(lastBootMs = bootDoneAt - bootAt, bootCount = it.bootCount + 1) }
        }.onFailure { log("boot statistics: could not save: ${it.message}") }
    }

    /**
     * 2.2–4.1 Google network location (in Play services and in Maps) calls TelephonyRegistry.listen with notifyNow at
     * start: the reply of that call is lost between the nested oneway callback and the reply, and the service dies with
     * "Unknown exception code" and a "has stopped" dialog on every boot. Nothing here needs network location.
     */
    private fun disableBrokenComponents() {
        if (img.api !in 9..16) return
        val marker = File(paths.root, "data/.aemu-components-disabled2")
        if (marker.isFile) return
        val list = listOf(
            "com.google.android.location/com.google.android.location.NetworkLocationService",
            "com.google.android.location/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.NetworkLocationService",
        )
        // PATH of 2.2–4.x starts with /sbin: the pm script's "app_process" is then looked up there and qemu gives up
        // Glass needs the "network" provider (settings' NewTimeZoneService requests it at boot and dies without it)
        val glass = GlassLocationPolicy.isGlass(img.name, img.model, File(paths.root, "system/app/GlassBluetooth.apk").isFile ||
            File(paths.root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        for (c in list) if (!GlassLocationPolicy.keepEnabled(c, glass)) runCatching { guestRunner.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; pm disable $c"), 120_000) }
        runCatching { marker.writeText("1") }
    }

    /** qemu maps a guest path into the tree only if the file exists there, so the shim's request file is pre-made */
    private val power: File get() = File(paths.root, "dev/aemu_power")

    private fun watchdog() {
        runCatching { power.writeText(""); power.setWritable(true, false) }
        // 7.0+: the guest deletes the host-path alias of the root that the linker stats at start-up (see TreeFixer);
        // a program started without it aborts, so it is checked far more often than the supervision loop below
        if (img.api >= 24) Thread({
            val fixer = TreeFixer(ctx, paths, img, ::log)
            while (!stopping) {
                runCatching { fixer.ensureHostPathAlias() }
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }, "host-path-alias").apply { isDaemon = true; start() }
        Thread({
            var restarts = HashMap<String, Int>()
            while (!stopping) {
                Thread.sleep(3000)
                if (power.length() > 0) { val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText(""); powerRequest(v) }
                if (img.api >= 21) runCatching { dns.ensure() }
                val dead = synchronized(procs) { procs.filter { !it.value.isAlive }.toList() }
                for ((name, p) in dead) {
                    if (stopping) break
                    // Consume this exact dead instance before spawning a replacement. Removing
                    // by name after restartZygote() erased the newly started zygote from supervision.
                    if (!synchronized(procs) { procs.remove(name, p) }) continue
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (name in setOf("zygote", "surfaceflinger", "mediaserver")) noteBoot.interrupted()
                    // a clean exit (code 0) is the normal end of the animation; anything else is a crash or a kill
                    if (name == "bootanim" && code != 0) checkDeadBootAnimLayer(code)
                    if (name == "zygote") {
                        // Glass 4.4 first boot: IMMS fails without a selected IME; settings.db now exists, so seed it and boot again once
                        if (state != State.FAILED && !everBooted && !imeRetried && guestLogHas(GlassImePolicy.FAILURE_MARK)) {
                            imeRetried = true
                            if (TreeFixer(ctx, paths, img, ::log).seedDefaultIme()) {
                                log("✖ system_server failed in InputMethodManagerService, default input method set, starting the system again")
                                restartZygote()
                                continue
                            }
                        }
                        if (state != State.FAILED && (!everBooted || zygoteRestarts >= 6)) {
                            failure = "zygote exited (code $code)" + if (code == 137) " — SIGKILL (cause not established)" else ""
                            if (code == 137 && guestLogHas("No original dex files found"))
                                failure = "system_server cannot start: the precompiled code of the framework (system/framework/oat) is missing from this container — import the firmware again"
                            log("✖ $failure")
                            // zygote only kills itself for system_server: say what the guest log says about it
                            val why = runCatching { CupcakeCompat.crashSummary(GuestLog.tail(paths.root), processTail(paths.log("zygote"))) }.getOrDefault(emptyList())
                            if (why.isNotEmpty()) { log("guest log before the crash:"); why.forEach { log("  $it") } }
                            setState(State.FAILED)
                        } else if (state != State.FAILED && everBooted) {
                            zygoteRestarts++
                            log("✖ zygote crashed (code $code), restarting system ($zygoteRestarts)")
                            // the first crash after a completed boot: what the guest said about it (later ones usually repeat it)
                            if (zygoteRestarts == 1) {
                                val why = runCatching { CupcakeCompat.crashSummary(GuestLog.tail(paths.root), processTail(paths.log("zygote"))) }.getOrDefault(emptyList())
                                if (why.isNotEmpty()) { log("guest log before the crash:"); why.forEach { log("  $it") } }
                            }
                            restartZygote()
                        }
                        continue
                    }
                    val svc = img.services.firstOrNull { it.name == name }
                    val n = restarts.getOrDefault(name, 0)
                    // init restarts surfaceflinger and then zygote ("onrestart restart zygote"); without that a
                    // crashed compositor leaves the last frame on screen forever
                    if (name == "surfaceflinger" && svc != null && bootDoneAt > 0 && n < 12) {
                        restarts[name] = n + 1
                        log("✖ surfaceflinger crashed (code $code), restarting it and the system (${n + 1}/12)")
                        runCatching { startService(svc) }
                        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
                        continue
                    }
                    // родной audio_policy производителя может падать с нашим HAL — подменяем на AOSP
                    if (name == "mediaserver" && code == 139 && n == 1) swapAudioPolicy()
                    // the AOSP stand-in crashed as well (different service ABI): keep the firmware's own policy for good
                    if (name == "mediaserver" && code == 139 && n == 2) abandonAospPolicy()
                    if (svc != null && (svc.restart || name in ALWAYS_RESTART) && code != 137 && code != 143 && n < 12) {
                        restarts[name] = n + 1
                        log("service $name crashed (code $code), restarting (${n + 1}/12)")
                        runCatching { startService(svc) }
                    }
                }
                if (pendingNote.isNotBlank() && !recoveryMode && !lowPowerBoot && !stopping) {
                    val healthy = synchronized(procs) {
                        listOf("zygote", "surfaceflinger").all { procs[it]?.isAlive == true }
                    }
                    if (noteBoot.ready(SystemClock.elapsedRealtime(), state == State.RUNNING, healthy)) {
                        runCatching {
                            val saved = ImageStore.update(ctx, img.id) { it.copy(oneTimeNote = "") }
                            if (saved != null) pendingNote = ""
                        }.onFailure { log("ROM note: could not save completion: ${it.message}") }
                    }
                }
            }
        }, "vm-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Ставит политику AOSP 4.3 за обёрткой: родная сохраняется, лишние потоки производителя
     * (Samsung: 0..14 против 0..9 в AOSP) обёртка сводит к MUSIC, чтобы AOSP-код не писал за массив.
     */
    private fun swapAudioPolicy() {
        if (img.api < 16) return
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        val failedMark = File(paths.root, "system/.aemu-parked/audio-aosp-failed")
        if (failedMark.isFile) { // the AOSP stand-in already crashed on this firmware: never install it again
            if (parked.isFile) runCatching { parked.copyTo(f, overwrite = true); aosp.delete() }
            return
        }
        // The previous stream-tail bug made mediaserver crash and triggered a
        // policy fallback. ZR's stock policy itself was not the origin of PC=2;
        // the AOSP fallback has a different service ABI and crashes at 0x98.
        val directTrack = img.api in 19..20 && runCatching {
            AudioHalAbi.usesDirectTrackTail(File(paths.root, "system/lib/libnbaio.so").readBytes())
        }.getOrDefault(false)
        if (directTrack) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true) // retain saved stock binary
                log("audio: CAF direct-track, restored firmware audio policy")
            }.onFailure { log("audio: policy restore failed: ${it.message}") }
            return
        }
        // MediaTek: AudioPolicyService переделан (другие ops и слоты), политика AOSP в нём падает.
        // Родная политика MTK падала только из-за проверки DRVB, которую теперь снимает TreeFixer
        if (TreeFixer.isMtkPlatform(paths.root)) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true); parked.delete(); aosp.delete()
                log("audio: MediaTek, restored stock audio_policy")
            }
            return
        }
        fun assetSize(n: String) = runCatching { ctx.assets.openFd("engines/kk/$n").use { it.length } }.getOrDefault(-1L)
        if (aosp.length() == assetSize("audio_policy.default.so") && f.length() == assetSize("audio_policy.wrap.so")) return
        runCatching {
            parked.parentFile?.mkdirs()
            if (!parked.isFile && f.isFile) f.copyTo(parked, overwrite = true)
            ctx.assets.open("engines/kk/audio_policy.default.so").use { i -> aosp.outputStream().use { o -> i.copyTo(o) } }
            ctx.assets.open("engines/kk/audio_policy.wrap.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            log("audio: firmware audio_policy crashes, installed AOSP one (stock kept)")
        }
    }

    /** Puts the firmware's own audio policy back and remembers that the AOSP stand-in does not work here. */
    private fun abandonAospPolicy() {
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        if (!parked.isFile || !aosp.isFile) return
        runCatching {
            parked.copyTo(f, overwrite = true)
            aosp.delete()
            File(paths.root, "system/.aemu-parked/audio-aosp-failed").writeText("1")
            log("audio: AOSP policy crashed too, restored the firmware's own audio policy")
        }.onFailure { log("audio: cannot restore firmware audio policy: ${it.message}") }
    }

    private val bootAnimCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var bootAnimFixes = 0

    /**
     * bootanim died abnormally. If SurfaceFlinger still keeps its layer on top once the system is up (the dead
     * client's layer is never reaped here), the screen stays black on top of the real UI. Restart SurfaceFlinger
     * and the system through the usual crash path of the watchdog, which drops that layer.
     */
    private fun checkDeadBootAnimLayer(code: Int) {
        if (!bootAnimCheckRunning.compareAndSet(false, true)) return
        log("boot animation died (code $code), will check the top SurfaceFlinger layer after boot")
        Thread({
            try {
                while (!stopping && bootAnimFixes < 2) {
                    // while booting the animation layer is legitimately on top
                    if (!waitFor("boot completion (bootanim check)", 300_000) { bootDoneAt != 0L }) return@Thread
                    Thread.sleep(5000)
                    if (stopping) return@Thread
                    val (_, dump) = guestRunner.run(listOf("/system/bin/sh", "-c",
                        "export PATH=/system/bin:/system/xbin:\$PATH; dumpsys SurfaceFlinger"), 30_000)
                    val layers = BootAnimLayer.visibleLayers(dump)
                    if (layers.isEmpty() || !BootAnimLayer.isBootAnim(layers.last())) {
                        log("boot animation died, top layer is ${layers.lastOrNull() ?: "unknown"}: nothing to fix")
                        return@Thread
                    }
                    bootAnimFixes++
                    log("✖ dead boot animation is still the top layer, restarting SurfaceFlinger ($bootAnimFixes/2)")
                    val sf = synchronized(procs) { procs["surfaceflinger"] }
                    val pid = sf?.let { pidOf(it) }
                    if (pid == null) { log("surfaceflinger is not running, cannot restart it"); return@Thread }
                    // the watchdog sees it die, starts it again and restarts zygote
                    runCatching { AProcess.sendSignal(pid, 9) }
                    Thread.sleep(10_000) // let the watchdog reset bootDoneAt before waiting for the next boot
                }
            } catch (_: InterruptedException) {
            } finally { bootAnimCheckRunning.set(false) }
        }, "bootanim-layer-check").apply { isDaemon = true; start() }
    }

    private fun restartZygote() {
        val z = img.services.firstOrNull { it.name == "zygote" } ?: return
        if (img.api >= 24) runCatching { TreeFixer(ctx, paths, img, ::log).ensureHostPathAlias() }
        bootDoneAt = 0
        setState(State.BOOTING)
        paths.socket("zygote").delete()
        // на настоящем ядре дети зиготы гибнут вместе с system_server; здесь они остаются жить и
        // держат ссылки на мёртвые службы (телефония отказывает новому system_server в правах)
        val n = killZygoteChildren()
        if (n > 0) log("killed apps of previous zygote: $n")
        // healthd keeps the battery listener of the dead system_server and stops answering the new one:
        // BatteryService.onStart then blocks in registerListener forever
        img.services.firstOrNull { it.name == "healthd" }?.let { h ->
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(h) }
        }
        runCatching { startService(z) }
    }

    /** the last lines a guest process wrote to its stdout/stderr file */
    private fun processTail(f: File, bytes: Long = 16_384): List<String> = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), bytes).toInt(); r.seek(r.length() - n)
            String(ByteArray(n).also { r.readFully(it) }, Charsets.UTF_8).lines().filter { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun guestLogHas(text: String): Boolean = runCatching {
        val f = File(paths.root, "dev/log/main")
        val len = f.length()
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(len, 400_000L).toInt(); r.seek(len - n)
            val b = ByteArray(n); r.readFully(b)
            String(b, Charsets.ISO_8859_1).contains(text)
        }
    }.getOrDefault(false)

    private fun killZygoteChildren(): Int {
        val marker = "/images/${img.id}/"
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            // app_process с aemu-stubs.jar — наша заглушка bluetooth_manager, не приложение зиготы
            if (cmd.contains(marker) && cmd.contains("/system/bin/app_process") && !cmd.contains("aemu-stubs.jar") && !cmd.contains("aemu-bt.jar") && !cmd.contains(WifiEmu.JAR_NAME) && !cmd.contains("aemu-sensorhub.jar")) {
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ остановка

    fun stop() {
        if (state == State.STOPPED) return
        stopping = true
        setState(State.STOPPING)
        log("stopping system")
        vibration.stop()
        battery.stop()
        camera.stop()
        mic.stop()
        bluetooth.stop()
        motion.stop()
        runCatching { guestRunner.run(listOf("/system/bin/sync"), 5_000) }
        killAll()
        adb.stop(); props.stop(); input.stop(); frames.stop(); ril.stop(); vold.stop(); audio.stop(); net.stop(); dns.stop(); events.stop(); logd.stop(); hwbinder.stop()
        extraStubs.forEach { it.stop() }; extraStubs.clear()
        lmk?.stop(); lmk = null
        Keeper.release(ctx)
        setState(State.STOPPED)
    }

    private fun killAll() {
        val list = synchronized(procs) { procs.values.toList() }
        for (g in pgids) runCatching { android.system.Os.kill(-g, android.system.OsConstants.SIGKILL) }
        list.forEach { runCatching { it.destroyForcibly() } }
        synchronized(procs) { procs.clear() }
        pgids.clear()
        killLeftovers()
    }

    /** Добивает процессы гостя, оставшиеся от прошлых запусков (qemu, binderd, dhdrun). */
    fun killLeftovers(): Int {
        val mine = AProcess.myPid()
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == mine) return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1).replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            val ours = (cmd.contains("/images/") && (cmd.contains("libqemu") || cmd.contains("libbinderd") || cmd.contains("libdhdrun") || cmd.contains("libglserverd"))) ||
                cmd.startsWith("${ctx.packageName}:binder") || cmd.startsWith("${ctx.packageName}:gl")
            if (ours) { runCatching { AProcess.sendSignal(pid, 9) }; n++ }
        }
        if (n > 0) log("cleaned up processes from previous run: $n")
        return n
    }

    // ------------------------------------------------------------------ служебное

    fun bootSeconds(): Long = if (bootAt == 0L) 0 else ((if (bootDoneAt > 0) bootDoneAt else System.currentTimeMillis()) - bootAt) / 1000

    /**
     * One-shot `sh -c` in the guest. Same setup as the adb shell: /system/bin first in PATH and shell functions for
     * script tools (am, pm, content, settings...), because qemu cannot exec a script directly and sh then reports
     * "<tool>: not found".
     */
    fun guestShell(cmd: String, timeoutMs: Long = 60_000): String {
        val r = guestRunner
        val path = GuestRunner.shellPath(r.env()["PATH"])
        return r.run(listOf("/system/bin/sh", "-c", GuestRunner.scriptFns(paths.root) + cmd), timeoutMs, mapOf("PATH" to path)).second
    }

    fun rotateGuest(): Pair<Int, String> = guestRunner.run(listOf(appProcess,
        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.RotationCtl"),
        15_000, mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))

    fun installApk(apkOnSdcard: String): String =
        guestRunner.run(listOf("/system/bin/pm", "install", "-r", apkOnSdcard), 600_000).second

    private fun pidOf(p: Process): Int? {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == "pid" }
            if (f != null) { f.isAccessible = true; return f.getInt(p) }
            c = c.superclass
        }
        return null
    }

    private fun gmtZone(): String {
        val h = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
        return if (h == 0) "Etc/GMT" else "Etc/GMT" + (if (h > 0) "-$h" else "+${-h}")
    }

    private fun isLinkTo(f: File, target: String) = runCatching { android.system.Os.readlink(f.absolutePath) == target }.getOrDefault(false)

    private fun stamp() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}