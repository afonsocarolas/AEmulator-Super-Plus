/* Modified for AEmulator Sunset, 2026-10-03: source-backed host motion HAL.
 * Modified for AEmulator Super Plus, 2026-10-11: Android 8.x healthd in /system/bin; camcorder profiles for video.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Подгонка дерева прошивки под запуск без ядра и железа.
 *  - [sanitize] выполняется один раз после импорта: убирает вендорские драйверы, которые полезли бы
 *    в несуществующее железо (GPU, композитор, OMX, датчики, камера…). Файлы не удаляются —
 *    переносятся в /system/.aemu-parked, чтобы можно было вернуть.
 *  - [fixup] выполняется перед каждым запуском: кладёт файлы движка, создаёт узлы /dev и /sys,
 *    каталоги, которые создаёт init, восстанавливает владельцев и т.п.
 */
class TreeFixer(
    private val ctx: Context,
    private val paths: VmPaths,
    private val img: GuestImage,
    private val log: (String) -> Unit,
) {
    private val root get() = paths.root
    private val engine get() = img.engine

    // ---------------------------------------------------------------- однократно после импорта

    fun sanitize() {
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var n = 0
        fun park(f: File) {
            if (!f.exists() && !isLink(f)) return
            val rel = f.relativeTo(root).path.replace('/', '#')
            val dst = File(parked, rel)
            dst.delete()
            if (f.renameTo(dst)) n++
        }
        // 1. HAL-модули: оставляем только нейтральные *.default.so и безвредные классы
        val keepClasses = setOf("audio_policy", "local_time", "power", "keystore", "consumerir", "vibrator", "memtrack")
        val parkClasses = setOf(
            "gralloc", "hwcomposer", "copybit", "overlay", "sensors", "camera", "gps", "nfc", "nfc_nci",
            "lights", "audio", "fm", "bluetooth", "hdmi", "tv", "display", "ir", "wlan", "gpu", "fingerprint",
        )
        for (dir in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                val name = f.name
                if (!name.endsWith(".so")) return@forEach
                val cls = name.substringBefore('.')
                val variant = name.removeSuffix(".so").substringAfter('.', "")
                val isDefault = variant == "default" || variant == "goldfish"
                when {
                    // родной gralloc.default прошивки работает с fb0, который эмулирует qemu, — оставляем
                    cls == "gralloc" && isDefault -> {}
                    // звук даёт движок
                    cls == "gralloc" || (cls == "audio" && variant == "primary.default") -> park(f)
                    cls == "audio" && (variant.startsWith("a2dp") || variant.startsWith("usb") || variant.startsWith("r_submix")) -> {}
                    cls in keepClasses && isDefault -> {}
                    cls == "audio_policy" -> if (!isDefault) park(f)
                    cls in parkClasses -> park(f)
                    !isDefault && cls !in keepClasses -> park(f)
                    !isDefault -> park(f)
                }
            }
        }
        // 2. вендорские драйверы OpenGL: их место займёт GL-мост
        for (dir in listOf("system/lib/egl", "vendor/lib/egl", "system/vendor/lib/egl")) {
            File(root, dir).listFiles()?.forEach { f ->
                val nm = f.name
                val vendorGl = (nm.startsWith("libEGL_") || nm.startsWith("libGLESv1_CM_") || nm.startsWith("libGLESv2_") ||
                    nm.startsWith("libGLESv3_") || (nm.startsWith("libGLES_") && nm != "libGLES_android.so") ||
                    nm.startsWith("libq3dtools") || nm.startsWith("eglsubAndroid") || nm.startsWith("libRBEGL") || nm.startsWith("libRBGLES"))
                if (vendorGl) park(f)
            }
        }
        // 3. аппаратные кодеки OMX переполняют стек OMXMaster и роняют mediaserver
        for (rel in listOf("system/lib/libstagefrighthw.so", "vendor/lib/libstagefrighthw.so", "system/vendor/lib/libstagefrighthw.so")) park(File(root, rel))
        // 4. заслонки ядра: /dev/binder как каталог, группы планировщика
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
        log("firmware prep: vendor modules set aside: $n")
    }

    // ---------------------------------------------------------------- перед каждым запуском

    /**
     * Precompiled code of 5.0+ system apps and of the framework (the odex files in oat/arm and framework/arm) is the only copy: the jars
     * and apks are stripped. installd's dexopt on a fresh /data replaced or unlinked those files inside /system
     * (a real /system is read-only), and the next boot died in system_server with "No original dex files found".
     * Taking the write bit away makes such writes fail harmlessly; ImageStore.wipe gives it back before deleting.
     */
    private fun protectOat() {
        val sys = File(root, "system")
        var n = 0
        fun lock(d: File) {
            d.listFiles()?.forEach { f -> if (f.isFile) { if (f.canWrite()) { f.setWritable(false, false); n++ } } else if (f.isDirectory) lock(f) }
            d.setWritable(false, false)
        }
        File(sys, "framework").listFiles()?.filter { it.isDirectory && (it.name == "oat" || it.name == "arm" || it.name == "arm64") }?.forEach { lock(it) }
        for (top in listOf("app", "priv-app")) File(sys, top).listFiles()?.forEach { app ->
            File(app, "oat").takeIf { it.isDirectory }?.let { lock(it) }
        }
        if (n > 0) log("oat files write-protected: $n")
    }

    fun fixup(owners: Boolean = true) {
        if (owners) seedOwners()
        installEngineFiles()
        installSensorHal()
        File(root, "system/framework/aemu-setup.jar").let { dst ->
            dst.parentFile?.mkdirs()
            ctx.assets.open("engines/common/aemu-setup.jar").use { src -> dst.outputStream().use { src.copyTo(it) } }
            dst.setReadable(true, false)
        }
        installCameraHal()
        protectOat()
        fixThemeXml()
        parkWhetstone()
        installCupcakeRenderer()
        parkPlayReady()
        swapMtkAudioHal()
        // glibc (Google TV) firmware: the engine's GL shims are bionic libraries (NEEDED libdl.so / libc.so) and cannot
        // be loaded by a glibc userland ("load_driver(libGLES_android.so): libdl.so: cannot open shared object file",
        // then no EGL display, SurfaceFlinger cannot pick a config and system_server dies with SIGSEGV).
        // They also replace the firmware's own libGLES_android.so, so use the firmware's software renderer instead.
        eglConfig(img.settings.gpu && !GuestPreload.isGlibcFirmware(root))
        makeDataDirs()
        runCatching {
            if (GuestRootAliases.ensureEtc(root)) log("init: restored /etc -> /system/etc alias")
        }.onFailure { log("init: could not prepare /etc alias: ${it.message}") }
        makeUserZeroLink()
        makeDevNodes()
        makeSysNodes()
        makeProcMounts()
        vendorAudioPolicy()
        audioPolicy()
        audioOpenSlot()
        seedSamsungCsc()
        qemuAudioPolicy()
        qtaguid()
        vendorChecks()
        flattenedLinks()
        shellLink()
        pldHints()
        berlinOsalGuard()
        hdmiRxGuard()
        appProcessLink()
        scriptShebangs()
        samsungEfs()
        selinuxOff()
        surfaceGuard()
        surfaceFlingerGuard()
        glassGestureGuard()
        glassBluetoothPark()
        disableStallingPackages()
        glassLocationProvider()
        seedDefaultIme()
        for (n in LOGS) File(root, "dev/log/$n").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        makeFb()
    }

    /**
     * Скрипты am/pm/input/monkey… в старых прошивках начинаются с «# Script…» без #!. Ядро на такой
     * execve отвечает ENOEXEC и mksh запускает файл сам, а qemu вместо этого падает «Exec format error».
     */
    /** Only app_process32 shipped: add the app_process link the pm/am/input scripts and our helper services expect. */
    private fun appProcessLink() {
        if (AppProcess.ensureLink(root)) log("app_process: only app_process32 present, linked system/bin/app_process -> app_process32")
    }

    private fun scriptShebangs() {
        val ap = AppProcess.path(root)
        var fixed = 0
        val failed = ArrayList<String>()
        // the commands (system/bin, system/xbin) plus the *.sh helpers the framework runs itself (WifiHW runs
        // system/etc/wifi/wifi_device_detect.sh: "Error while loading ...: Exec format error" in logcat)
        val files = ArrayList<File>()
        for (dir in listOf("system/bin", "system/xbin")) File(root, dir).listFiles()?.let { files += it }
        File(root, "system/etc").walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".sh") }.forEach { files += it }
        run {
            for (f in files) {
                if (!f.isFile || f.length() > 64_000 || java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(2).also { s.read(it) } } }.getOrNull() ?: continue
                // am/pm/input/monkey…: "exec app_process" is looked up through PATH, and the default PATH of 2.2–4.x/Google TV
                // starts with /sbin:/vendor/bin:/system/sbin, so run from a plain shell (adb shell) it fails where the same
                // command with /system/bin first works. These scripts all set base=/system: call it by its full path.
                if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                    runCatching {
                        val t = f.readText(Charsets.ISO_8859_1)
                        val u = AppProcess.fixExec(t, ap)
                        if (u != t) { f.writeText(u, Charsets.ISO_8859_1); fixed++ }
                    }
                    continue
                }
                if (head[0] != '#'.code.toByte()) continue
                // a read-only or odd-owner file made writeBytes() fail silently and left "pm: Exec format error" behind:
                // retry through a temp file that replaces the original, keeping the mode bits
                val ok = runCatching {
                    val body = ("#!/system/bin/sh\n" + AppProcess.fixExec(String(f.readBytes(), Charsets.ISO_8859_1), ap)).toByteArray(Charsets.ISO_8859_1)
                    val exec = f.canExecute()
                    try { f.writeBytes(body) } catch (_: Exception) {
                        f.setWritable(true, true)
                        val tmp = File(f.parentFile, f.name + ".aemu-tmp")
                        tmp.writeBytes(body)
                        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); error("cannot replace") } }
                    }
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                    if (exec) f.setExecutable(true, false)
                }.isSuccess
                if (ok) fixed++ else failed += f.name
            }
        }
        if (fixed > 0) log("scripts: #! added to $fixed shell script(s) and \"exec app_process\" made absolute (system/bin, system/xbin, system/etc/*.sh)")
        if (failed.isNotEmpty()) log("scripts: could not add #! to ${failed.joinToString()}")
    }

    /**
     * Samsung: без /efs/FactoryApp/factorymode = ON прошивка считает себя заводской и поверх всего
     * рисует таблицу «PDA / CSC / RF Cal Date…», а часть служб работает в режиме заводского теста.
     */
    private fun samsungEfs() {
        val efs = File(root, "efs")
        if (!efs.isDirectory) return
        val dir = File(efs, "FactoryApp").apply { mkdirs() }
        for (n in listOf("factorymode", "keystr")) {
            val f = File(dir, n)
            if (runCatching { f.readText().trim() }.getOrNull() != "ON") runCatching { f.writeText("ON") }
        }
    }

    private fun qcomAudioFlinger(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && String(af.readBytes(), Charsets.ISO_8859_1).contains("setFmVolume")
    }.getOrDefault(false)

    /**
     * Samsung 4.0 AudioFlinger (AudioPolicyService::getParamFromPolicy) calls an extra slot (+108) of the audio_policy
     * struct that only Samsung's own policy library (audio_policy.<board>.so) fills in. The generic
     * audio_policy.default.so leaves it null, so the first getParameters("situationVolume") from the framework killed
     * mediaserver with PC=0. When the firmware ships that library (sanitize parked it) it is installed as the
     * default policy module; the generic one is kept aside.
     */
    private fun vendorAudioPolicy() {
        if (img.api >= 16) return
        runCatching {
            val af = File(root, "system/lib/libaudioflinger.so")
            val marker = "getParamFromPolicy".toByteArray(Charsets.ISO_8859_1)
            fun has(b: ByteArray): Boolean {
                outer@ for (i in 0..b.size - marker.size) {
                    for (k in marker.indices) if (b[i + k] != marker[k]) continue@outer
                    return true
                }
                return false
            }
            if (!af.isFile || !has(af.readBytes())) return
            val parkedDir = File(root, "system/.aemu-parked")
            val hw = File(root, "system/lib/hw")
            val candidates = (parkedDir.listFiles()?.filter { it.name.startsWith("system#lib#hw#audio_policy.") && it.name.endsWith(".so") && !it.name.endsWith("#audio_policy.default.so") } ?: emptyList()) +
                (hw.listFiles()?.filter { it.name.startsWith("audio_policy.") && it.name.endsWith(".so") && it.name != "audio_policy.default.so" } ?: emptyList())
            val vendor = candidates.firstOrNull { has(it.readBytes()) } ?: return
            val dst = File(hw, "audio_policy.default.so")
            val bytes = vendor.readBytes()
            if (dst.isFile && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)) return
            val keep = File(parkedDir, "audio_policy.default.so.generic")
            if (!keep.isFile && dst.isFile) { parkedDir.mkdirs(); dst.copyTo(keep) }
            dst.writeBytes(bytes)
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            log("audio: Samsung audio policy (${vendor.name.substringAfterLast('#')}) installed as the default policy")
        }.onFailure { log("audio: Samsung audio policy install failed: ${it.message}") }
    }

    /**
     * Samsung firmware reads /system/csc/customer.xml (carrier customisation), normally supplied by the CSC partition,
     * which the importer skips. Without it Messaging (com.android.mms.csc.CscParser.search, "File didn't exist") dies with
     * a NullPointerException in Application.onCreate, instantly on every launch. An empty but well-formed file is enough
     * for the parser to find "no carrier overrides".
     */
    private fun seedSamsungCsc() {
        runCatching {
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val f = File(root, "system/csc/customer.xml")
            if (!samsung || f.exists() || !File(root, "system/app").isDirectory) return
            f.parentFile?.mkdirs()
            f.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CustomerData>\n  <GeneralInfo>\n  </GeneralInfo>\n  <Settings>\n  </Settings>\n</CustomerData>\n")
            f.setReadable(true, false)
            f.parentFile?.setReadable(true, false); f.parentFile?.setExecutable(true, false)
            log("csc: wrote an empty /system/csc/customer.xml (Samsung Messaging needs the file)")
        }.onFailure { log("csc: cannot write customer.xml: ${it.message}") }
    }

    /**
     * Samsung KitKat AudioFlinger calls open_output_stream at another offset of audio_hw_device than AOSP does (108):
     * on SM-C115M 4.4.2 the call landed on a stub of the stand HAL, "Failed to open primary output" followed and every
     * audio policy (Samsung's, then the AOSP stand-in) died in mediaserver with no output to work with. The offset is
     * read from the firmware's own libaudioflinger and handed to the HAL in /aemu.audio.slot; the HAL moves the end of
     * its device structure accordingly. No file means the AOSP layout.
     */
    private fun audioOpenSlot() {
        val f = File(root, "aemu.audio.slot")
        runCatching {
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val af = File(root, "system/lib/libaudioflinger.so")
            val slot = if (img.api >= 16 && samsung && af.isFile) AudioHalAbi.openOutputSlot(af.readBytes()) else null
            if (slot != null && slot > 108 && slot <= 172 && slot % 4 == 0) {
                if (!f.isFile || f.readText().trim() != slot.toString()) f.writeText("$slot\n")
                f.setReadable(true, false)
                log("audio: AudioFlinger opens the output at device slot $slot (AOSP 108), HAL layout shifted")
            } else {
                if (f.exists()) f.delete()
                if (img.api >= 16 && samsung) log("audio: AudioFlinger open_output_stream slot: ${slot ?: "unknown"}")
            }
        }.onFailure { log("audio: cannot write aemu.audio.slot: ${it.message}") }
    }

    /**
     * The mediaserver runs with ro.kernel.qemu=1 (emulator audio output). Samsung 2.x AudioPolicyService() skips
     * createAudioPolicyManager() in that mode and then calls setSystemProperty() on the NULL policy manager:
     * mediaserver died with SIGSEGV at the first start, every start (see ElfPatch.policyBranchIn).
     * The firmware's libaudioflinger is patched to always create its policy; the stock file is kept aside.
     */
    private fun qemuAudioPolicy() = runCatching {
        val lib = File(root, "system/lib/libaudioflinger.so")
        if (!lib.isFile) return@runCatching
        val keep = File(root, "system/.aemu-parked/system#lib#libaudioflinger.so")
        val d = lib.readBytes()
        if (ElfPatch.policyBranchOffsets(d).isEmpty()) { // not this firmware, or already patched
            log("audio: libaudioflinger policy branch not found (already patched or different build)")
            return@runCatching
        }
        if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
        if (ElfPatch.policyManagerAlways(lib) > 0) log("audio: AudioPolicyService creates its policy in emulator mode (libaudioflinger patched)")
        else log("audio: libaudioflinger policy patch was not written")
    }.onFailure { log("audio: libaudioflinger policy patch failed: ${it.message}") }

    private fun samsungDeviceSlots(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == 116
    }.getOrDefault(false)

    private fun openOutputSlotIs(slot: Int): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == slot
    }.getOrDefault(false)

    private fun directTrackAudio(): Boolean = img.api in 19..20 && runCatching {
        AudioHalAbi.usesDirectTrackTail(File(root, "system/lib/libnbaio.so").readBytes())
    }.getOrDefault(false)

    /**
     * MediaTek 4.x: libaudioflinger loads the primary HAL from /system/lib/libaudio.primary.default.so
     * (AudioMTKHardware, which needs the MTK sound driver — silent here). Park it as libaudio.mtk.so and put
     * our HAL there; it forwards the DcRemove filter AudioFlinger links from that library to the original.
     */
    private fun swapMtkAudioHal() {
        swapMtkAudioHalImpl()
        redirectMtkAudioDeps()
    }

    /**
     * Other MediaTek libraries link symbols that only MTK's own HAL has (libmtkplayer.so needs
     * AudioResourceManager::getInstance(); libmtkplayer is pulled in by libmediaplayerservice and, through
     * libandroid_servers, by system_server — both died at link time). The stand-in exports just the DcRemove filter,
     * so those libraries are pointed at the parked original instead; the path has exactly the length of the old
     * name and is the one the stand-in itself uses, so the library is loaded once per process.
     * libaudioflinger is left alone: it is the one that must see the stand-in.
     */
    private fun redirectMtkAudioDeps() = runCatching {
        if (!File(root, "system/lib/libaudio.mtk.so").isFile) return@runCatching
        var n = 0
        for (dir in listOf("system/lib", "system/lib/hw", "vendor/lib", "vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so") && f.name != "libaudioflinger.so" && f.name != "libaudio.mtk.so" &&
                    f.name != "libaudio.primary.default.so" && !isLink(f))
                    n += ElfPatch.redirectNeeded(f, "libaudio.primary.default.so", "/system/lib/libaudio.mtk.so")
            }
        }
        if (n > 0) log("audio: $n MediaTek librar${if (n == 1) "y" else "ies"} linked to the original HAL (libaudio.mtk.so)")
    }.onFailure { log("audio: HAL link redirect failed: ${it.message}") }

    private fun swapMtkAudioHalImpl() {
        if (engine != Engine.KK || img.api < 17) return
        val lib = File(root, "system/lib/libaudio.primary.default.so")
        val orig = File(root, "system/lib/libaudio.mtk.so")
        if (!orig.isFile) {
            if (!lib.isFile || !isMtkAudio(root)) return
            if (!lib.renameTo(orig)) return
        }
        val asset = "engines/kk/audio.primary.mtk.so"
        runCatching {
            // AudioFlinger asks libhardware for module class "libaudio"/"primary" here, and libhardware rejects a module
            // whose id differs ("load: id=libaudio != hmi->id=audio"). The same asset serves the AOSP-style loader
            // (hw/audio.primary.default.so, id "audio"), so only this copy gets the id changed.
            val raw = ctx.assets.open(asset).use { it.readBytes() }
            val hal = HalModuleId.retarget(raw, MTK_HAL_MODULE_ID)
            if (hal == null) log("audio: cannot set the HAL module id to $MTK_HAL_MODULE_ID, AudioFlinger will refuse it")
            val bytes = hal ?: raw
            if (lib.isFile && lib.length() == bytes.size.toLong() && lib.readBytes().contentEquals(bytes)) return
            lib.outputStream().use { it.write(bytes) }
            lib.setReadable(true, false)
            log("audio: MediaTek HAL replaced with the emulator HAL (module id $MTK_HAL_MODULE_ID)")
        }.onFailure { log("audio: MediaTek HAL swap failed: ${it.message}") }
    }

    /**
     * MIUI 8 reads system/media/theme/theme_compatibility.xml while zygote preloads classes. Some builds ship it with
     * nested comments ("<!-- <!-- ...") that Android's strict XML parser rejects; ThemeCompatibility and MiuiResources
     * then fail to initialise in every process and the system never finishes booting (black screen). Double hyphens
     * inside comments are separated so the file parses.
     */
    private fun fixThemeXml() {
        val f = File(root, "system/media/theme/theme_compatibility.xml")
        if (!f.isFile || f.length() > 4_000_000) return
        runCatching {
            val s = f.readText()
            if (!s.contains("<!-- <!--")) return
            val out = StringBuilder(s.length)
            var i = 0
            while (true) {
                val a = s.indexOf("<!--", i)
                if (a < 0) { out.append(s, i, s.length); break }
                out.append(s, i, a + 4)
                val e = s.indexOf("-->", a + 4)
                if (e < 0) { out.append(s, a + 4, s.length); break }
                out.append(s.substring(a + 4, e).replace("--", "- -")).append("-->")
                i = e + 3
            }
            f.writeText(out.toString())
            log("theme_compatibility.xml: nested comments fixed")
        }
    }

    /**
     * MIUI 8: ActivityManagerService calls MIUI's Whetstone service (an app process) while holding its own lock, and that
     * process in turn waits for the same lock. The watchdog then restarts system_server over and over and the boot never
     * finishes. Whetstone only does background-power bookkeeping, so its package is set aside.
     */
    /**
     * Android 1.5: Cupcake's libpixelflinger generates ARM code for its pixel pipelines in a malloc()ed buffer and jumps
     * into it. qemu-user does not make heap pages executable (old kernels did, READ_IMPLIES_EXEC), so SurfaceFlinger,
     * which runs inside system_server on 1.5, died with SIGSEGV right after "ARMAssembler: generated scanline...".
     * The engine's copy is the same Cupcake source built without the code generator (native/cupcakegl). The firmware's
     * own copy is parked once and is the one a later delta OTA patches.
     */
    private fun installCupcakeRenderer() = runCatching {
        if (!CupcakeCompat.applies(img.api)) return@runCatching
        val lib = File(root, "system/lib/libpixelflinger.so")
        val parked = File(root, "system/.aemu-parked/system#lib#libpixelflinger.so")
        if (!parked.exists() && lib.isFile) {
            parked.parentFile?.mkdirs()
            if (!lib.renameTo(parked)) return@runCatching
        }
        if (!parked.exists()) return@runCatching   // no renderer of its own: leave the tree alone
        val asset = ctx.assets.open("engines/common/libpixelflinger_cupcake.so").use { it.readBytes() }
        if (!lib.isFile || lib.length() != asset.size.toLong() || !lib.readBytes().contentEquals(asset)) {
            lib.writeBytes(asset)
            lib.setReadable(true, false)
            log("Android 1.5: software renderer without run-time code generation installed (libpixelflinger)")
        }
    }

    private fun parkWhetstone() {
        val apk = File(root, "system/app/Whetstone.apk")
        if (!apk.isFile || !File(root, "system/app/miuisystem.apk").isFile) return
        runCatching {
            val dir = File(root, "system/.aemu-parked").apply { mkdirs() }
            if (apk.renameTo(File(dir, "Whetstone.apk"))) {
                File(root, "data/dalvik-cache/system@app@Whetstone.apk@classes.dex").delete()
                log("MIUI: Whetstone package set aside (deadlock with the activity manager)")
            }
        }
    }

    /**
     * Samsung/TouchWiz ICS: drmserver loads every plugin from /system/lib/drm while it handles the first DRM
     * request after boot. libplayreadyplugin.so (Microsoft PlayReady) dereferences a NULL pointer there (SIGSEGV at
     * 0x4, exit 139) because its device-key / platform setup cannot succeed on the emulator. drmserver then dies,
     * 'drm.drmManager' disappears from servicemanager and every DrmManagerClient call blocks waiting for it.
     * The plugin only serves PlayReady-protected media, which can never play here, so it is set aside; drmserver
     * simply registers the remaining plugins (OMA/forward-lock). The stock file is kept for a later delta OTA.
     */
    private fun parkPlayReady() = runCatching {
        val plugin = File(root, "system/lib/drm/libplayreadyplugin.so")
        if (!plugin.isFile && !isLink(plugin)) return@runCatching
        val parked = File(root, "system/.aemu-parked/system#lib#drm#libplayreadyplugin.so")
        parked.parentFile?.mkdirs()
        val moved = if (isLink(plugin) || parked.exists()) plugin.delete() else plugin.renameTo(parked)
        if (moved) log("DRM: PlayReady plugin set aside (drmserver crashed in it)")
    }.onFailure { log("DRM: could not set aside the PlayReady plugin: ${it.message}") }

    private fun installCameraHal() {
        val enabled = img.settings.camera && engine == Engine.KK && img.api in 14..25
        // video: the camera app records the camcorder profile's size, which the bridge has to deliver (640x480)
        runCatching {
            if (CamcorderProfiles.configure(root, enabled))
                log(if (enabled) "camera: camcorder profiles set to ${CamcorderProfiles.WIDTH}x${CamcorderProfiles.HEIGHT} (original kept)" else "camera: original camcorder profiles restored")
        }.onFailure { log("camera: camcorder profiles not changed: ${it.message}") }
        val destination = File(root, "system/lib/hw/camera.aemu_host.so")
        if (!enabled) {
            if (CameraHalFallback.configure(root, destination, false)) log("camera: restored previous default HAL slot")
            return
        }
        // Dedicated loader variant: never overwrite a stock camera.default/vendor HAL.
        val asset = "engines/common/camera.aemu_host.so"
        if (destination.isFile && sameContent(asset, destination)) {
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
            return
        }
        val backup = File(root, "system/.aemu-parked/system#lib#hw#camera.aemu_host.so")
        runCatching {
            if (destination.exists() || isLink(destination)) {
                backup.parentFile?.mkdirs()
                if (!backup.exists() && !destination.renameTo(backup)) error("cannot preserve existing camera HAL")
                if (isLink(destination)) Os.remove(destination.absolutePath)
            }
            destination.parentFile?.mkdirs()
            ctx.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
            destination.setReadable(true, false); destination.setExecutable(true, false)
            log("camera: standard HAL1 host-camera bridge installed")
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
        }.onFailure { error("camera HAL installation failed: ${it.message}") }
    }

    private fun installSensorHal() {
        if (img.api !in 9..25) return
        // Park OEM names too: old libhardware loaders can ignore ro.hardware.sensors.
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (directory in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, directory).listFiles()?.filter { it.name.startsWith("sensors.") && it.name.endsWith(".so") }?.forEach { file ->
                val dest = File(parked, (directory + "/" + file.name).replace('/', '#'))
                if (!dest.exists()) check(file.renameTo(dest)) { "Cannot park sensor HAL" } else check(file.delete())
            }
        }
        for (name in listOf("sensors.aemu_host.so", "sensors.default.so")) {
            val file = File(root, "system/lib/hw/$name")
            file.parentFile?.mkdirs()
            ctx.assets.open("engines/common/sensors.aemu_host.so").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.setReadable(true, false)
        }
        log("motion: installed legacy host sensor HAL")
    }

    private fun installEngineFiles() {
        // the fake SHM devices (libshmshim.so) keep their allocator state in these files: start every boot from scratch
        File(root, "data/local/tmp").listFiles()?.filter { it.name == "shm_cache.bin" || it.name == "shm_noncache.bin" }?.forEach { it.delete() }
        val copies = when (engine) {
            Engine.KK -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES.so" to "system/lib/egl/libGLES_bridge.so",
                "audio.primary.default.so" to "system/lib/hw/audio.primary.default.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
                "fbpaint" to "system/bin/fbpaint",
                "netprobe" to "system/bin/netprobe",
                "aemu-stubs.jar" to "system/framework/aemu-stubs.jar",
                "aemu-bt.jar" to "system/framework/aemu-bt.jar",
                "aemu-wifi.jar" to "system/framework/aemu-wifi.jar",
                "aemu-sensorhub.jar" to "system/framework/aemu-sensorhub.jar",
            )
            Engine.GB -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES_dhd.so" to "system/lib/egl/libGLES_dhd.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
                "fbpaint" to "system/bin/fbpaint",
            )
        }
        // образы, подготовленные старой версией: вернуть родной gralloc.default из «парковки»
        val parkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so")
        if (parkedGralloc.isFile) {
            val g = File(root, "system/lib/hw/gralloc.default.so")
            g.delete(); parkedGralloc.renameTo(g)
        }
        // gralloc.default, который выделяет память через ION (MediaTek и др.), без ядра не работает:
        // устройство выделения получается пустым и SurfaceFlinger падает — ставим gralloc движка
        val romGralloc = File(root, "system/lib/hw/gralloc.default.so")
        if (engine == Engine.KK && romGralloc.isFile && romGralloc.length() < 2_000_000) {
            val text = runCatching { String(romGralloc.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            if (text.contains("libion.so") || text.contains("ion_alloc")) {
                val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
                parked.parentFile?.mkdirs()
                if (romGralloc.renameTo(parked)) log("firmware gralloc uses ION, replaced with engine gralloc")
            }
        }
        var copied = 0
        // 7.0+ libEGL on a qemu kernel (qemu.gles=1) only tries the "emulation" driver name
        val nougat = if (img.api >= 24 && copies.any { it.second == "system/lib/egl/libGLES_bridge.so" })
            listOf("libGLES_split.so" to "system/lib/egl/libGLES_emulation.so") else emptyList()
        // 7.0+ without the GPU bridge: libEGL would still find the bridge by scanning egl/, so it is not there at all
        val noBridge = img.api >= 24 && !img.settings.gpu
        if (noBridge) {
            for (n in listOf("libGLES_bridge.so", "libGLES_emulation.so")) File(root, "system/lib/egl/$n").delete()
            // a scan skips libGLES_android.so; in "vendor software renderer" mode (qemu.gles=2) libEGL asks for
            // libGLES_swiftshader.so, which here is the stock software renderer
            val sw = File(root, "system/lib/egl/libGLES_swiftshader.so")
            if (!sw.exists() && !isLink(sw) && File(root, "system/lib/egl/libGLES_android.so").isFile)
                runCatching { Os.symlink("libGLES_android.so", sw.path) }
        }
        for ((from, to) in (copies + nougat).filter { !noBridge || !it.second.startsWith("system/lib/egl/libGLES_") } +
            listOf("@libaemushim.so" to "system/lib/libaemushim.so") +
            // Android 8+: the guest side of /dev/hwbinder and /dev/vndbinder (HwBinderDaemon)
            (if (img.api >= 26) listOf("@libaemuhwb.so" to GuestPreload.HWB.removePrefix("/")) else emptyList()) +
            // glibc (Google TV) firmware: fake /dev/shm_cache and /dev/shm_noncache so av_settings & co. can start
            (if (GuestPreload.isGlibcFirmware(root)) listOf("@libshmshim.so" to GuestPreload.SHMSHIM.removePrefix("/")) else emptyList())) {
            val dst = File(root, to)
            // gralloc движка — только если в прошивке своего нет
            if (from == "gralloc.default.so" && dst.isFile) continue
            // «@» — общий для всех движков файл
            // звуковой HAL стенда разложен под AudioFlinger HTC; остальным 4.2+ — вариант с раскладкой AOSP.
            // У Samsung свой audio_stream_out (лишние слоты) — с ним AOSP-вариант роняет mediaserver,
            // поэтому там остаётся исходный: выход не открывается, система работает без звука.
            // Samsung 4.3 AudioFlinger uses the KitKat slots (verified on I9300 XXUGNJ2: init_check 0x44,
            // open_output_stream 0x6c, stream write 0x40), so only 4.1–4.2 TouchWiz keeps the stand HAL
            // Samsung 4.0 (api 14-15, e.g. GT-P7300) has the ICS device layout (no get_master_volume /
            // set_master_mute slots): the stand HAL's JB layout put a data pointer in a slot AudioFlinger calls
            // right after get_supported_devices (SIGSEGV pc inside libaudioflinger .data), so it takes the ICS HAL
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val htcLike = img.skin.contains("HTC", true) || (samsung && img.api in 16..17)
            val mtkHw = from == "audio.primary.default.so" && engine == Engine.KK && isMtkHwOnlyAudio(root)
            val name = when {
                mtkHw -> "audio.primary.mtk.so"
                from != "audio.primary.default.so" || htcLike -> from
                // 4.0 has its own audio_hw_device layout; Qualcomm CAF builds add set_fm_volume/open_output_session
                img.api in 14..15 -> when {
                    qcomAudioFlinger() -> "audio.primary.ics-qcom.so"
                    // the +0xc-shifted device layout only when this firmware's AudioFlinger really calls
                    // open_output_stream at +116; stock ICS (+104, e.g. GT-P7300 4.0.4) must keep the plain ICS HAL,
                    // otherwise AudioFlinger calls set_parameters instead and mediaserver dies in readOutputParameters
                    samsung && samsungDeviceSlots() -> "audio.primary.ics-samsung.so"
                    // ICS 4.0.x build with the JB/KK device layout (get_master_volume slot present, e.g. Nexus Q):
                    // open_output_stream is at +108, which the plain ICS HAL leaves as close_output_stream -> SIGSEGV
                    // in mediaserver at AudioFlinger::openOutput. The AOSP-layout HAL matches that slot.
                    openOutputSlotIs(108) -> "audio.primary.aosp.so"
                    else -> "audio.primary.ics.so"
                }
                // Samsung KitKat+ whose AudioFlinger calls open_output_stream at +116 (the +0xc-shifted device layout):
                // the AOSP-layout HAL answers that slot with another method, the policy gets no primary output
                // ("Failed to open primary output", init_check "No such device") and mediaserver then dies in the
                // half-initialised policy (heap corruption in audio_policy.default.so, code 139)
                samsung && img.api >= 18 && samsungDeviceSlots() -> {
                    log("audio: Samsung AudioFlinger calls open_output_stream at +116, using the shifted-layout HAL")
                    "audio.primary.ics-samsung.so"
                }
                directTrackAudio() -> "audio.primary.directtrack.so"
                img.api >= 16 -> "audio.primary.aosp.so"
                else -> from
            }
            val asset = if (name.startsWith("@")) "engines/common/${name.drop(1)}" else "engines/${engine.id}/$name"
            val size = runCatching { ctx.assets.openFd(asset).use { it.length } }.getOrDefault(-1L)
            // одинаковый размер ещё не значит тот же файл (правки движка на месте, варианты HAL) — сверяем CRC
            if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
            if (mtkHw) runCatching {
                // keep the firmware's own HAL (never one of ours): isMtkAudio keeps working after the swap
                val parked = File(root, "system/.aemu-parked/system#lib#hw#audio.primary.default.so")
                if (!parked.isFile && dst.isFile) { parked.parentFile?.mkdirs(); dst.copyTo(parked) }
                log("audio: MediaTek HAL layout in hw/audio.primary.default.so")
            }
            runCatching {
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                ctx.assets.open(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
                copied++
                if (name == "audio.primary.directtrack.so") log("audio: verified CAF direct-track stream ABI selected")
            }.onFailure { log("${from.removePrefix("@")} missing from engine set: ${it.message}") }
        }
        // вендорские драйверы RenderScript (Adreno, Mali…) лезут в GPU; без них libRS берёт процессорный
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            File(root, dir).listFiles()?.filter { it.name.startsWith("libRSDriver_") }?.forEach { f ->
                val parked = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (f.renameTo(parked)) log("RenderScript: removed vendor driver ${f.name}")
            }
        }
        // netfilter в эмуляторе нет: iptables всегда падает, а netd 4.x (Samsung) считает это фатальным
        // для NetworkManagementService/ConnectivityService — ставим пустую программу, родную прячем
        val trueSize = runCatching { ctx.assets.openFd("engines/common/aemu_true.so").use { it.length } }.getOrDefault(-1L)
        // MIUI: invoke-as runs FirewallService's iptables loop as root; under qemu that shell never sees its
        // children exit, so ConnectivityService (and the whole boot) hangs in CommandLineUtils.waitFor
        val fakes = listOf("bin/iptables", "bin/ip6tables") + if (img.skin.contains("MIUI", true)) listOf("xbin/invoke-as") else emptyList()
        for (rel in fakes) {
            val n = rel.substringAfter('/')
            val f = File(root, "system/$rel")
            if (!f.exists() && !isLink(f) || trueSize < 0 || (!isLink(f) && f.length() == trueSize)) continue
            runCatching {
                val parked = File(root, "system/.aemu-parked/system#${rel.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (isLink(f)) Os.remove(f.absolutePath) else if (!parked.exists()) f.renameTo(parked) else f.delete()
                ctx.assets.open("engines/common/aemu_true.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                f.setReadable(true, false); f.setExecutable(true, false)
                copied++
            }.onFailure { log("$n not replaced: ${it.message}") }
        }
        // камера 2.3: заглушка вместо вендорской libcamera.so, которая лезет в /dev/msm_camera
        val cam = File(root, "system/lib/libcamera.so")
        val stamp = File(root, "system/lib/libcamera.so.aemu")
        val parkedCam = File(root, "system/.aemu-parked/system#lib#libcamera.so")
        val htc = img.skin.contains("HTC", true)
        // заглушка собрана под интерфейс камеры HTC — у других производителей оставляем родную
        if (!htc && stamp.isFile && parkedCam.isFile) { cam.delete(); parkedCam.renameTo(cam); stamp.delete() }
        if (engine == Engine.GB && htc) {
            if (cam.isFile && !stamp.isFile) {
                runCatching {
                    File(root, "system/.aemu-parked").mkdirs()
                    cam.copyTo(File(root, "system/.aemu-parked/system#lib#libcamera.so"), overwrite = true)
                    ctx.assets.open("engines/gb/libcamera.so").use { i -> cam.outputStream().use { o -> i.copyTo(o) } }
                    stamp.writeText("1")
                }
            }
        }
        if (copied > 0) log("engine ${engine.id} files updated: $copied")
    }

    /** Направляет загрузчик EGL прошивки на GL-мост (или на программный растеризатор). */
    private fun eglConfig(gpu: Boolean) {
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val cfg = File(dir, "egl.cfg")
        val cfgRom = File(dir, "egl.cfg.rom")
        // Motorola (e.g. XT910 4.0.4): egl.cfg is a symlink to /sys/egl/egl.cfg, which doesn't exist on the
        // host, so reading/writing through it fails with ENOENT and aborts boot. Replace it with a real file.
        if (isLink(cfg)) runCatching { Os.remove(cfg.absolutePath) }.onFailure { log("egl.cfg: symlink removal failed: ${it.message}"); cfg.delete() }
        if (!cfgRom.isFile && cfg.isFile) cfg.copyTo(cfgRom, overwrite = true)
        when (engine) {
            Engine.GB -> cfg.writeText(if (gpu) "0 0 dhd\n" else "0 0 android\n")
            Engine.KK -> {
                // сам мост лежит как libGLES_bridge.so; все имена, которые ищут загрузчики EGL (libGLES.so у 4.4,
                // libGLES_<тег>.so по egl.cfg, раздельные libEGL_/libGLESv*_), занимает переходник libGLES_split:
                // он чинит загрузку текстур, сводит раздельные библиотеки в один мост и даёт SurfaceFlinger ES2
                val bridge = File(dir, "libGLES_bridge.so")
                val sw = File(dir, "libGLES_android.so")
                val swOff = File(dir, "libGLES_android.so.sw")
                val names = listOf("libGLES.so", "libGLES_aemu.so", "libGLES_android.so", "libEGL_aemu.so", "libGLESv1_CM_aemu.so", "libGLESv2_aemu.so")
                if (gpu && bridge.isFile) {
                    if (!swOff.isFile && sw.isFile && sw.length() != splitSize()) sw.renameTo(swOff)
                    runCatching {
                        for (n in names) {
                            val f = File(dir, n)
                            if (f.isFile && f.length() == splitSize()) continue
                            ctx.assets.open("engines/kk/libGLES_split.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                            f.setReadable(true, false); f.setExecutable(true, false)
                        }
                    }.onFailure { log("GL: shim failed to install: ${it.message}") }
                    // часть загрузчиков (Samsung 4.3) берут последнюю строку, остальные — строку с impl=1
                    cfg.writeText("0 0 android\n0 1 aemu\n")
                } else {
                    // программная отрисовка: убираем все наши имена, иначе загрузчик 4.4 всё равно найдёт libGLES.so
                    for (n in names) if (n != "libGLES_android.so") File(dir, n).delete()
                    if (swOff.isFile) { sw.delete(); swOff.renameTo(sw) }
                    cfg.writeText("0 0 android\n")
                }
            }
        }
    }

    private fun sameContent(asset: String, f: File): Boolean = runCatching {
        fun crc(i: java.io.InputStream): Long {
            val c = java.util.zip.CRC32(); val b = ByteArray(1 shl 16)
            while (true) { val n = i.read(b); if (n < 0) break; c.update(b, 0, n) }
            return c.value
        }
        ctx.assets.open(asset).use { crc(it) } == f.inputStream().use { crc(it) }
    }.getOrDefault(false)

    /** Пустые таблицы xt_qtaguid, на которые гостевая прослойка подменяет /proc/net/xt_qtaguid/… */
    /**
     * Images imported from a zip that lost its symlinks have system/bin/sh as a 4-byte text file ("mksh"), so
     * `adb shell` ends in "Error while loading .../system/bin/sh: Exec format error", and so does every toolbox applet
     * (ls, cat, ...) that is a 7-byte file saying "toolbox". Such images are repaired here, once per image
     * (see [FlattenedLinks]); new imports are repaired by Importer.finishTree.
     */
    private fun flattenedLinks() = runCatching {
        val stamp = File(root, ".aemu-flatlinks")
        if (stamp.isFile) return@runCatching
        FlattenedLinks.repair(root, log)
        stamp.writeText("1\n")
    }.onFailure { log("symlinks: flattened link repair failed: ${it.message}") }

    /**
     * `adb shell` and every `sh -c` start /system/bin/sh. qemu stats the host path of the executable, so the link must
     * be relative and resolve inside the tree: a missing sh, a dangling link, or an absolute link (it would resolve
     * against the phone's own /system) ends in "unable to stat file for the executable" and an abort in the guest
     * linker. Relinks it (relative) to the first of mksh/ash/toybox/busybox that really lives inside the tree; a
     * working sh is left alone.
     */
    private fun shellLink() = runCatching {
        val bin = File(root, "system/bin")
        val path = File(bin, "sh").toPath()
        val nofollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        val rootPath = root.canonicalPath + File.separator
        // canonicalFile resolves absolute links against the phone, so such a target lands outside rootPath and is rejected
        fun inTree(f: File) = runCatching { f.canonicalFile.let { it.isFile && it.path.startsWith(rootPath) } }.getOrDefault(false)
        val isLink = java.nio.file.Files.isSymbolicLink(path)
        if (isLink) {
            val t = java.nio.file.Files.readSymbolicLink(path).toString()
            if (!t.startsWith("/") && inTree(File(bin, "sh"))) return@runCatching
        } else if (java.nio.file.Files.exists(path, nofollow)) {
            // a real file is not ours to replace, unless it is a few bytes of plain text (a flattened link, "mksh")
            val head = runCatching { path.toFile().takeIf { it.length() <= FlattenedLinks.MAX_LEN }?.readBytes() }.getOrNull()
            if (head == null || FlattenedLinks.targetOf(head) == null) return@runCatching
            path.toFile().delete()
        }
        val target = listOf("mksh", "ash", "toybox", "busybox", "../xbin/busybox").firstOrNull { inTree(File(bin, it)) }
        if (target == null) { log("sh: system/bin/sh is missing and there is no mksh/ash/toybox/busybox to link it to"); return@runCatching }
        if (isLink) java.nio.file.Files.delete(path)
        java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
        log("sh: system/bin/sh -> $target restored")
    }.onFailure { log("sh: could not repair system/bin/sh: ${it.message}") }

    /**
     * MediaTek bionic (linker, libc) uses `pldw` hints that qemu-user rejects with SIGILL, so every process died in
     * its first memcpy. See [ElfPatch.fixPldHints].
     */
    private fun pldHints() = runCatching {
        var words = 0; var files = 0
        val hit = ArrayList<String>()
        for (dir in listOf("system/bin", "system/xbin", "system/lib", "vendor/lib", "vendor/bin")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    val n = ElfPatch.fixPldHints(f)
                    if (n > 0) { words += n; files++; hit.add("${f.name}=$n") }
                }
            }
        }
        if (words > 0) log("pld hints: $words word(s) rewritten in $files file(s) (qemu rejects pldw and malformed pld): ${hit.joinToString(", ")}")
    }.onFailure { log("pld hints: failed: ${it.message}") }

    /**
     * MediaTek DRVB: кодеки, DRM, камера и даже debuggerd сверяют «платформу» через демон drvbd, а тот
     * читает efuse через /dev/devmap. На эмуляторе проверка проваливается, каждый клиент 10 с ждёт демон,
     * а модуль DRM затем нарочно прыгает на 0xddeeaadd (drmserver падает, MediaPlayer зависает намертво).
     * Проверка отвечает на вызов подписанным ответом, который считает сама библиотека, — поэтому достаточно,
     * чтобы её внутренние проверки вернули «успех»: ответ получится честный.
     */
    private fun vendorChecks() = runCatching {
        val drvb = File(root, "system/lib/libmtk_drvb.so")
        if (drvb.isFile) {
            val n = ElfPatch.returnZero(drvb, setOf("mtk_drvb_basechk", "platform_init", "platform_advchk", "drvb_ext_input"))
            if (n > 0) log("MediaTek DRVB: platform check disabled ($n func.)")
        }
    }.onFailure { log("MediaTek DRVB: failed: ${it.message}") }

    /**
     * 5.0+/7.x: the guest runs under qemu-user, so libselinux sees the HOST's selinuxfs
     * (/proc/filesystems, /sys/fs/selinux) and is_selinux_enabled() answers 1. servicemanager (and
     * installd, keystore, system_server…) then demands a service_contexts handle and a security context
     * of its own, finds neither, and abort()s (exit 134) before binderd ever gets a context manager.
     * The guest has no policy of its own to load, so report "SELinux disabled" from libselinux:
     * every AOSP caller guards its checks with is_selinux_enabled() > 0 and falls back to plain DAC.
     */
    /**
     * Google TV (Marvell Berlin, e.g. Hisense GX1200V): av_settings and client_auth_service call MV_OSAL_Init(), which
     * starts the OSAL worker tasks and raises their priority with os_set_task_prio() -> pthread_setschedparam()
     * (SCHED_RR/FIFO). The guest is an unprivileged process under qemu-user, so the host refuses it (EPERM) and the
     * caller does MV_ASSERT(ret == 0); t_Assert() then deliberately writes to address 0 (SIGSEGV inside libOSAL.so,
     * av_settings exit 139) and the logs show "MV_OSAL_Init() failed (80004005)". "media.avsettings" is never
     * registered, SurfaceFlinger waits for it forever and the boot never gets past the splash.
     * Real-time priorities mean nothing here, so os_set_task_prio() just reports success.
     */
    private val HDMIRX_NOOPS = setOf(
        "GetBoardVersion", "kg2h_gpio_exit", "kg2h_gpio_set", "kg2h_gpio_enable_irq", "kg2h_gpio_disable_irq",
    )

    /**
     * Entry points of the Avc275x HDMI receiver driver (libkg2h.so): they bring the chip up over i2c/gpio and wait for
     * it to report ready, which it never does here. Skipped (report success) by [hdmiRxGuard].
     */
    private val HDMIRX_INIT = setOf("KG2H_InitDevice", "AvcInitKG2h", "AvcInitHdmiRx", "Avc275xI2CInit")

    private fun berlinOsalGuard() = runCatching {
        if (!GuestPreload.isGlibcFirmware(root)) return@runCatching
        // The Marvell kernel drivers (/dev/galois_cc, /dev/galois_pe_agent, /dev/mvpm) do not exist here:
        //  - MV_CC_DSS_Init() opens /dev/galois_cc and fails, so MV_OSAL_Init() returns E_FAIL (80004005);
        //  - MV_PE_Init() opens /dev/galois_pe_agent and fails, the hotplug handler then cleans up with
        //    MV_PE_Remove(NULL), whose MV_ASSERT(handle != NULL) crashes av_settings (SIGSEGV in t_Assert).
        //  - MV_CC_UDP_Init() -> MV_CC_UDP_Open() opens a private netlink socket (AF_NETLINK, SOCK_RAW, protocol 29)
        //    towards the Galois kernel module; the host refuses it, so MV_OSAL_Init() still fails after SHM init.
        // The OSAL/PE calls that need the drivers are reduced to "success" so av_settings can reach
        // "media.avsettings"; the rest of the Berlin A/V path is not available in the guest anyway.
        val targets = mapOf(
            "libOSAL.so" to setOf("os_set_task_prio", "MV_CC_DSS_Init", "MV_CC_UDP_Init"),
            // every PE (Marvell "player engine") entry point that the hotplug handler of av_settings calls: there is no
            // /dev/galois_pe_agent, so each one just reports success (output buffers stay as the caller left them)
            "libPEAgent.so" to setOf(
                "MV_PE_Init", "MV_PE_Remove", "MV_PE_RegisterEventCallBack", "MV_PE_ClearScreen",
                "MV_PE_VOutSetEnable", "MV_PE_VOutSetInput", "MV_PE_VOutHDMIGetSinkCaps", "MV_PE_VOutGetCPCBResolution",
                "MV_PE_VOutHDMISetVideoFormat", "MV_PE_VOutHDMISetAudioFormat", "MV_PE_VOutSetCPCBResolutionBDEx",
                "MV_PE_VOutHDMISet3DVideoFormat", "MV_PE_VOutHDMILoadHDCPKeys", "MV_PE_VOutHDMISetHDCP",
                "MV_PE_VideoSet3DConvertMode", "MV_PE_VideoSetSSType",
                "MV_PE_AOutGetDigitalOutCaps", "MV_PE_AOutSetVolume", "MV_PE_AOutSetMute", "MV_PE_AOutGetHDMIFormat",
                "MV_PE_AOutSetHDMIFormat", "MV_PE_AOutGetSpdifFormat", "MV_PE_AOutSetSpdifFormat",
            ),
        )
        for ((name, funcs) in targets) for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/$name"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("Berlin: $name patched ($n func.: ${funcs.joinToString()})")
        }
        // mediaserver crash loop (exit 139, 12 restarts, media.audio_policy never published): libkg2h's HDMI RX init
        // fails (no /dev/twsi0, no /dev/gpio -> "ROM code status" never passes), the error path calls
        // libHdmiRx GetBoardVersion(), which does fopen("/proc/galois_pe/detail") + fread(buf, 1, 0x400, fp) without
        // checking fp. The proc file belongs to the Galois kernel module, so fp == NULL and libc faults on the stream
        // lock (qemu: PC in libc, r3 = 0, LR = libHdmiRx +0x5d88). The board version is only informational: report 0.
        // GetBoardVersion is a static function: it exists in .symtab only, not in .dynsym.
        // Second crash of the same family: kg2h_gpio_init() fails (no /dev/gpio), then KG2H_InitDevice() -> kg2h_gpio_set()
        // -> ioctl on a dead fd fails -> kg2h_gpio_exit() does fclose(NULL) (qemu: PC in libc, r0 = 0, LR = libHdmiRx
        // +0x3324). The whole GPIO line is a no-op here: set/exit/enable_irq/disable_irq just report success.
        // (kg2h_gpio_poll is left alone: its only caller, kg2h_int_process_task, runs only after a successful gpio init.)
        for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/libHdmiRx.so"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, HDMIRX_NOOPS, includeSymtab = true)
            if (n > 0) log("Berlin: $rel patched ($n func.: ${HDMIRX_NOOPS.joinToString()})")
        }
        // AlarmManagerService (system_server) sets the kernel time zone with settimeofday(&tv, &tz) right after
        // /dev/alarm fails to open. The host's seccomp filter does not allow that syscall for an app process and kills
        // the whole guest process with SIGSYS (zygote: "terminated by signal (31)", strace: settimeofday(...,{-180,0})
        // followed by SIGCHLD si_status=31), so system_server dies at "SystemServer: Alarm Manager" on every boot.
        // The guest can never change the host clock anyway: make glibc's settimeofday() report success.
        for (rel in listOf("lib/libc-2.12.2.so")) {
            val libc = File(root, rel)
            if (!libc.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); libc.copyTo(keep) }
            val n = ElfPatch.returnZero(libc, setOf("settimeofday", "__settimeofday"))
            if (n > 0) log("Berlin: glibc settimeofday() no longer reaches the host ($n func.)")
        }
        // berlin_avservice (init.rc class early_start) is the one that finishes the A/V engine start-up and then creates
        // this marker; HotplugHandler::Init() in av_settings polls access("/tmp/.PE_AV_Init.done") before it goes on,
        // so without the marker av_settings stays alive but never registers "media.avsettings".
        // /tmp is a symlink to /var/tmp in this image (init.rc mounts a tmpfs on /var at boot and makes /var/tmp):
        // follow the link inside the guest tree, never on the host, and create the directory behind it
        var rel = "tmp"
        for (i in 0 until 8) {
            val f = File(root, rel)
            if (!isLink(f)) break
            val t = Os.readlink(f.absolutePath)
            rel = if (t.startsWith("/")) t.trimStart('/') else File(rel).parent.let { if (it == null) t else "$it/$t" }
        }
        val tmp = File(root, rel).apply { mkdirs() }
        val marker = File(tmp, ".PE_AV_Init.done")
        if (!marker.exists() && marker.createNewFile()) log("Berlin: /tmp/.PE_AV_Init.done created (in /$rel)")
    }.onFailure { log("Berlin: vendor lib patch failed: ${it.message}") }

    /**
     * Google TV 3.2 (Marvell Berlin, Netgear GTV100) with a bionic userland: mediaserver prints "HDMI RX INIT" and then
     * spins forever in the Avc275x HDMI receiver driver ("master_id = 0,,twsi_fp[master_id] = (null)",
     * "Avc275x_i2csend -- write FAIL!!" / "Avc275x_i2crecv -- i2c read FAIL!!", ~700 MB of it in 15 seconds). The chip sits on
     * /dev/twsi0 and /dev/gpio, which do not exist here, so the receiver never answers, MediaPlayerService and
     * AudioPolicyService are never created, "media.audio_policy" is never registered and every client sits in
     * "ServiceManager: Waiting for service media.audio_policy...". berlinOsalGuard() only runs for the glibc build, so
     * this one was never touched. The receiver is not needed in the guest: its i2c transfers and GPIO calls report
     * success at once, so the init finishes instead of retrying. Libraries (and mediaserver itself, in case the driver is
     * linked statically) that mention the driver are patched once per image; the originals are kept in .aemu-parked.
     * The functions found are logged, so a build whose driver is stripped shows up in aemu.log instead of silently hanging.
     */
    private fun hdmiRxGuard() = runCatching {
        val stamp = File(root, ".aemu-hdmirx")
        // v2: the i2c stand-ins alone only silenced the retry loop (nothing printed, mediaserver still never got past
        // the receiver init), so the driver's init entry points are skipped as well; v1 images are redone
        if (stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == "2") return@runCatching
        val markers = listOf("Avc275x", "kg2h_gpio", "twsi_fp").map { it.toByteArray(Charsets.ISO_8859_1) }
        fun mentions(d: ByteArray) = markers.any { m -> indexOf(d, m) >= 0 }
        val wanted = { n: String -> n in HDMIRX_NOOPS || n in HDMIRX_INIT || n.startsWith("Avc275x_i2c") }
        val candidates = ArrayList<File>()
        for (dir in listOf("system/lib", "system/vendor/lib", "vendor/lib", "lib"))
            File(root, dir).listFiles()?.filterTo(candidates) { it.isFile && !isLink(it) && it.name.endsWith(".so") }
        for (n in listOf("mediaserver", "av_settings")) File(root, "system/bin/$n").takeIf { it.isFile && !isLink(it) }?.let { candidates += it }
        var patched = 0
        var seen = 0
        for (lib in candidates) {
            if (lib.length() < 4096 || lib.length() > 30L shl 20) continue
            val d = runCatching { lib.readBytes() }.getOrNull() ?: continue
            if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || !mentions(d)) continue
            seen++
            val rel = lib.relativeTo(root).path
            val found = ElfPatch.symbolsMatching(d, true, wanted)?.keys.orEmpty()
            if (found.isEmpty()) {
                val like = ElfPatch.symbolsMatching(d, true) { Regex("(?i)avc275x|kg2h|hdmi_?rx").containsMatchIn(it) }?.keys.orEmpty()
                log("HDMI RX: $rel mentions the receiver driver but exports none of its i2c/gpio functions" +
                    if (like.isEmpty()) " (stripped)" else "; candidates: ${like.take(80).joinToString()}")
                continue
            }
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZeroMatching(lib, true, wanted)
            patched += n
            log("HDMI RX: $rel patched ($n of ${found.size} func.: ${found.joinToString()})")
        }
        if (seen > 0 && patched == 0) log("HDMI RX: driver functions already patched or not found in $seen file(s)")
        stamp.writeText("2\n")
    }.onFailure { log("HDMI RX: guard failed: ${it.message}") }

    /**
     * KitKat (Tegra) SurfaceFlinger dereferences the NULL HAL device (hwcomposer is parked) right after
     * "Screen acquired" and dies with SIGSEGV at +0x1e77e (see [ElfPatch.skipNullDeviceHook]).
     */
    private fun surfaceFlingerGuard() = runCatching {
        val lib = File(root, "system/lib/libsurfaceflinger.so")
        if (lib.isFile) {
            val keep = File(root, "system/.aemu-parked/system#lib#libsurfaceflinger.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            if (ElfPatch.skipNullDeviceHook(lib) > 0) log("libsurfaceflinger: the missing HAL device hook is skipped")
        }
    }.onFailure { log("libsurfaceflinger: patch failed: ${it.message}") }

    /**
     * A failed Surface.unlockCanvasAndPost() throws IllegalArgumentException; on a system_server thread nothing
     * catches it, system_server kills itself and zygote exits (see ElfPatch.unlockAndPostNeverFails).
     */
    private fun surfaceGuard() = runCatching {
        val lib = File(root, "system/lib/libgui.so")
        if (lib.isFile) {
            val keep = File(root, "system/.aemu-parked/system#lib#libgui.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            if (ElfPatch.unlockAndPostNeverFails(lib) > 0) log("libgui: a failed frame post no longer kills the caller")
        }
    }.onFailure { log("libgui: patch failed: ${it.message}") }

    /**
     * Google Glass (XE): GlassSystemServer creates the DMP gesture detector inside system_server's ServerThread.
     * DMPManager's constructor calls MPLSensor::getInstance() (libinvensense_hal), which cond-waits on MPLSensor::mWait
     * until the Invensense sensors HAL has constructed the MPLSensor singleton. installSensorHal() replaces that
     * HAL with the host one, so nobody ever builds it, the wait has no timeout and boot stops right after
     * "GlassSystemServer: Starting gesture service." (futex on libinvensense_hal's mWait, no further SystemServer steps).
     * Make getInstance() return null at once and report the DMP as unavailable, so Glass uses the software
     * (sensor-based) gesture detectors it already builds; a null MPLSensor is never dereferenced then.
     */
    private fun glassGestureGuard() = runCatching {
        fun patch(name: String, funcs: Set<String>, label: String) {
            val lib = File(root, "system/lib/$name")
            if (!lib.isFile) return
            val keep = File(root, "system/.aemu-parked/system#lib#$name")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("$label ($n func.)")
        }
        patch("libinvensense_hal.so", setOf("_ZN9MPLSensor11getInstanceEv"),
            "glass: MPLSensor::getInstance() no longer waits for the Invensense HAL")
        patch("libglassgesture.so",
            setOf("_ZNK5glass10DMPManager22isDMPGesturesSupportedEv", "_ZN5glass10DMPManager27blockUntilGestureRecognizedEPNS_10DMPGestureE"),
            "glass: DMP gestures reported unavailable")
    }.onFailure { log("glass: gesture patch failed: ${it.message}") }

    /**
     * Glass (4.0.x): the Glass Bluetooth app (com.google.glass.bluetooth) cannot work in the guest. Its
     * GlassBluetoothService opens an RFCOMM server socket (HandsFreeProfile, channel 13), the guest cannot create
     * AF_BLUETOOTH sockets under the host app (EACCES), the listener stays null and AsyncBluetoothServerSocket's
     * AcceptThread dies with an NPE; the app restarts and crashes again, so "Bluetooth has stopped" covers the UI.
     * Disabling only the service is not enough: CompanionLocationService in the same app then crashes because it
     * cannot bind to it. The APK (and its odex) is set aside in /system/.aemu-parked instead and PackageManager drops
     * the missing system app, like the backup transport in disableStallingPackages().
     */
    private fun glassBluetoothPark() = runCatching {
        if (img.api >= 16) return@runCatching
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var moved = 0
        for (dir in listOf("system/app", "system/priv-app")) {
            val apk = File(root, "$dir/GlassBluetooth.apk")
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) moved++ else log("glass: could not park ${f.name}")
            }
        }
        if (moved > 0) {
            File(root, "data/dalvik-cache/system@app@GlassBluetooth.apk@classes.dex").delete()
            log("glass: Bluetooth app parked ($moved file(s)); RFCOMM sockets are unavailable in the guest")
        }
    }.onFailure { log("glass: could not park the Bluetooth app: ${it.message}") }

    private fun selinuxOff() = runCatching {
        val lib = File(root, "system/lib/libselinux.so")
        if (lib.isFile) {
            // keep the stock file so that a later delta OTA can still rebuild the stock system image
            val keep = File(root, "system/.aemu-parked/system#lib#libselinux.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, setOf("is_selinux_enabled", "is_selinux_mls_enabled"))
            if (n > 0) log("SELinux: libselinux reports disabled ($n func.)")
        }
    }.onFailure { log("SELinux: failed to patch libselinux: ${it.message}") }

    private fun qtaguid() = runCatching {
        val d = File(root, "data/.aemu_qtaguid").apply { mkdirs() }
        d.setReadable(true, false); d.setExecutable(true, false)
        val files = mapOf(
            "stats" to "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets " +
                "rx_tcp_bytes rx_tcp_packets rx_udp_bytes rx_udp_packets rx_other_bytes rx_other_packets " +
                "tx_tcp_bytes tx_tcp_packets tx_udp_bytes tx_udp_packets tx_other_bytes tx_other_packets\n",
            "iface_stat_fmt" to "ifname total_skb_rx_bytes total_skb_rx_packets total_skb_tx_bytes total_skb_tx_packets\n",
            "iface_stat_all" to "",
        )
        for ((n, text) in files) File(d, n).apply { writeText(text); setReadable(true, false) }
    }

    private fun splitSize(): Long = runCatching { ctx.assets.openFd("engines/kk/libGLES_split.so").use { it.length } }.getOrDefault(-1L)

    private fun makeDataDirs() {
        var made = 0
        val rcs = root.listFiles { f -> f.isFile && f.name.endsWith(".rc") }?.map { f ->
            runCatching { f.readText() }.getOrDefault("")
        }.orEmpty()
        val dbdata = dbdataDirs(rcs, img.dirs,
            legacySamsung = img.api < 14 && img.brand.contains("samsung", true) || img.name.contains("samsung", true) && img.api < 14,
            alreadyThere = File(root, "dbdata").exists())
        val want = (DATA_DIRS + img.dirs.map { it.trim().removePrefix("/") } + dbdata).filter { it.isNotEmpty() && !it.startsWith("#") }
        for (d in want.distinct()) {
            // каталоги init в /dev, /sys, /proc, /acct не создаём — это подменяет qemu
            if (d.startsWith("proc") || d.startsWith("sys/") || d == "sys" || d.startsWith("acct")) continue
            val f = File(root, d)
            if (!f.isDirectory && f.mkdirs()) made++
            if (d in dbdata) { f.setReadable(true, false); f.setWritable(true, false); f.setExecutable(true, false) }
        }
        if (made > 0) log("created init directories: $made")
    }

    private fun makeUserZeroLink() {
        if (img.api < 14) return // ICS installd creates /data/user/0 too; an absolute link points at the host /data
        // 7.0+: vold prepares the device-encrypted per-user dirs; without it installd cannot create app DE storage
        if (img.api >= 24) for (d in listOf("data", "user_de/0", "misc_ce/0", "misc_de/0", "system_ce/0", "system_de/0", "media/0", "misc/profiles/cur/0", "misc/profiles/ref"))
            File(root, "data/$d").mkdirs()
        val user = File(root, "data/user").apply { mkdirs() }
        val zero = File(user, "0")
        val cur = runCatching { Os.readlink(zero.absolutePath) }.getOrNull()
        if (cur == "../data") return
        runCatching {
            if (cur != null || zero.exists()) { if (zero.isDirectory && !isLink(zero)) wipe(zero) else zero.delete() }
            Os.symlink("../data", zero.absolutePath)
        }
    }

    private fun makeDevNodes() {
        File(root, "dev/input/event0").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        File(root, "dev/socket").mkdirs()
        File(root, "dev/graphics").mkdirs()
        AudioOut(paths, log).makeFifo()
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
    }

    private fun makeSysNodes() {
        val freq = File(root, "sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state")
        if (!freq.isFile) {
            freq.parentFile?.mkdirs()
            freq.writeText(listOf(384000, 594000, 810000, 1026000, 1242000, 1512000).joinToString("\n") { "$it 0" } + "\n")
        }
        val cpus = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val cpu = File(root, "sys/devices/system/cpu").apply { mkdirs() }
        for ((n, v) in listOf("present" to "0-${cpus - 1}", "possible" to "0-${cpus - 1}", "online" to "0-${cpus - 1}", "offline" to "")) {
            File(cpu, n).let { if (!it.isFile) it.writeText(v + "\n") }
        }
        // Dalvik's NetworkInterface.getByName() reads these files; without them the open falls through to the host's
        // /sys, which answers EACCES. The Nexus Q broker (IPUtils) then finds no loopback address and its thread dies,
        // which restarts the Tungsten apps in a loop.
        val lo = File(root, "sys/class/net/lo").apply { mkdirs() }
        run { var d: File? = lo; while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile } }
        for ((n, v) in listOf("ifindex" to "1", "flags" to "0x9", "mtu" to "16436", "type" to "772", "operstate" to "unknown",
            "carrier" to "1", "address" to "00:00:00:00:00:00", "addr_len" to "6", "dev_id" to "0x0")) {
            runCatching {
                File(lo, n).let { f -> if (!f.isFile || f.length() == 0L) f.writeText(v + "\n"); f.setReadable(true, false) }
            }.onFailure { log("sysfs: cannot create /sys/class/net/lo/$n: ${it.message}") }
        }
        val base = File(root, "sys/class/power_supply")
        val bat = mapOf(
            "battery/type" to "Battery", "battery/status" to "Full", "battery/health" to "Good",
            "battery/present" to "1", "battery/capacity" to "100", "battery/batt_vol" to "4200",
            "battery/voltage_now" to "4200000", "battery/batt_temp" to "250", "battery/temp" to "250",
            "battery/technology" to "Li-ion", "ac/type" to "Mains", "ac/online" to "1",
            "usb/type" to "USB", "usb/online" to "0",
        )
        for ((rel, v) in bat) File(base, rel).let { if (!it.isFile) { it.parentFile?.mkdirs(); it.writeText(v + "\n") } }
        healthdRelativeSysfs(base)
        personalityNoop()
        hostPathAlias()
        parkNfc()
        mainStackMaps()
        val power = File(root, "sys/power").apply { mkdirs() }
        for (n in listOf("state", "wake_lock", "wake_unlock", "autosleep")) File(power, n).let { if (!it.isFile) it.createNewFile(); it.setWritable(true, false) }
        // узлы питания, которые открывает libhardware_legacy именно этой прошивки (Samsung: dvfslock_ctrl…):
        // если хоть один не откроется, библиотека считает экран выключенным и система не принимает касания
        runCatching {
            val lib = File(root, "system/lib/libhardware_legacy.so")
            if (lib.isFile) {
                val text = String(lib.readBytes(), Charsets.ISO_8859_1)
                for (m in Regex("/sys/(power|android_power)/[a-z_0-9]+").findAll(text)) {
                    val rel = m.value.removePrefix("/")
                    if (rel.contains("wait_for_fb")) continue // handled below
                    File(root, rel).let { f -> if (!f.exists()) { f.parentFile?.mkdirs(); f.createNewFile(); f.setWritable(true, false) } }
                }
            }
        }
        // SurfaceFlinger's DisplayEventThread loops sleep→wake: missing nodes make it spin at 100% CPU (EBADF).
        // wake answers at once; sleep is a FIFO with no writer, so the read blocks forever and the screen stays on.
        runCatching {
            File(power, "wait_for_fb_wake").let { if (!it.isFile) it.writeText("awake") }
            val sleep = File(power, "wait_for_fb_sleep")
            if (!sleep.exists()) android.system.Os.mkfifo(sleep.absolutePath, "666".toInt(8))
        }
        // подсветка экрана: LightsService пишет сюда яркость
        val bl = File(root, "sys/class/leds/lcd-backlight").apply { mkdirs() }
        File(bl, "brightness").let { if (!it.isFile) it.writeText("255\n") }
        File(bl, "max_brightness").let { if (!it.isFile) it.writeText("255\n") }
        lowMemoryKillerNodes()
        procNetNodes()
        javaIfInet6Redirect()
        settingsCpuinfoRedirect()
        launcherProximityGuard()
        tungstenLedNodes()
        tungstenLedInitPatch()
        tungstenLedCountPatch()
    }

    /** ARMv7 /proc/cpuinfo in the layout of a Tegra K1 (Cortex-A15) tablet, 4 cores, ending with the board lines. */
    private val GUEST_CPUINFO: String = buildString {
        for (i in 0 until 4) {
            append("processor\t: $i\nmodel name\t: ARMv7 Processor rev 3 (v7l)\nBogoMIPS\t: 38.40\n")
            append("Features\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm\n")
            append("CPU implementer\t: 0x41\nCPU architecture: 7\nCPU variant\t: 0x2\nCPU part\t: 0xc0f\nCPU revision\t: 3\n\n")
        }
        append("Hardware\t: Yellowstone\nRevision\t: 0000\nSerial\t\t: 0000000000000000\n")
    }

    /**
     * NetworkInterface.getByName() (Dalvik 4.x) opens /proc/net/if_inet6 for every interface; on this host the open
     * falls through to the real /proc and fails with EACCES, so the Nexus Q broker (IPUtils) gets a SocketException,
     * finds no loopback address and its thread dies (NPE in NetworkSetupSession.reloadNetworkState). BootReceiver
     * also reads /proc/version. Serve both from the tree.
     *
     * qemu's -L prefix only redirects a guest path when the file exists in the tree, so the nodes have to be real
     * regular files under real, traversable directories. A symlink or a directory left at one of these paths would
     * either send the write into the host /proc (EACCES, the guest keeps hitting the host file) or make the lookup
     * miss, so such leftovers are replaced. Modes are set explicitly instead of through File.setReadable(), and every
     * node is read back and logged, so run/aemu.log shows whether the stub is in place.
     */
    private fun procNetNodes() {
        val nodes = linkedMapOf(
            "proc/net/if_inet6" to "00000000000000000000000000000001 01 80 10 80       lo\n",
            "proc/version" to VmSettings.procVersion(img.settings.kernel),
            // Chinese "xhl" firmware: SpenService.checkVersion() writes to /proc/versioncheck, and Launcher.onCreate()
            // returns before setupViews() when that check fails, so onResume then dies on a null mWorkspace
            // (NullPointerException at Launcher.onResume, relaunched forever as the only HOME app)
            "proc/versioncheck" to "",
        )
        // nodes the guest writes to as well as reads
        val writable = setOf("proc/versioncheck")
        // directories the guest (a faked, non-owner uid) must be able to traverse: 0755
        for (rel in listOf("proc", "proc/net")) runCatching {
            val d = File(root, rel)
            if (isLink(d) || (d.exists() && !d.isDirectory)) wipe(d)
            if (!d.isDirectory && !d.mkdirs()) error("mkdirs failed")
            Os.chmod(d.path, 0b111_101_101)
        }.onFailure { log("procfs: cannot prepare /$rel: ${it.message}") }
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (isLink(f) || f.isDirectory) wipe(f)
            f.parentFile?.mkdirs()
            if (!f.isFile || (rel !in writable && f.readText() != v)) f.writeText(v)
            // 0644: readable by any guest uid (0666 for the nodes the guest writes)
            Os.chmod(f.path, if (rel in writable) 0b110_110_110 else 0b110_100_100)
            val back = f.readText()
            val mode = Os.stat(f.path).st_mode and 0xfff
            if (rel !in writable && back != v) error("read-back mismatch (${back.length} bytes)")
            log("procfs: /$rel served from the tree (${Integer.toOctalString(mode)}, ${f.length()} bytes)")
        }.onFailure { log("procfs: cannot create /$rel: ${it.message}") }
    }

    /**
     * The real fix for the /proc/net/if_inet6 EACCES. Whatever qemu does with /proc paths, the guest's open of
     * /proc/net/if_inet6 still reached the host's /proc (EACCES) even with a correct tree file in place, so stop
     * depending on /proc: Dalvik's libcore has the path as a string constant, and the tree's /data is served like any
     * other tree file. [DexStringPatch] swaps the constant for /data/.aemu_ifinet, where the loopback line is put.
     * Dalvik (API 20 and below) only; ART has no such patchable constant.
     * Files patched: core.odex when the firmware has it; otherwise the dalvik-cache copy of core.jar's dex (so the
     * jar itself stays stock, which keeps delta OTAs and every other file's dependency data valid; on a deodexed
     * firmware the cache is created by the first boot, so the patch lands from the second boot on).
     * DeltaOta undoes the patch on a copy to match the OTA's source hashes, and this runs again at the next start.
     */
    private fun javaIfInet6Redirect() = runCatching {
        if (img.api > 20) return@runCatching
        val odex = File(root, "system/framework/core.odex").takeIf { it.isFile }
        val targets = if (odex != null) listOf(odex)
        else listOfNotNull(File(root, "data/dalvik-cache/system@framework@core.jar@classes.dex").takeIf { it.isFile })
        if (targets.isEmpty()) {
            if (File(root, "system/framework/core.jar").isFile) log("libcore: no core.odex or dalvik-cache copy yet; if_inet6 redirect follows after the first boot")
            return@runCatching
        }
        val node = File(root, "data/.aemu_ifinet")
        val text = "00000000000000000000000000000001 01 80 10 80       lo\n"
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != text) node.writeText(text)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d)) {
                1 -> {
                    f.setWritable(true, true)
                    f.writeBytes(d)
                    log("libcore: ${f.name} now reads the interface list from /data/.aemu_ifinet")
                }
                0 -> {}
                else -> log("libcore: /proc/net/if_inet6 string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("libcore: if_inet6 redirect failed: ${it.message}") }

    /**
     * Tango Settings > About: DeviceInfoSettings.getFormattedTNHWRevision() takes a substring after indexOf() over
     * /proc/cpuinfo and dies with StringIndexOutOfBoundsException (index -1) because the host's arm64 cpuinfo has no
     * Hardware/Revision/Serial lines. A cpuinfo file in the tree is not served for /proc (qemu keeps reading the host's
     * file, as with if_inet6), so the path constant inside Settings' dex is swapped for /data/.cpuinf, which holds a
     * Tegra-style cpuinfo ([GUEST_CPUINFO]). Settings only; other processes still see the host's file.
     * Odex when the firmware has one, otherwise the dalvik-cache copy (created by the first boot).
     */
    private fun settingsCpuinfoRedirect() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = listOf(
            "system/priv-app/Settings.odex", "system/app/Settings.odex",
            "data/dalvik-cache/system@priv-app@Settings.apk@classes.dex",
            "data/dalvik-cache/system@app@Settings.apk@classes.dex",
        ).map { File(root, it) }.filter { it.isFile }
        if (targets.isEmpty()) return@runCatching
        val node = File(root, "data/.cpuinf")
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != GUEST_CPUINFO) node.writeText(GUEST_CPUINFO)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d, "/proc/cpuinfo", "/data/.cpuinf")) {
                1 -> { f.setWritable(true, true); f.writeBytes(d); log("settings: ${f.name} now reads cpuinfo from /data/.cpuinf") }
                0 -> {}
                else -> log("settings: /proc/cpuinfo string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("settings: cpuinfo redirect failed: ${it.message}") }

    /**
     * "xhl" firmware (NDLauncher): Launcher.onCreate() ends with getSensorList(TYPE_PROXIMITY).get(0), and the guest's
     * sensor HAL has no proximity sensor, so the list is empty and the launcher dies with IndexOutOfBoundsException
     * (Launcher.java:765) on every start; the system relaunches it forever as the only HOME app. [LauncherProximityPatch]
     * makes that lookup tolerate an empty list (onResume/onPause already skip a null sensor).
     * Odex when the firmware has one, otherwise the dalvik-cache copy (created by the first boot). Launcher apps whose
     * dex lacks the lookup are not touched, and nothing is logged for them.
     */
    private fun launcherProximityGuard() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = ArrayList<File>()
        for (dir in listOf("system/app", "system/priv-app"))
            File(root, dir).listFiles()?.filterTo(targets) { it.isFile && it.name.endsWith(".odex") && it.name.contains("Launcher", true) }
        File(root, "data/dalvik-cache").listFiles()?.filterTo(targets) {
            it.isFile && it.name.contains("Launcher", true) && it.name.endsWith("@classes.dex")
        }
        for (f in targets) {
            val d = f.readBytes()
            when (LauncherProximityPatch.apply(d)) {
                LauncherProximityPatch.PATCHED -> {
                    f.setWritable(true, true); f.writeBytes(d)
                    log("launcher: ${f.name} no longer fails when the guest has no proximity sensor")
                }
                LauncherProximityPatch.UNEXPECTED -> log("launcher: ${f.name} looks up the proximity sensor, but not in the code this patch knows, left as is")
                else -> {}
            }
        }
    }.onFailure { log("launcher: proximity guard failed: ${it.message}") }

    /**
     * Nexus Q (Tungsten): LEDService's LEDController.nativeInit() opens the LED ring driver and throws
     * "LEDController nativeInit failed" when it can't, so the service crashes and is restarted every 5 s. The node
     * name lives in the native library, so scan the *led* libraries (system/lib, app-lib, and inside the LED/health
     * APKs) for /dev/ paths and give each a world-writable placeholder file so the open succeeds.
     */
    private fun tungstenLedNodes() = runCatching {
        val re = Regex("/dev/[A-Za-z0-9_./-]{2,40}")
        val found = LinkedHashSet<String>()
        fun scan(bytes: ByteArray) { for (m in re.findAll(String(bytes, Charsets.ISO_8859_1))) found += m.value.trimEnd('.', '/') }
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib", "system/app", "system/priv-app")) {
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name.contains("led", true) }.forEach { libs += it }
        }
        for (f in libs) {
            if (f.name.endsWith(".so")) scan(f.readBytes())
            else if (f.name.endsWith(".apk")) java.util.zip.ZipFile(f).use { z ->
                for (e in z.entries()) if (e.name.startsWith("lib/armeabi") && e.name.endsWith(".so") && e.name.contains("led", true))
                    scan(z.getInputStream(e).use { it.readBytes() })
            }
        }
        val skip = setOf("/dev/null", "/dev/zero", "/dev/log", "/dev/input", "/dev/socket", "/dev/graphics", "/dev/binder", "/dev/ashmem", "/dev/random", "/dev/urandom", "/dev/tty")
        for (path in found) {
            if (path in skip || path.startsWith("/dev/log/") || path.startsWith("/dev/socket/")) continue
            val f = File(root, path.removePrefix("/"))
            if (f.exists()) continue
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(4096))
            f.setReadable(true, false); f.setWritable(true, false)
            log("tungsten: placeholder LED node $path")
        }
        if (found.isNotEmpty()) log("tungsten: /dev paths found in LED libs: ${found.joinToString()}")
    }.onFailure { log("tungsten: LED node scan failed: ${it.message}") }

    /**
     * Nexus Q (Tungsten), the real cause of the LED crash loop: the /dev/leds placeholder makes open() succeed, but
     * TungstenLEDs::InitBuffer() then does ioctl(fd, 0x8001e206, &ledCount) on it. A regular file answers ENOTTY, so
     * InitBuffer returns -11, getInstance() logs "Failed to open handle to led driver or initBuffer", nativeInit throws
     * and the service dies and restarts. There is no LED driver to answer, so patch the Thumb code of InitBuffer:
     *   before: r1 = this+4; r4 = this; r0 = ioctl_wrapper(this, r1)      (bl, 4 bytes)
     *   after : r1 = 32;     r4 = this; this->ledCount(+4) = 32; r0 = 0
     * The rest (malloc of 3*(count+1) bytes for the RGB buffer, success return) stays untouched. 32 is the ring size
     * of the Nexus Q. Later ioctls (commit/set) keep failing with a logged error, which the JNI layer does not throw.
     */
    private fun tungstenLedInitPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs10InitBufferEv"
        for (f in libs) {
            val sym = ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue
            val off = sym and 0xffffffffffL
            fun hw(r: java.io.RandomAccessFile, at: Long): Int { r.seek(at); val a = r.read(); val b = r.read(); return a or (b shl 8) }
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off) != 0xb510) { log("tungsten: InitBuffer prologue unexpected in ${f.name}, not patched"); return@use }
                if (hw(r, off + 2) == 0x2120) return@use // already patched
                // push {r4,lr}; adds r1,r0,#4; mov r4,r0; bl <ioctl wrapper>
                if (hw(r, off + 2) != 0x1d01 || hw(r, off + 4) != 0x4604 || hw(r, off + 6) != 0xf7ff || (hw(r, off + 8) and 0xd000) != 0xd000) {
                    log("tungsten: InitBuffer body unexpected in ${f.name}, not patched"); return@use
                }
                val patch = intArrayOf(0x2120, 0x4604, 0x7121, 0x2000) // movs r1,#32; mov r4,r0; strb r1,[r4,#4]; movs r0,#0
                r.seek(off + 2)
                for (h in patch) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: InitBuffer in ${f.name} no longer needs the LED ioctl (32 LEDs)")
            }
        }
    }.onFailure { log("tungsten: InitBuffer patch failed: ${it.message}") }

    /**
     * Second half of the Nexus Q LED fix. LEDService's JNI nativeInit() calls TungstenLEDs::getLedCount(&n) and
     * returns false (-> "LEDController nativeInit failed", crash loop) when it is non-zero. getLedCount is a thin
     * ioctl(fd, 0x8001e206, out) wrapper, which fails with ENOTTY on the /dev/leds placeholder file. Replace the
     * 8 bytes "ldr r0,[r0]; ldr r1,[pc,#8]; blx ioctl" with "movs r3,#32; strb r3,[r2]; movs r0,#0; nop", so it
     * reports 32 LEDs and success; the null-pointer check and the pop stay as they were.
     */
    private fun tungstenLedCountPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs11getLedCountEPh"
        for (f in libs) {
            val off = (ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue) and 0xffffffffffL
            fun hw(r: java.io.RandomAccessFile, at: Long): Int { r.seek(at); val a = r.read(); val b = r.read(); return a or (b shl 8) }
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off + 12) == 0x2320) return@use // already patched
                // push {r4,lr}; mov r2,r1; cbnz r1; mvn r0,#21; b; ldr r0,[r0]; ldr r1,[pc,#8]; blx ioctl; pop
                if (hw(r, off) != 0xb510 || hw(r, off + 2) != 0x460a || hw(r, off + 4) != 0xb911 ||
                    hw(r, off + 12) != 0x6800 || hw(r, off + 14) != 0x4902 || hw(r, off + 16) != 0xf7ff) {
                    log("tungsten: getLedCount unexpected in ${f.name}, not patched"); return@use
                }
                r.seek(off + 12)
                for (h in intArrayOf(0x2320, 0x7013, 0x2000, 0xbf00)) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: getLedCount in ${f.name} reports 32 LEDs")
            }
        }
    }.onFailure { log("tungsten: getLedCount patch failed: ${it.message}") }

    /**
     * Some vendor ActivityManagers (Spreadtrum KitKat: ProcessList.updateOomLevels) read the kernel's
     * lowmemorykiller tunables and Integer.parseInt() them without checking for an empty read. The guest has
     * no such kernel module, so the read returned "" and system_server died with
     * "NumberFormatException: Invalid int: """, which looked like an endless reboot. Give those files
     * stock-shaped content (six oom_adj levels, minfree in 4 KB pages). Our GuestLmk does the real killing.
     */
    private fun lowMemoryKillerNodes() {
        val nodes = mapOf(
            "sys/module/lowmemorykiller/parameters/adj" to "0,1,2,4,9,15",
            "sys/module/lowmemorykiller/parameters/minfree" to "1536,2048,4096,5120,5632,6144",
            "proc/sys/vm/extra_free_kbytes" to "0",
        )
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (!f.isFile || f.length() == 0L) {
                f.parentFile?.mkdirs()
                f.writeText(v + "\n")
            }
            f.setReadable(true, false)
            f.setWritable(true, false)
        }.onFailure { log("sysfs: cannot create /$rel: ${it.message}") }
    }

    /**
     * Своя таблица монтирования: прошивки (Samsung /efs, /preload…) читают /proc/mounts и, не найдя
     * раздел, пытаются смонтировать его сами — а seccomp телефона убивает за mount(). qemu отдаёт
     * гостю файлы из дерева поверх хостовых, поэтому root/proc/mounts видится как настоящий.
     */
    private fun makeProcMounts() {
        val mounts = LinkedHashMap<String, String>() // точка → строка
        fun add(dev: String, point: String, type: String, opts: String) { mounts.putIfAbsent(point, "$dev $point $type $opts 0 0") }
        add("rootfs", "/", "rootfs", "ro,relatime")
        add("tmpfs", "/dev", "tmpfs", "rw,nosuid,relatime,mode=755")
        add("devpts", "/dev/pts", "devpts", "rw,relatime,mode=600")
        add("proc", "/proc", "proc", "rw,relatime")
        add("sysfs", "/sys", "sysfs", "rw,relatime")
        add("none", "/acct", "cgroup", "rw,relatime,cpuacct")
        add("tmpfs", "/mnt/asec", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("tmpfs", "/mnt/obb", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("/dev/block/platform/aemu/by-name/system", "/system", "ext4", "ro,relatime")
        add("/dev/block/platform/aemu/by-name/userdata", "/data", "ext4", "rw,nosuid,nodev,noatime")
        add("/dev/block/platform/aemu/by-name/cache", "/cache", "ext4", "rw,nosuid,nodev,noatime")
        // точки монтирования из init*.rc и fstab прошивки
        val rcs = (root.listFiles()?.filter { it.isFile && (it.name.endsWith(".rc") || it.name.startsWith("fstab")) } ?: emptyList()) +
            listOfNotNull(File(root, "system/etc/vold.fstab").takeIf { it.isFile })
        for (f in rcs) runCatching {
            for (raw in f.readLines()) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t.isEmpty() || t[0].startsWith("#")) continue
                when {
                    t[0] == "mount" && t.size >= 4 -> {
                        val point = t[3]
                        if (point.startsWith("/") && point != "/" && !point.startsWith("/proc") && !point.startsWith("/sys") && !point.startsWith("/dev"))
                            add(t[2].substringBefore('@').ifEmpty { "none" }, point, t[1], if (point == "/system") "ro,relatime" else "rw,relatime")
                    }
                    f.name.startsWith("fstab") && t.size >= 3 && t[0].startsWith("/dev") && t[1].startsWith("/") ->
                        add(t[0], t[1], t[2], "rw,relatime")
                }
            }
        }
        // карта памяти видна «смонтированной»
        add("/dev/block/vold/179:1", img.sdcardPath, "vfat", "rw,dirsync,nosuid,nodev,noexec,relatime,uid=1000,gid=1015,fmask=0702,dmask=0702")
        for (p in mounts.keys) if (p != "/" && !p.startsWith("/proc") && !p.startsWith("/sys") && !p.startsWith("/dev")) File(root, p.trimStart('/')).mkdirs()
        val f = File(root, "proc/mounts")
        f.parentFile?.mkdirs()
        val text = mounts.values.joinToString("\n", postfix = "\n")
        if (f.takeIf { it.isFile }?.readText() != text) f.writeText(text)
    }

    /**
     * Звук обслуживает наш HAL (один выход на динамик, 48 кГц), поэтому audio_policy.conf пишем
     * канонический: лишние выходы прошивок (hdmi с dynamic-параметрами, deep_buffer, a2dp, usb)
     * заставляют audio_policy спрашивать у HAL то, чего он не знает, и mediaserver падает.
     */
    private fun audioPolicy() {
        val cur = File(root, "system/etc/audio_policy.conf")
        val rom = File(root, "system/etc/audio_policy.conf.rom")
        if (!cur.isFile && !rom.isFile && img.api < 16) return
        runCatching {
            if (cur.isFile && !rom.isFile) cur.copyTo(rom)
            val text = """
                |# создано AEmulator: единственный модуль — звуковой HAL эмулятора
                |global_configuration {
                |  attached_output_devices AUDIO_DEVICE_OUT_SPEAKER
                |  default_output_device AUDIO_DEVICE_OUT_SPEAKER
                |  attached_input_devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |}
                |
                |audio_hw_modules {
                |  primary {
                |    outputs {
                |      primary {
                |        sampling_rates 48000
                |        channel_masks AUDIO_CHANNEL_OUT_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_OUT_SPEAKER
                |        flags AUDIO_OUTPUT_FLAG_PRIMARY
                |      }
                |    }
                |    inputs {
                |      primary {
                |        sampling_rates 8000|16000|44100|48000
                |        channel_masks AUDIO_CHANNEL_IN_MONO|AUDIO_CHANNEL_IN_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |      }
                |    }
                |  }
                |}
                |""".trimMargin()
            if (cur.takeIf { it.isFile }?.readText() != text) cur.writeText(text)
        }.onFailure { log("audio: failed to write audio_policy.conf: ${it.message}") }
    }

    /** Кадровый буфер — обычный файл, qemu отвечает на ioctl FBIOGET_* от гостя. */
    private fun makeFb() {
        val s = img.settings
        val (w, h) = if (engine == Engine.GB) 480 to 800 else s.width to s.height
        // двойная буферизация: гость листает страницы через FBIOPAN_DISPLAY
        // qemu reports smem_len = two 32-bit pages (TWRP draws BGRA_8888); mmap past the file end would SIGBUS
        val need = w.toLong() * h * 4 * 2
        val f = paths.fb
        if (f.isFile && f.length() == need) return
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(need) }
        runCatching { File(root, "dhd.fbgeom").writeText("$w $h\n") }
        log("framebuffer: ${w}x$h")
    }

    /**
     * Владельцы файлов приложений в /data/data: ядро телефона не даёт менять uid, поэтому qemu
     * подставляет их по таблице dhd.owners (dev, ino → uid, gid). Берём uid из packages.xml.
     */
    private fun seedOwners() {
        val stamp = File(root, "dhd.owners.seeded")
        val done = if (stamp.isFile) stamp.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        val pkgXml = File(root, "data/system/packages.xml")
        val dataDir = File(root, "data/data")
        if (!pkgXml.isFile || !dataDir.isDirectory) return
        runCatching {
            val text = pkgXml.readText()
            val re = Regex("<package name=\"([^\"]+)\"[^>]*?\\s(?:shared)?[Uu]serId=\"(\\d+)\"")
            val sb = StringBuilder()
            var rows = 0
            var pkgs = 0
            for (m in re.findAll(text)) {
                val name = m.groupValues[1]
                val uid = m.groupValues[2].toIntOrNull() ?: continue
                val d = File(dataDir, name)
                if (!d.isDirectory) continue
                val key = "$name $uid"
                if (key in done) continue
                done.add(key)
                pkgs++
                val todo = ArrayDeque<File>().apply { add(d) }
                while (todo.isNotEmpty()) {
                    val f = todo.removeFirst()
                    val st = runCatching { Os.lstat(f.absolutePath) }.getOrNull() ?: continue
                    sb.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, uid, uid))
                    rows++
                    if (!OsConstants.S_ISLNK(st.st_mode) && f.isDirectory) f.listFiles()?.forEach { todo.add(it) }
                }
            }
            if (rows > 0) {
                paths.owners.appendText(sb.toString())
                log("file owners restored: $pkgs packages, $rows entries")
            }
            stamp.writeText(done.joinToString("\n", postfix = "\n"))
        }.onFailure { log("owners: ${it.message}") }
    }

    /** Пропустить миграцию PRE_BOOT_COMPLETED (4.x): под эмуляцией она занимает минуты. */
    fun skipPreBoot(props: PropService) {
        if (img.api < 14) return
        val f = File(root, "data/system/called_pre_boots.dat")
        val rel = props.get("ro.build.version.release") ?: img.release
        val code = props.get("ro.build.version.codename") ?: "REL"
        val incr = props.get("ro.build.version.incremental") ?: ""
        val fresh = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                i.readInt() == 10000 && i.readUTF() == rel && i.readUTF() == code && i.readUTF() == incr
            }
        }.getOrDefault(false)
        if (fresh) return
        runCatching {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(10000); o.writeUTF(rel); o.writeUTF(code); o.writeUTF(incr); o.writeInt(0)
            }
        }
    }

    /** Экран не гаснет, данные «включены»: прямо в базе настроек (есть после первой загрузки). */
    private fun hasSonySetupFlow(): Boolean =
        File(root, "system/app/Initial-boot-setup.apk").isFile && File(root, "system/priv-app/SEMCSetupWizard.apk").isFile

    /** Called only before launching guest processes. Keep recovery copies of migrated data. */
    fun restoreSonySetupFlow() {
        if (!hasSonySetupFlow()) return
        val marker = File(paths.bin, "sony-setup-flow-v1")
        if (marker.isFile) return
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (!db.isFile || !restrictions.isFile) return // fresh imports already use stock defaults
        val prefs = File(root, "data/data/${SonySetupPolicy.PACKAGE}/shared_prefs/SetupWizard.xml")
        runCatching {
            val completed = prefs.isFile && SonySetupPolicy.completed(prefs.readText())
            if (!completed) {
                val xml = restrictions.readText()
                val restored = SonySetupPolicy.restorePackage(xml)
                // Only migrate a package disabled by the old compatibility policy.
                if (xml != restored) {
                    for (suffix in listOf("", "-wal", "-shm")) {
                        val source = File(db.path + suffix)
                        val backup = File(source.path + ".aemu-before-sony-setup-v1")
                        if (source.isFile && !backup.exists()) source.copyTo(backup)
                    }
                    val backup = File(restrictions.path + ".aemu-before-sony-setup-v1")
                    if (!backup.exists()) restrictions.copyTo(backup)
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                        d.beginTransaction()
                        try {
                            for ((table, name) in listOf("global" to "device_provisioned", "secure" to "user_setup_complete")) {
                                val cv = android.content.ContentValues().apply { put("value", "0") }
                                d.update(table, cv, "name=?", arrayOf(name))
                            }
                            d.setTransactionSuccessful()
                        } finally { d.endTransaction() }
                    }
                    restrictions.writeText(restored)
                    log("setup: restored Sony wizard and incomplete-setup flags; backups retained")
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("Sony owns setup completion; migration checked\n")
        }.onFailure { log("setup: Sony flow migration failed: ${it.message}") }
    }

    /**
     * settings.db of the guest: /data/data/... on stock layouts, /dbdata/databases/... on Samsung 2.x/3.x (the
     * framework hardcodes /dbdata there). Null until SettingsProvider has created it (after the first boot).
     */
    private fun settingsDb(): File? = listOf(
        "data/data/com.android.providers.settings/databases/settings.db",
        "dbdata/databases/com.android.providers.settings/settings.db",
    ).map { File(root, it) }.firstOrNull { it.isFile }

    fun noScreenSleep() {
        val db = settingsDb() ?: return
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun put(table: String, name: String, value: String) {
                    val exists = runCatching {
                        d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.count > 0 }
                    }.getOrDefault(false)
                    if (!exists) return
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update(table, cv, "name=?", arrayOf(name)) == 0) d.insert(table, null, cv)
                }
                put("system", "screen_off_timeout", "2147483647")
                val t = if (img.api >= 17) "global" else "system"
                put(t, "stay_on_while_plugged_in", "7")
                put(if (img.api >= 17) "global" else "secure", "mobile_data", "1")
                put(if (img.api >= 17) "global" else "secure", "package_verifier_enable", "0")
                put(if (img.api >= 17) "global" else "secure", "install_non_market_apps", "1")
                put("secure", "install_non_market_apps", "1")
                if (img.api >= 17) put("global", "verifier_verify_adb_installs", "0")
                // Samsung 2.x/3.x DeviceStorageMonitorService judges /dbdata by the host's real free space (statfs is not
                // faked there, unlike /data): a phone that is >90% full makes the guest "database low on memory", and the
                // stock messaging stack then stops storing incoming SMS. 1% instead of the default 10%.
                if (File(root, "dbdata").isDirectory) put("secure", "sys_storage_threshold_percentage", "1")
                // Preserve the older-engine behavior; the explicit live-provider option starts at API 16.
                if (img.api < 16 && !hasSonySetupFlow()) {
                    put("secure", "device_provisioned", "1")
                    put("secure", "user_setup_complete", "1")
                }
            }
        }.onFailure { log("settings: database failed to open: ${it.message}") }
        disableLegacyGoogleLogin()
    }

    /** Existing legacy Google sign-in workaround; independent of the setup-skip option. */
    /**
     * Packages that stall boot on legacy images, handled before the system starts (afterBoot() is too late: it only
     * runs once boot has completed). 4.0.x Glass: system_server binds com.google.android.backup's
     * BackupTransportService, the process never answers, ActivityManager reports "ANR in system" ("System is not
     * responding") and sys.boot_completed is never set. Backup is opted out in the guest anyway.
     * The APK (and its odex) is set aside in /system/.aemu-parked; PackageManager then drops the missing system app.
     */
    private fun disableStallingPackages() = runCatching {
        if (img.api >= 16) { log("boot stall guard: skipped (api ${img.api})"); return@runCatching }
        val pkg = "com.google.android.backup"
        val packagesXml = File(root, "data/system/packages.xml")
        val declared = if (packagesXml.isFile) Regex("<package name=\"${Regex.escape(pkg)}\"[^>]*?codePath=\"([^\"]+)\"").find(packagesXml.readText())?.groupValues?.get(1) else null
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        val candidates = LinkedHashSet<File>()
        if (declared != null) candidates += File(root, declared.trimStart('/'))
        for (dir in listOf("system/app", "system/priv-app", "system/vendor/app", "vendor/app"))
            File(root, dir).listFiles()?.filter { it.isFile && it.name.endsWith(".apk") && it.name.contains("Backup", ignoreCase = true) && it.name.contains("Google", ignoreCase = true) }?.let { candidates += it }
        log("boot stall guard: ${pkg} codePath=${declared ?: "unknown (no packages.xml entry)"}, candidates=${candidates.joinToString { it.name }}")
        var moved = 0
        for (apk in candidates) {
            if (!apk.isFile) continue
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) { moved++; log("boot stall guard: parked ${f.name}") } else log("boot stall guard: could not park ${f.name}")
            }
        }
        // Fallback / belt and braces: mark the package disabled for user 0 as well.
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (restrictions.isFile && packagesXml.isFile && packagesXml.readText().contains("<package name=\"$pkg\"")) {
            var xml = restrictions.readText()
            val m = Regex("<pkg name=\"${Regex.escape(pkg)}\"([^>]*?)(/?)>").find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$pkg\" enabled=\"2\" />\n</package-restrictions>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$pkg\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            }
        } else log("boot stall guard: no restrictions entry changed (restrictions=${restrictions.isFile}, packages.xml=${packagesXml.isFile})")
        if (moved == 0 && declared == null) log("boot stall guard: $pkg not found in this tree, nothing to do")
    }.onFailure { log("boot stall guard failed: ${it.message}") }

    /**
     * Glass: earlier versions switched off com.google.android.location's NetworkLocationService, which removes the
     * "network" location provider and makes com.google.glass.settings crash at boot (IllegalArgumentException:
     * provider=network). The disabled state sits in packages.xml, so it is cleared here, before system_server reads it.
     */
    private fun glassLocationProvider() = runCatching {
        if (img.api !in 9..16) return@runCatching
        val glass = GlassLocationPolicy.isGlass(img.name, img.model,
            File(root, "system/app/GlassBluetooth.apk").isFile || File(root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        if (!glass) return@runCatching
        val f = File(root, "data/system/packages.xml")
        if (!f.isFile) return@runCatching
        GlassLocationPolicy.reenable(f.readText())?.let {
            f.writeText(it)
            log("glass: network location service enabled again (settings needs the network provider)")
        }
    }.onFailure { log("glass: could not re-enable network location: ${it.message}") }

    /**
     * Glass 4.4: selects the Glass remote IME in settings.db so that InputMethodManagerService does not take the
     * "No IME selected" path that throws in its constructor (see [GlassImePolicy]). Needs a settings.db, which the
     * first (failing) boot creates; returns true when a value was written, so the caller can retry the boot.
     */
    fun seedDefaultIme(): Boolean {
        if (img.api < 19) return false
        val installed = listOf("system/priv-app/GlassRemoteIme.apk", "system/app/GlassRemoteIme.apk").any { File(root, it).isFile }
        if (!installed) return false
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        if (!db.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val hasTable = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='secure'", null).use { it.count > 0 }
                if (!hasTable) return@use false
                fun get(name: String): String? = d.rawQuery("SELECT value FROM secure WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
                fun put(name: String, value: String) {
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update("secure", cv, "name=?", arrayOf(name)) == 0) d.insert("secure", null, cv)
                }
                val v = GlassImePolicy.valueToSeed(get("default_input_method"), true) ?: return@use false
                put("default_input_method", v)
                put("enabled_input_methods", GlassImePolicy.enabledWith(get("enabled_input_methods"), v))
                log("glass: default input method set to $v (InputMethodManagerService no longer fails on first boot)")
                true
            }
        }.onFailure { log("glass: could not set the default input method: ${it.message}") }.getOrDefault(false)
    }

    private fun disableLegacyGoogleLogin() {
        val f = File(root, "data/system/users/0/package-restrictions.xml")
        if (!f.isFile) return
        // packages.xml, а не packages.list: в списке нет пакетов с общим системным uid (мастер Samsung)
        val pkgs = runCatching { File(root, "data/system/packages.xml").readText() }.getOrNull() ?: return
        val legacy = if (img.api < 16) listOf("com.sec.android.app.SecSetupWizard",
            "com.google.android.setupwizard", "com.android.provision", "com.miui.provision",
            "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard") else emptyList()
        val targets = (legacy + "com.google.android.gsf.login").filter {
            pkgs.contains("<package name=\"$it\"") && !(hasSonySetupFlow() && it == SonySetupPolicy.PACKAGE)
        }
        if (targets.isEmpty()) return
        var xml = runCatching { f.readText() }.getOrNull() ?: return
        var changed = false
        for (p in targets) {
            val open = Regex("<pkg name=\"${Regex.escape(p)}\"([^>]*?)(/?)>")
            val m = open.find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$p\" enabled=\"2\" />\n</package-restrictions>")
                changed = true
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$p\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                changed = true
            }
        }
        if (changed) runCatching { f.writeText(xml); log("legacy Google login disabled: ${targets.joinToString()}") }
    }

    /**
     * Static healthd (5.0+) checks the battery files with faccessat, which the stand's qemu does not map into the
     * guest root: it tests the phone's real /sys and finds no battery. Its path constant becomes the relative
     * "sys/class/power_supply" (resolved from the services' working directory, run/, where sys links to root/sys).
     */
    private fun healthdRelativeSysfs(base: File) {
        if (img.api < 21) return
        runCatching {
            val link = File(paths.bin, "sys")
            paths.bin.mkdirs()
            if (!isLink(link)) { wipe(link); Os.symlink(File(root, "sys").absolutePath, link.path) }
            // 8.0 moved healthd from the ramdisk to /system/bin; the battery path is compiled into either binary
            val hd = InitPlan.HEALTHD_PATHS.map { File(root, it.removePrefix("/")) }.firstOrNull { it.isFile } ?: return
            val d = hd.readBytes()
            val from = "/sys/class/power_supply".toByteArray()
            val to = "sys/class/power_supply".toByteArray() + 0
            var n = 0
            var i = 0
            while (i <= d.size - from.size) {
                if (d[i] == from[0] && (0 until from.size).all { d[i + it] == from[it] }) { System.arraycopy(to, 0, d, i, to.size); n++; i += from.size } else i++
            }
            if (n > 0) { hd.writeBytes(d); log("healthd: battery sysfs path made relative ($n)") }
        }.onFailure { log("healthd patch failed: ${it.message}") }
        if (!base.isDirectory) log("no fake power_supply")
    }

    /**
     * bionic 6.0+ starts every 32-bit process with personality(PER_LINUX32) and aborts ("error setting PER_LINUX32
     * personality") when it fails — and it does on phones without AArch32 (Snapdragon 8 Elite…), qemu passes the
     * call to the host kernel. The personality syscall stub (mov ip,r7; mov r7,#136; swi 0) gets "mov r0,#0"
     * instead of the swi, in libc.so and in static binaries (healthd, ueventd…). Done once per image.
     */
    private fun personalityNoop() {
        if (img.api < 23) return
        val stamp = File(root, ".aemu-personality")
        if (stamp.isFile && runCatching { stamp.readText().trim().toInt() }.getOrDefault(0) > 0) return
        val files = listOf(File(root, "system/lib/libc.so")) +
            listOf("sbin", "system/bin", "system/xbin").flatMap { File(root, it).listFiles().orEmpty().toList() }
        var n = 0
        for (f in files) {
            if (!f.isFile || isLink(f) || f.length() > 8_000_000 || f.length() < 1024) continue
            runCatching {
                val d = f.readBytes()
                if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte()) return@runCatching
                val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var hit = false
                var i = 0
                while (i + 12 <= d.size) {
                    // mov ip, r7 ; mov r7, #136 (6.0) or ldr r7, [pc, #x] holding 136 (7.x) ; swi #0
                    if (b.getInt(i) == 0xe1a0c007.toInt() && b.getInt(i + 8) == 0xef000000.toInt()) {
                        val w = b.getInt(i + 4)
                        val nr = when {
                            w == 0xe3a07088.toInt() -> 136
                            w and 0xfffff000.toInt() == 0xe59f7000.toInt() ->
                                (i + 4 + 8 + (w and 0xfff)).takeIf { it + 4 <= d.size }?.let { b.getInt(it) } ?: -1
                            else -> -1
                        }
                        if (nr == 136) { b.putInt(i + 8, 0xe3a00000.toInt()); hit = true }   // swi #0 → mov r0, #0
                    }
                    i += 4
                }
                if (hit) { f.writeBytes(d); n++ }
            }
        }
        log("personality(PER_LINUX32) made a no-op in $n files")
        runCatching { stamp.writeText("$n\n") }
    }

    /**
     * 7.0+ linker stats the executable it was started for, by the path from /proc/self/exe. qemu reports the
     * program's host path (<filesDir>/images/<id>/root/system/bin/servicemanager), but every guest path goes
     * through "-L root", so the guest looks for <root><host path> and gets ENOENT ("unable to stat file for the
     * executable", SIGABRT in linker, servicemanager/surfaceflinger die). The host path of root is made to exist
     * inside root as a link back to root. The target is absolute on purpose: /data/user/0 is itself a link to
     * ../data, so the real directory holding the alias is not where its path says, and a relative target would
     * miss. The host kernel resolves the absolute target, qemu does not remap symlink targets. Absolute links
     * are never exported (VmArchive) and the alias is rechecked on every boot.
     */
    /**
     * The alias sits in the guest's own /data/data (the guest's /data/user/0 is a link to it), under the name of a
     * package that does not exist there, and the guest removes it while it boots (the first dex2oat run by installd
     * and every program started after a zygote restart then abort in the linker with "unable to stat file for the
     * executable"). GuestVm calls this while the system runs and before it restarts zygote to put it back.
     */
    fun ensureHostPathAlias() = hostPathAlias()

    private var aliasMade = false

    private fun hostPathAlias() {
        if (img.api < 24) return
        runCatching {
            val hostRoot = root.absoluteFile.toPath().normalize()
            val rel = hostRoot.root.relativize(hostRoot)           // data/user/0/app.aemu.superplus/files/images/<id>/root
            if (rel.nameCount < 2) return
            val alias = File(root, rel.toString())
            val target = hostRoot.toString()
            alias.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val cur = runCatching { Os.readlink(alias.path) }.getOrNull()
            if (cur == target) return
            if (cur != null || alias.exists()) wipe(alias)
            Os.symlink(target, alias.path)
            log(if (aliasMade) "linker: host path of the guest root alias was removed by the guest, restored" else "linker: host path of the guest root aliased inside it")
            aliasMade = true
        }.onFailure { log("host path alias failed: ${it.message}") }
    }

    /**
     * bionic 6.0 finds the main thread's stack in /proc/self/task/<pid>/maps; qemu only emulates /proc/self/maps,
     * so the guest parses the host's 64-bit map and ART gets random stack bounds ("Check failed: &stack_variable >
     * stack_end", then SIGSEGV in zygote). The format string in libc.so becomes "/proc/self/maps".
     */
    /**
     * 5.0+: the NFC app's own watchdog aborts it while the vendor NFC stack initialises under qemu; that
     * persistent crash loops the whole system. There is no NFC to offer, so the app is moved out of the way.
     */
    private fun parkNfc() {
        // 7.0+ zygote: the shim shows qemu's /dhd.owners descriptor under this whitelisted name; after fork
        // zygote reopens it by that path, which has to lead back to the same table
        if (img.api >= 24) runCatching {
            val link = File(root, "system/framework/aemu-owners.jar")
            if (!isLink(link)) { link.delete(); Os.symlink(paths.owners.absolutePath, link.path) }
        }
        if (img.api < 21) return
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (dir in listOf("system/app", "system/priv-app")) File(root, dir).listFiles()?.filter { it.name.startsWith("Nfc") }?.forEach { f ->
            val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
            if (f.renameTo(dst)) log("parked ${f.name}")
        }
    }

    private fun mainStackMaps() {
        if (img.api < 21) return
        val libc = File(root, "system/lib/libc.so")
        runCatching {
            val d = libc.readBytes()
            val from = "/proc/self/task/%d/maps".toByteArray()
            val i = indexOf(d, from)
            if (i < 0) return
            val to = "/proc/self/maps".toByteArray()
            for (k in from.indices) d[i + k] = if (k < to.size) to[k] else 0
            libc.writeBytes(d)
            log("libc: main thread stack read from /proc/self/maps")
        }.onFailure { log("libc maps patch failed: ${it.message}") }
    }

    private fun indexOf(d: ByteArray, p: ByteArray): Int {
        var i = 0
        while (i <= d.size - p.size) {
            if (d[i] == p[0] && (1 until p.size).all { d[i + it] == p[it] }) return i
            i++
        }
        return -1
    }

    private fun isLink(f: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)
    private fun wipe(f: File) = ImageStore.wipe(f)

    companion object {
        /** hw_module_t.id that MediaTek's AudioFlinger requests for libaudio.primary.default.so. */
        private const val MTK_HAL_MODULE_ID = "libaudio"

        /** Прошивка MediaTek, где AudioFlinger связан с собственной звуковой библиотекой MTK (/dev/eac). */
        fun isMtkAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.mtk.so").isFile) return true   // already swapped for our HAL
            if (isMtkHwOnlyAudio(root)) return true
            val f = File(root, "system/lib/libaudio.primary.default.so")
            if (!f.isFile || f.length() > 20_000_000) return false
            return runCatching {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                // "AudioMTKHardware" is absent from some MTK 4.4 builds; any MTK audio class or an AudioFlinger
                // that links this very library (stock AOSP's does not) is just as conclusive
                text.contains("AudioMTK") || text.contains("AudioALSAHardware") ||
                    File(root, "system/lib/libaudioflinger.so").let { af ->
                        af.isFile && af.length() < 20_000_000 &&
                            String(af.readBytes(), Charsets.ISO_8859_1).contains("libaudio.primary.default.so")
                    }
            }.getOrDefault(false)
        }

        /**
         * MediaTek platform by any trace (modem/NVRAM daemons, build.prop), not only by the audio HAL string.
         * Some MTK 4.4 builds (e.g. ALSA-based audio) lack "AudioMTKHardware" yet still ship MTK's reworked
         * AudioPolicyService, where the AOSP policy stand-in dereferences a missing output descriptor.
         */
        fun isMtkPlatform(root: File): Boolean = isMtkAudio(root) || mtkTraces(root)

        /** MediaTek by modem/NVRAM daemons or build.prop alone (independent of the audio libraries). */
        fun mtkTraces(root: File): Boolean {
            if (listOf("nvram_daemon", "ccci_mdinit", "muxreport", "gsm0710muxd").any { File(root, "system/bin/$it").isFile }) return true
            return runCatching {
                File(root, "system/build.prop").readText()
                    .contains(Regex("(?m)^(ro\\.mediatek\\.[^=\\s]*=|ro\\.board\\.platform=mt|ro\\.hardware=mt)"))
            }.getOrDefault(false)
        }

        /**
         * MediaTek 4.2+ whose AudioFlinger takes the HAL from the usual system/lib/hw/audio.primary.default.so
         * (no libaudio.primary.default.so in system/lib): the AOSP-layout stand-in HAL crashes its AudioFlinger,
         * so the MediaTek-layout HAL goes into hw/ instead.
         */
        fun isMtkHwOnlyAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.primary.default.so").isFile || File(root, "system/lib/libaudio.mtk.so").isFile) return false
            if (!File(root, "system/lib/hw/audio.primary.default.so").isFile) return false
            val sdk = runCatching {
                Regex("(?m)^ro\\.build\\.version\\.sdk=(\\d+)").find(File(root, "system/build.prop").readText())?.groupValues?.get(1)?.toInt()
            }.getOrNull() ?: return false
            return sdk in 17..20 && mtkTraces(root)
        }

        private val DBDATA_DIRS = listOf("dbdata", "dbdata/databases", "dbdata/system")

        /**
         * Samsung 2.x keeps app databases and the account / sync registries on a separate /dbdata partition
         * (/dbdata/databases/<pkg>/, /dbdata/system/), which init.rc mounts and the framework then uses by
         * absolute path. There is no such partition here, so nothing creates those directories: SettingsProvider fails with
         * "unable to open database file", system_server dies on it and zygote exits. Returns the directories to create
         * when the init scripts ([rcTexts]) or their mkdir lines ([initDirs]) mention /dbdata, else nothing.
         */
        internal fun dbdataDirs(rcTexts: List<String>, initDirs: List<String>, legacySamsung: Boolean = false, alreadyThere: Boolean = false): List<String> =
            // the framework hardcodes /dbdata on Samsung 2.x/3.x even when the ramdisk's init.rc never mentions it (the mount lives in a vendor script)
            if (legacySamsung || alreadyThere || initDirs.any { it.contains("dbdata") } || rcTexts.any { it.contains("/dbdata") }) DBDATA_DIRS else emptyList()

        private val LOGS = listOf("main", "system", "radio") // events — канал, его делает EventsSink
        private val DATA_DIRS = listOf(
            "data", "data/app", "data/app-private", "data/app-lib", "data/app-asec", "data/data", "data/dalvik-cache",
            "data/local", "data/local/tmp", "data/misc", "data/misc/keystore", "data/misc/wifi", "data/misc/zoneinfo",
            "data/property", "data/system", "data/anr", "data/backup", "data/lost+found", "data/drm", "data/media",
            "data/resource-cache", "data/security", "data/tombstones", "cache", "cache/download", "cache/lost+found",
            "mnt", "mnt/asec", "mnt/obb", "mnt/secure", "mnt/secure/asec", "mnt/shell", "mnt/media_rw", "storage",
        )
    }
}