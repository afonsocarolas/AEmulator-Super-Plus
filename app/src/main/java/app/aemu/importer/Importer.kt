/* Modified for AEmulator Sunset, 2026-10-03: VM archive restore and boot retention.
 * Modified for AEmulator Super Plus, 2026-10-11: Sony .ftf / .sin import.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import android.content.Context
import android.net.Uri
import android.system.Os
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.TreeFixer
import app.aemu.core.VmPaths
import app.aemu.core.RecoveryImage
import app.aemu.core.SystemLayout
import app.aemu.core.StockImage
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive as RarArchive
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import app.aemu.importer.tools.FirmwareToolset
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * Импорт прошивки в дерево гостя. Понимает (в том числе вложенные друг в друга):
 *  - ZIP для CWM/TWRP (MIUI, CyanogenMod, большинство прошивок 2.x–4.x): system/… + boot.img + updater-script
 *  - ZIP/TGZ factory-образов Google, прошивки с system.img внутри
 *  - TAR / TAR.MD5 (Samsung Odin, TouchWiz), TWRP-бэкапы (.win), tar.gz/xz/bz2
 *  - Huawei dload/UPDATE.APP (packet chain, YAFFS2 system + boot/recovery images)
 *  - Acer flash package (*.bin / *.nb0: record table + YAFFS2 rootfs.img / system.img)
 *  - Sony Flashtool .ftf (ZIP из .sin v1–v4: system, kernel (ELF), fotakernel) и отдельные .sin
 *  - system.img: ext2/3/4, в том числе sparse (и разбитый на части: system.img_sparsechunk.N, system_sparsechunkN, system.img.N, system.N, system_N …)
 *  - system.new.dat(.br) + system.transfer.list (OTA 5.x–6.x)
 *  - уже готовое дерево rootfs в tar.gz (например, из стендов HTC)
 */
class Importer(
    private val ctx: Context,
    private val onProgress: (String, Float) -> Unit,
    private val log: (String) -> Unit,
) {
    private lateinit var paths: VmPaths
    private lateinit var root: File
    private val tmp = File(ctx.cacheDir, "import").apply { mkdirs() }
    private var ramdisk: List<BootImage.CpioEntry>? = null
    private val MAX_RAMDISK = 64L * 1024 * 1024
    private val MAX_RAMDISK_FILE = 32L * 1024 * 1024
    private var recovery: ByteArray? = null
    private val symlinks = ArrayList<Pair<String, String>>() // (цель, путь ссылки в госте)
    private val perms = ArrayList<Triple<String, Int, Boolean>>() // (путь, режим, рекурсивно-файлы)
    private var files = 0
    private var bytes = 0L
    private var gotSystem = false
    private var gotOem = false
    /** The system partition held the whole root file system (Android 7+ A/B "system-as-root": init, init*.rc, sepolicy, sbin/ at its top). */
    private var systemAsRoot = false
    /** boot image whose ramdisk is only the recovery ramdisk (A/B devices keep recovery in boot); used only if the system partition is not system-as-root. */
    private var recoveryBoot: ByteArray? = null
    @Volatile var cancelled = false

    fun import(uri: Uri, name: String): GuestImage {
        if (name.endsWith(".aessvm", true)) {
            return app.aemu.core.VmArchiveStorage.restore(ctx, uri) { path ->
                if (cancelled) throw IOException("cancelled")
                onProgress("Restoring $path", -1f)
            }.also { onProgress("Done", 1f) }
        }
        val id = ImageStore.newId(ctx)
        paths = VmPaths(ctx, id)
        root = paths.root
        root.mkdirs()
        try {
            onProgress("Opening $name", 0f)
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("file failed to open")
            pfd.use {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val seekable = runCatching { ch.position(0); ch.size() > 0 }.getOrDefault(false)
                if (seekable) handle(ChannelSource(ch), ch, name, 0)
                else {
                    val t = spill(FileInputStream(pfd.fileDescriptor), "whole")
                    FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, name, 0) }
                    t.delete()
                }
            }
            useDeferredBoot()
            // build.prop is optional: some dumps/ports lack it, Analyzer infers the version from the tree
            if (!File(root, "system/framework").isDirectory && !File(root, "system/build.prop").isFile)
                throw IOException("no Android system partition found in file")
            finishTree()
            recovery?.let { r -> runCatching { RecoveryImage.install(paths, r, log) }.onFailure { log("recovery: install failed (${it.message}), skipped") } }
            onProgress("Analyzing firmware", 0.97f)
            val img = Analyzer(ctx, paths, ramdisk).analyze(id, name)
            TreeFixer(ctx, paths, img, log).sanitize()
            ImageStore.save(ctx, img.copy(sizeBytes = ImageStore.du(paths.dir)))
            onProgress("Done", 1f)
            log("import: $files files, ${bytes shr 20} MB")
            return ImageStore.get(ctx, id)!!
        } catch (t: Throwable) {
            ImageStore.delete(ctx, id)
            throw t
        } finally {
            tmp.listFiles()?.forEach { deleteTree(it) }
        }
    }

    // ------------------------------------------------------------------ разбор контейнеров

    private fun handle(src: RandomSource, ch: FileChannel?, name: String, depth: Int, role: FirmwareToolset.Role? = null) {
        if (depth > 8) return
        val head = ByteBuffer.allocate(1100)
        src.read(0, head); head.flip()
        val h = ByteArray(head.remaining()).also { head.get(it) }
        if (h.size < 2) throw IOException("empty or truncated firmware file")
        when {
            name.endsWith(".ofp", true) && h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                val source = spill(streamOf(src), name)
                val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
                    if (artifacts.isEmpty()) throw IOException("OFP ZIP produced no files")
                    for (artifact in artifacts) {
                        if (!artifact.file.isFile) continue
                        FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                        }
                    }
                } finally { deleteTree(toolWork); source.delete() }
            }
            // Sony .sin partition image (a member of an .ftf bundle, or picked on its own); v4 is a bare tar/gzip,
            // so the name is checked before the archive probes below
            name.endsWith(".sin", true) || (h.size > 4 && h[0].toInt() == 3 && String(h, 1, 3, Charsets.ISO_8859_1) == "SIN") ->
                importSin(src, name)
            h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                if (ch != null) importZip(ch, name, depth) else throw IOException("zip without random access")
            }
            h.size > 6 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() && h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() -> {
                if (ch != null) importSevenZ(ch, name, depth) else throw IOException("7z without random access")
            }
            h.size >= 7 && h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() &&
                h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() && h[4] == 0x1a.toByte() -> {
                val archive = spill(streamOf(src), name)
                try { importRar(archive, name, depth) } finally { archive.delete() }
            }
            h.size >= 6 && (String(h, 0, 6, Charsets.US_ASCII) == "070701" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070702" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070707") ->
                importCpioStream(streamOf(src), name, depth)
            // full eMMC/UFS dump with a GUID partition table: boot / recovery / system are cut out of it
            GptDisk.probe(h) && src.size > (1L shl 20) -> importGptDisk(src, name)
            // Huawei dload/UPDATE.APP: packet chain of raw partition images (the container has no name-based hint)
            HuaweiApp.probe(h) -> importHuaweiApp(src, name, strict = depth == 0)
            // Acer flash package (*.bin / *.nb0): table of named images, YAFFS2 rootfs + system
            AcerBin.probe(h) -> importAcerBin(src, name, strict = depth == 0)
            // HTC: RUU_*.exe installer, Dream-style .nbh, 256-byte-signed rom.zip / *_signed.img.
            // Routed explicitly so the heuristic probes below never look at a multi-hundred-MB executable.
            FirmwareToolset.isHtcContainer(h) -> importViaToolset(src, name, depth)
            // A boot/recovery image that a container extractor (PAC, NBH, TOT/BIN, KDZ, OFP, payload.bin, RUU, ...)
            // already classified: its role decides, whatever the partition happened to be called in that container
            // ("005-recovery.img", "02_Recovery.img", "mmcblk0p15", ...).
            // oem: optional, never boot-critical. Matched before the sparse/ext4 probes below, which would take it for system.
            role == FirmwareToolset.Role.OEM || isOemName(name.replace('\\', '/').substringAfterLast('/')) -> importOem(src)
            // optional /vendor partition (vendor.img, vendor_a.bin): matched before the ext4 probe, which would take it for system
            isVendorName(name.replace('\\', '/').substringAfterLast('/')) -> importVendor(src)
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isAndroidImage(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isLz4Frame(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            SparseSource.probe(src) -> importImage(SparseSource(listOf(src)), "system")
            Ext4Reader.probe(src) -> importImage(src, "system")
            MotoImage.probe(src) -> importImage(src, "system")
            UbiFsReader.probe(src) -> importImage(src, "system")
            Yaffs2Reader.probe(src) -> importImage(src, "system")
            // Compression signatures win over misleading suffixes (a gzip file named *.tgz.tar is not a plain tar)
            FirmwareContainers.compression(h) != null -> when (FirmwareContainers.compression(h)!!) {
                FirmwareContainers.Compression.GZIP -> importCompressed(GZIPInputStream(streamOf(src), 1 shl 16), name, depth)
                FirmwareContainers.Compression.XZ -> importCompressed(XZInputStream(streamOf(src)), name, depth)
                FirmwareContainers.Compression.BZIP2 -> importCompressed(BZip2CompressorInputStream(streamOf(src)), name, depth)
            }
            isTar(h) || name.endsWith(".tar", true) || name.endsWith(".md5", true) || name.endsWith(".win", true) ->
                importTarStream(streamOf(src), name, depth)
            // a recovery image reached directly (extracted by the firmware toolset, nested archive, plain file)
            isRecoveryName(name.replace('\\', '/').substringAfterLast('/')) && src.size < 64_000_000 ->
                takeRecovery(streamOf(src), name.replace('\\', '/').substringAfterLast('/'))
            h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!" -> {
                val data = readAllFrom(src)
                val base = name.replace('\\', '/').substringAfterLast('/')
                // an unnamed / oddly named image ("part_7.img", "mmcblk0p9") whose ramdisk carries /sbin/recovery is a recovery
                if (role != FirmwareToolset.Role.BOOT && !isBootName(base) && hasRecoveryBinary(data)) takeRecovery(data.inputStream(), recoveryLabel(name, h))
                else takeBoot(data)
            }
            // system.img / factoryfs / factoryfs.img / factoryfs.rfs: a system partition whatever the filesystem
            isSystemImageName(name.replace('\\', '/').substringAfterLast('/')) ->
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
            else -> importViaToolset(src, name, depth)
        }
    }

    /**
     * Spills the file, lets [FirmwareToolset] unpack it and feeds every artifact back into [handle].
     * Large intermediates are dropped as early as possible: an HTC One S RUU is a 518 MB installer that
     * yields a 1 GB system.img, which would otherwise sit next to it until the whole import ends.
     */
    private fun importViaToolset(src: RandomSource, name: String, depth: Int) {
        val source = spill(streamOf(src), name)
        val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
        try {
            val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
            if (artifacts.isEmpty()) {
                // *.bin is only a name guess ("allow-mbmloader-flashing-mbm.bin" of a Motorola package is a bootloader, not
                // an LG container): inside an archive that is no reason to abort the whole import
                if (depth > 0 && name.endsWith(".bin", true)) { log("skipped $name: not a firmware container"); return }
                throw IOException("unknown file format \"$name\"")
            }
            // the unpacked copy is all that is needed from here on (unless an artifact IS the spilled file)
            if (artifacts.none { it.file == source }) source.delete()
            for (artifact in artifacts) {
                if (cancelled) throw IOException("cancelled")
                if (!artifact.file.isFile) continue
                FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                    handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                }
                if (artifact.cleanup && artifact.file != source) artifact.file.delete()
            }
        } finally {
            deleteTree(toolWork)
            source.delete()
        }
    }

    private fun recognizedFirmwareTool(name: String): Boolean {
        val n = name.lowercase()
        if (n == "file_contexts.bin" || n == "file_contexts" || n.endsWith("/file_contexts.bin")) return false
        // HTC RUU installers only: a bare *.exe in an archive is usually adb/fastboot/driver tooling
        if (n.endsWith(".exe") && n.substringAfterLast('/').startsWith("ruu")) return true
        return n.endsWith(".pac") || n.endsWith(".sbf") || n.endsWith(".nbh") ||
            n.endsWith(".tot") || n.endsWith(".cab") || isLikelyLgBinName(n) || n.endsWith(".ofp") ||
            n.endsWith(".ops") || n.endsWith(".pkg") || n.endsWith(".rfs") ||
            n.endsWith(".squashfs") || n.endsWith(".new.dat") || n.endsWith(".new.dat.br") ||
            n.endsWith(".transfer.list") || n == "payload.bin" || n == "super.img"
    }

    // A generic *.bin is too broad: Android root files such as file_contexts.bin
    // are data files, not LG firmware containers. Only treat conventional LG
    // container names as tools here; header-based detection still handles an
    // actual LG BIN when it is presented directly to handle().
    private fun isLikelyLgBinName(n: String): Boolean {
        val b = n.substringAfterLast('/')
        if (!b.endsWith(".bin")) return false
        if (b == "file_contexts.bin") return false
        return b.startsWith("lg", true) || b.contains("kdz", true) ||
            b.contains("firmware", true) || b.contains("flash", true)
    }

    private fun spillPreservingName(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "entry" }
        val dir = File(tmp, "entries-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, safe)
        f.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
        return f
    }

    /**
     * gzip/xz/bz2 wrapper: unpack to a temp file and re-dispatch on the content. Feeding a decompressing
     * stream straight into the TAR reader broke .tar.gz/.tgz ("Corrupted TAR archive"), and a plain .gz
     * of a non-tar image never worked. A seekable file goes through the same path as an uncompressed tar.
     */
    private fun importCompressed(decoded: InputStream, name: String, depth: Int) {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val inner = base.replace(Regex("(?i)\\.(tgz|tbz2?|txz)$"), ".tar")
            .replace(Regex("(?i)\\.(gz|xz|bz2)$"), "").ifEmpty { "payload" }
        val f = decoded.use { spill(it, inner) }
        try {
            if (f.length() < 2) throw IOException("compressed file is empty: $name")
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, inner, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    /** recovery.img (optionally lz4-framed) or a TWRP raw recovery.*.win backup. */
    private fun isRecoveryName(base: String) = PartitionNames.isRecovery(base)

    private fun isOemName(base: String) = PartitionNames.isOem(base)

    private fun isVendorName(base: String) = PartitionNames.isVendor(base)

    /**
     * The /vendor partition (A/B devices such as Android Things on MT8516 mount it from fstab) is read like system and
     * lands in the guest's /vendor. It is optional for importing, so a failure is logged and the partition skipped.
     */
    private fun importVendor(src: RandomSource) {
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "vendor")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("vendor: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
        }
    }

    /**
     * The OEM partition (/oem: carrier / OEM customisation, sometimes the Samsung/Huawei/Moto extras) is optional and
     * not needed to boot, so anything that goes wrong with it is logged and skipped; a half-read one is removed again.
     */
    private fun importOem(src: RandomSource) {
        if (gotOem) return
        val dir = File(root, "oem")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
            gotOem = true
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("oem: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/oem/") }
            if (!existed) deleteTree(dir)
        }
    }

    /**
     * Settings import of an OEM image into an existing VM: [image] is a raw / sparse ext, YAFFS2 or Moto-signed image,
     * [dest] a staging folder that gets "oem/…". The caller moves it into the VM only if this returns normally.
     */
    internal fun importOemInto(image: File, dest: File) {
        root = dest
        File(dest, "oem").mkdirs()
        FileChannel.open(image.toPath(), StandardOpenOption.READ).use { c ->
            val src = ChannelSource(c)
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
        }
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            if (!rel.startsWith("oem/")) continue
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(File(root, "oem").canonicalPath)) continue
            f.parentFile?.mkdirs()
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(relTarget(link, target), f.absolutePath)
            }
        }
    }

    /** *.APP inside an archive: read in place when stored, otherwise through a temp file (the packets need random access). */
    private fun importZipHuaweiApp(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // computes dataOffset
            if (e.dataOffset > 0) { importHuaweiApp(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importHuaweiApp(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    /** First [n] bytes of a zip entry (fewer if it is shorter). */
    private fun zipEntryHead(zip: ZipFile, e: ZipArchiveEntry, n: Int): ByteArray = zip.getInputStream(e).use { s ->
        val b = ByteArray(n)
        var o = 0
        while (o < n) { val r = s.read(b, o, n - o); if (r <= 0) break; o += r }
        b.copyOf(o)
    }

    /** Acer *.bin / *.nb0 inside an archive: read in place when stored, otherwise through a temp file (the table needs random access). */
    private fun importZipAcerBin(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // computes dataOffset
            if (e.dataOffset > 0) { importAcerBin(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        onProgress("Unpacking $base", -1f)
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importAcerBin(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    /**
     * Acer flash package (see [AcerBin]). The importer takes
     *  - `system.img`, the YAFFS2 /system partition (any other image only if no system.img is there and its tree looks like /system),
     *  - `rootfs.img`, the YAFFS2 root file system, as the ramdisk (init*.rc, default.prop, sbin/...), because the
     *    kernel is flashed on its own and there is no boot.img,
     *  - an "ANDROID!" boot / recovery image if a (later) package carries one.
     * Boot loader pieces, kernel, modem, userdata, hidden/modules, factory-test rootfs and the YAFFS2 recovery
     * root file system are skipped.
     * [strict]: the file was chosen directly, so a package without a system partition is an error.
     */
    private fun importAcerBin(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val entries = try { AcerBin.entries(src) } catch (ex: IOException) {
            if (strict) throw ex
            log("skipped $label: ${ex.message}"); return
        }
        val profile = AcerBin.profile(src, entries)
        log("acer $label: ${entries.size} images" +
            (profile["PROJECT"]?.let { ", project $it" } ?: "") + (profile["PLATFORM"]?.let { ", platform $it" } ?: "") +
            (profile["MODEL"]?.let { ", model $it" } ?: ""))
        fun slice(e: AcerBin.Entry): RandomSource = SliceSource(src, e.offset, e.size)
        fun head(e: AcerBin.Entry, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), e.size).toInt()); slice(e).read(0, b); return b.array()
        }

        // boot / recovery images in Android format (the Gingerbread-era E1xx packages have none)
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            if (e.size < 4096 || e.size > 64_000_000) continue
            if (String(head(e, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(e))
            if (isRecoveryName(e.base) || hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery (${e.base})") else takeBoot(data)
        }

        // ramdisk: the root file system image
        if (ramdisk == null) entries.firstOrNull { AcerBin.isRootfs(it.base) && it.size > 0 }?.let { takeYaffs2Ramdisk(slice(it), "$label ${it.base}") }

        // system partition: system.img, otherwise the file system image whose tree looks like /system
        val fsEntries = ArrayList<AcerBin.Entry>()
        val systemEntry = entries.firstOrNull { isSystemImageName(it.base) && it.size > 0 } ?: run {
            for (e in entries) {
                if (e.size < (4L shl 20) || AcerBin.isRootfs(e.base) || e.base.contains("ftm", true)) continue
                val h = head(e, 8)
                if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) continue
                val s = slice(e)
                if (SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s)) fsEntries.add(e)
            }
            fsEntries.firstOrNull { looksLikeSystem(slice(it)) }
        }
        if (systemEntry == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw IOException("\"$label\" has no system partition (not a full Acer firmware package?)")
            log("acer $label: $what")
            return
        }
        val s = slice(systemEntry)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (e in entries) {
            if (e === systemEntry) continue
            if (e.base.endsWith(".img", true) && e.size >= 4096)
                log("acer $label: skipped ${e.base} (${e.size shr 10} KB)")
        }
    }

    /**
     * Root file system image (YAFFS2) as ramdisk: the same list of files finishTree takes from a boot.img ramdisk.
     * Without an init.rc the image is not a root file system and is ignored.
     */
    private fun takeYaffs2Ramdisk(src: RandomSource, label: String) {
        if (ramdisk != null) return
        try {
            val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            fs.walk { path, node ->
                val perm = node.mode and 0xfff
                when (node.type) {
                    3 -> { out.add(BootImage.CpioEntry(path, 0x4000 or (if (perm != 0) perm else 0x1ed), ByteArray(0))) }
                    2 -> { if (node.alias.isNotEmpty()) out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, node.alias.toByteArray())) }
                    1, 4 -> {
                        if (node.size > MAX_RAMDISK_FILE) { log("ramdisk $label: skipped $path (${node.size shr 20} MB)"); return@walk }
                        val bytes = java.io.ByteArrayOutputStream(node.size.toInt().coerceAtLeast(16))
                        fs.copy(node, bytes)
                        total += bytes.size()
                        if (total > MAX_RAMDISK) throw IOException("ramdisk too large")
                        out.add(BootImage.CpioEntry(path, 0x8000 or (fs.fileMode(node) and 0xfff), bytes.toByteArray()))
                    }
                    else -> {} // device nodes: the guest creates /dev itself
                }
            }
            fs.warnings.forEach { log("ramdisk $label: $it") }
            if (out.none { it.name == "init.rc" }) { log("ramdisk $label: no init.rc, not a root file system"); return }
            ramdisk = out
            log("boot: ramdisk from YAFFS2 $label, ${out.size} entries")
        } catch (ex: IOException) {
            if (cancelled) throw ex
            log("ramdisk $label: ${ex.message}, skipped")
        }
    }

    /**
     * Huawei UPDATE.APP (see [HuaweiApp]). Of its images the importer needs the system partition (YAFFS2 or ext4),
     * the boot image and, optionally, the recovery image; modem, bootloader pieces, userdata and the carrier
     * customisation partition are skipped. Which ANDROID! image is boot and which is recovery is decided by the
     * ramdisk (/sbin/recovery), because the image ids differ between devices.
     * [strict]: the file was chosen directly, so a package without a system partition (UPDATE_cust.APP) is an error.
     */
    private fun importHuaweiApp(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val packets = HuaweiApp.packets(src)
        if (packets.isEmpty()) {
            if (strict) throw IOException("not a Huawei update package: $label")
            log("skipped $label: not a Huawei update package"); return
        }
        log("huawei $label: ${packets.size} packets, hardware ${packets[0].hardware.ifEmpty { "?" }}, built ${packets[0].date}")
        fun slice(p: HuaweiApp.Packet): RandomSource = SliceSource(src, p.dataOffset, p.size)
        fun head(p: HuaweiApp.Packet, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), p.size).toInt()); slice(p).read(0, b); return b.array()
        }

        // boot / recovery
        for (p in packets) {
            if (cancelled) throw IOException("cancelled")
            if (p.size < 4096 || p.size > 64_000_000) continue
            if (String(head(p, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(p))
            val tag = "$label id 0x%02x".format(p.id)
            if (hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery ($tag)") else takeBoot(data)
        }

        // filesystems: the system partition is image id 0, otherwise the one whose tree looks like /system
        val fsParts = packets.filter { p ->
            if (p.size < (4L shl 20)) return@filter false
            val h = head(p, 8)
            if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) return@filter false
            val s = slice(p)
            SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s)
        }
        val systemPacket = fsParts.firstOrNull { it.id == HuaweiApp.ID_SYSTEM }
            ?: fsParts.firstOrNull { looksLikeSystem(slice(it)) }
        if (systemPacket == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw IOException("\"$label\" has no system partition (customization-only package?); import UPDATE.APP instead")
            log("huawei $label: $what; skipped ${fsParts.size} other partition(s)")
            return
        }
        onProgress("Verifying $label", -1f)
        val bad = HuaweiApp.verify(src, systemPacket) { if (cancelled) throw IOException("cancelled") }
        if (bad > 0) log("huawei $label: $bad block(s) of the system image fail their CRC, the download may be damaged")
        val s = slice(systemPacket)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (p in fsParts) if (p !== systemPacket) log("huawei $label: skipped partition id 0x%02x (%d KB)".format(p.id, p.size shr 10))
    }

    private fun looksLikeSystem(src: RandomSource): Boolean = runCatching {
        var found = false
        val s = if (SparseSource.probe(src)) SparseSource(listOf(src)) else MotoImage.unwrap(src)
        if (UbiFsReader.probe(s)) UbiFsReader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (Yaffs2Reader.probe(s)) Yaffs2Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (Ext4Reader.probe(s)) Ext4Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        found
    }.getOrDefault(false)

    private fun isAndroidImage(h: ByteArray) = h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!"
    private fun isLz4Frame(h: ByteArray) = h.size >= 4 && h[0] == 0x04.toByte() && h[1] == 0x22.toByte() && h[2] == 0x4d.toByte() && h[3] == 0x18.toByte()
    /** takeRecovery decides about lz4 framing by the name, so a framed image always gets the suffix. */
    private fun recoveryLabel(name: String, h: ByteArray): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        return if (isLz4Frame(h) && !base.endsWith(".lz4", true)) "$base.lz4" else base
    }
    private fun hasRecoveryBinary(img: ByteArray): Boolean =
        runCatching { BootImage.ramdisk(img)?.any { it.name.removePrefix("./") == "sbin/recovery" } == true }.getOrDefault(false)

    /** boot.img, or the signed one: boot_signed.img (HTC: 256-byte signature + image, BootImage strips it) / boot_signed (Motorola fastboot XML). */
    private fun isBootName(base: String) = PartitionNames.isBoot(base)

    /** Recovery is optional: remember the first one found, and never let a bad one abort the import. */
    private fun takeRecovery(i: InputStream, base: String) {
        runCatching {
            val data = BootImage.stripHtcSignature((if (base.endsWith(".lz4", true))
                org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(i) else i).readBytes())
            if (recovery == null && data.size >= 512) { recovery = data; log("recovery: found $base, ${data.size shr 10} KB") }
        }.onFailure { log("recovery: cannot read $base (${it.message}), skipped") }
    }

    private fun isTar(h: ByteArray) = h.size > 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar"

    private fun streamOf(src: RandomSource): InputStream = object : InputStream() {
        var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= src.size) return -1
            val k = minOf(len.toLong(), src.size - pos).toInt()
            src.read(pos, ByteBuffer.wrap(b, off, k))
            pos += k
            return k
        }
    }.let { BufferedInputStream(it, 1 shl 20) }

    private fun readAllFrom(src: RandomSource): ByteArray {
        val b = ByteBuffer.allocate(src.size.toInt()); src.read(0, b); return b.array()
    }

    private var zipChannel: FileChannel? = null
    /** partition images spilled out of a nested zip, imported only after that zip's temp file is gone (disk peak) */
    private var deferred: MutableList<Pair<File, String>>? = null

    private fun importZip(ch: FileChannel, name: String, depth: Int) {
        val zip = ZipFile.builder().setSeekableByteChannel(ch).get()
        val prevCh = zipChannel
        zipChannel = ch
        try { importZipEntries(zip, name, depth) } finally { zipChannel = prevCh }
    }

    private fun importZipEntries(zip: ZipFile, name: String, depth: Int) {
        val entries = zip.entries.toList()
        val total = entries.sumOf { maxOf(0L, it.size) }.coerceAtLeast(1)
        var done = 0L
        // сначала — сценарий установки (ссылки и права)
        entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }?.let { e ->
            zip.getInputStream(e).use { parseUpdaterScript(String(it.readBytes())) }
        }
        // прошивка, упакованная вместе с папкой (Имя/META-INF/…, Имя/system/…): папку-обёртку снимаем
        val wrap = entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }
            ?.name?.substringBefore("META-INF/")?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 } ?: ""
        if (wrap.isNotEmpty()) log("archive with wrapper folder \"${wrap.trimEnd('/')}\"")
        val names = entries.map { it.name.replace('\\', '/').removePrefix(wrap) }
        // system folder dump at any depth (Firmware/system/…); null if the archive has none
        val sysRoot = findSystemRoot(names.filter { !it.contains("__MACOSX/") })
        if (sysRoot != null && sysRoot.isNotEmpty()) log("system folder dump under \"${sysRoot.trimEnd('/')}\"")
        // zip of a raw system partition (build.prop + bin/ at the zip root, no system/ folder): all of it goes under system/
        val rawDump = sysRoot == null && PartitionNames.isRawSystemDump(names)
        if (rawDump) log("raw system partition dump (build.prop and bin/ at the archive root)")
        val bootEntries = ArrayList<Pair<String, ZipArchiveEntry>>()
        val pendingZips = ArrayList<ZipArchiveEntry>()
        // split sparse system (system.img_sparsechunk.N, system_sparsechunkN, system.img.N, system.N, system_N, …)
        val sparseChunks = SparseChunks.select(entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") }.map { it.name })
            .let { sel -> val byName = entries.associateBy { it.name }; sel.mapNotNull { byName[it] } }
        val rawProgramEntries = entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") &&
            it.name.substringAfterLast('/').matches(Regex("(?i)rawprogram.*\\.xml")) }
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            val n = e.name.replace('\\', '/').removePrefix(wrap)
            val base = n.substringAfterLast('/')
            when {
                n.contains("__MACOSX/") || base.startsWith("._") -> {}
                rawDump -> {
                    val rel = "system/" + n.trimStart('/')
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, rel).mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    }
                    gotSystem = true
                }
                e.isDirectory -> {}
                // everything inside a system folder dump belongs to it (never scanned for boot.img etc.)
                systemRel(sysRoot, n) != null -> {
                    val srel = systemRel(sysRoot, n)!!
                    if (e.isUnixSymlink) {
                        // a zip that kept its symlinks stores the target as the entry body; writing it as a file left
                        // "sh" as a 4-byte text file ("Exec format error")
                        val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }.trim('\u0000', '\n', '\r')
                        if (target.isNotEmpty()) symlinks.add(target to "/$srel")
                    } else zip.getInputStream(e).use { writeFile(srel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    gotSystem = true
                }
                // whole-disk image with a GPT inside the archive (Android Things iot_rpi3.img: 4.5 GB, deflated):
                // read once as a stream, only the boot / oem / system / gapps slots are kept
                base.endsWith(".img", true) && e.size > (16L shl 20) && GptDisk.probe(zipEntryHead(zip, e, 4104)) -> {
                    val ok = zip.getInputStream(e).use { importGptStream(it, base) }
                    if (!ok) log("$base: partition table unreadable, skipped")
                }
                base.equals("system.yaffs2.img", true) -> withEntrySource(zip, e) { importImage(it, "system") }
                base.endsWith(".yaffs2.img", true) -> {} // CWM user-data/cache backups are not firmware.
                // Sony Flashtool .ftf bundle: system.sin, kernel.sin, fotakernel.sin ...
                base.endsWith(".sin", true) -> withSinSource(zip, e) { importSin(it, base) }
                // boot.img at any depth; the shallowest valid one is taken after the loop
                isBootName(base) -> bootEntries.add(n to e)
                isRecoveryName(base) && e.size < 64_000_000 -> zip.getInputStream(e).use { takeRecovery(it, base) }
                isOemName(base) -> withEntrySource(zip, e) { importOem(it) }
                isSystemImageName(base) ->
                    withEntrySource(zip, e) { importImage(if (SparseSource.probe(it)) SparseSource(listOf(it)) else it, "system") }
                isVendorName(base) -> withEntrySource(zip, e) { importVendor(it) }
                base.lowercase().endsWith(".zip") && (base.startsWith("image-") || depth == 0 && e.size > 50_000_000) ||
                    base.lowercase().endsWith(".rar") || base.lowercase().endsWith(".7z") -> {
                    handleNestedZip(spillEntry(zip, e), base, depth)
                }
                base.lowercase().endsWith(".zip") && e.size > 0 && !SKIP_NESTED.matches(base) -> pendingZips.add(e)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5|md5|tgz|tar\\.gz)")) 
                    // Odin: AP/PDA/CODE — система, BL/KERNEL/HOME — ядро с рамдиском; модем и CSC не нужны
                    && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                    zip.getInputStream(e).use { s ->
                        if (base.endsWith("gz", true)) importCompressed(GZIPInputStream(s, 1 shl 16), base, depth + 1)
                        else importTarStream(s, base, depth + 1)
                    }
                }
                // Block OTA data/list files are useless alone; they are paired after the loop.
                base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) ||
                    base.endsWith(".transfer.list", true) -> {}
                base.endsWith(".app", true) && e.size > 0 -> importZipHuaweiApp(zip, e, base)
                // Acer packages are recognised by their table, not by the (generic) .bin / .nb0 extension
                (base.endsWith(".nb0", true) || base.endsWith(".bin", true)) && e.size > AcerBin.MIN_SIZE &&
                    AcerBin.probe(zipEntryHead(zip, e, 1100)) -> importZipAcerBin(zip, e, base)
                recognizedFirmwareTool(base) -> {
                    val nestedFile = spillEntry(zip, e)
                    try {
                        FileChannel.open(nestedFile.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, nestedFile.name, depth + 1)
                        }
                    } finally { nestedFile.delete() }
                }
            }
            done += maxOf(0L, e.size)
            onProgress("Extracting: $base", 0.9f * done / total)
        }
        for ((_, be) in bootEntries.sortedBy { it.first.count { c -> c == '/' } }) {
            if (ramdisk != null) break
            zip.getInputStream(be).use { takeBoot(it.readBytes()) }
        }
        // Split boot dumps (kernel + ramdisk.gz, or an unpacked initrd/ folder) carry no boot.img: take the ramdisk directly,
        // otherwise init.rc, /sbin/healthd and friends never reach the tree and system_server dies in BatteryService.
        if (ramdisk == null && !systemAsRoot) takeLooseRamdisk(zip, entries, wrap, sysRoot)
        for (partition in listOf("system", "vendor")) {
            if (partition == "system" && gotSystem) continue
            val dataE = entries.firstOrNull { it.name.matches(Regex("(?i)(.*/)?$partition\\.new\\.dat(\\.br)?")) } ?: continue
            val dir = dataE.name.substringBeforeLast('/', "")
            val listE = entries.firstOrNull {
                it.name.substringBeforeLast('/', "") == dir &&
                    it.name.substringAfterLast('/').equals("$partition.transfer.list", true)
            }
            if (listE == null) {
                if (partition == "system") throw IOException("$partition.transfer.list missing")
                log("vendor.transfer.list missing, skipping vendor.new.dat"); continue
            }
            val list = zip.getInputStream(listE).use { String(it.readBytes(), Charsets.UTF_8) }
            onProgress("Building $partition.img from OTA", 0.5f)
            val raw = File(tmp, "$partition.raw")
            try {
                zip.getInputStream(dataE).use { s ->
                    val data = if (dataE.name.endsWith(".br", true)) BrotliInputStream(BufferedInputStream(s, 1 shl 20)) else s
                    TransferList.build(list, data, raw)
                }
                keepStockImage = partition == "system"
                FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                    if (partition == "vendor") runCatching { importImage(ChannelSource(c), partition) }
                    else importImage(ChannelSource(c), partition)
                }
            } finally { keepStockImage = false; raw.delete() }
        }
        if (sparseChunks.isNotEmpty() && !gotSystem) {
            val parts = sparseChunks.map { spillEntry(zip, it) }
            val chans = parts.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
            try {
                val srcs = chans.map { ChannelSource(it) }
                // names like system.0 / system_1 are ambiguous: only a real sparse set is used
                if (srcs.all { SparseSource.probe(it) }) {
                    log("sparse system in ${srcs.size} part(s): ${sparseChunks.joinToString { it.name.substringAfterLast('/') }}")
                    importImage(SparseSource(srcs), "system")
                } else log("ignoring ${sparseChunks.first().name}: not a sparse image set")
            } finally { chans.forEach { it.close() }; parts.forEach { it.delete() } }
        }
        if (rawProgramEntries.isNotEmpty() && (!gotSystem || ramdisk == null || recovery == null || !gotOem)) importEdl(zip, entries, rawProgramEntries)
        // zips at any depth (Firmware/fw.zip): opened only if the outer archive had no system / boot
        for (pe in pendingZips) {
            if (cancelled) throw IOException("cancelled")
            if (gotSystem && ramdisk != null) break
            val nested = spillEntry(zip, pe)
            if (!gotSystem) handleNestedZip(nested, pe.name.substringAfterLast('/'), depth)
            else try { bootFromArchive(nested, pe.name) } finally { nested.delete() }
        }
        if (!gotSystem) log("no system found in archive $name")
    }

    /**
     * Qualcomm EDL package (rawprogram*.xml + split images). Of all rawprogram XMLs the one that
     * describes the partition and whose chunk files are really in the archive is chosen, only the
     * chunks it names are copied out, merged into one raw image (offsets relative to the lowest
     * start sector, sparse chunks expanded) and imported. Same for boot when none was found.
     */
    private fun importEdl(zip: ZipFile, entries: List<ZipArchiveEntry>, xmlEntries: List<ZipArchiveEntry>) {
        val edlDir = File(tmp, "edl-${System.nanoTime()}").apply { mkdirs() }
        try {
            val xmls = xmlEntries.map { spillEntry(zip, it) }
            val byBase = HashMap<String, MutableList<ZipArchiveEntry>>()
            for (x in entries) if (!x.isDirectory && !x.name.contains("__MACOSX/"))
                byBase.getOrPut(x.name.replace('\\', '/').substringAfterLast('/').lowercase()) { ArrayList() }.add(x)
            for (label in listOf("system", "boot", "recovery", "oem")) {
                if (cancelled) throw IOException("cancelled")
                if (label == "system" && gotSystem) continue
                if (label == "boot" && ramdisk != null) continue
                if (label == "recovery" && recovery != null) continue
                if (label == "oem" && gotOem) continue
                val chunks = File(edlDir, label).apply { mkdirs() }
                val raw = File(tmp, "edl-$label.raw.img")
                try {
                    val plan = FirmwareToolset.planEdl(xmls, byBase.keys, label) { msg -> log(msg) } ?: continue
                    if (plan.missing > 0) log("EDL: ${plan.missing} $label chunk(s) missing from archive")
                    val xmlDir = xmlEntries.getOrNull(xmls.indexOf(plan.xml))?.name?.replace('\\', '/')?.substringBeforeLast('/', "") ?: ""
                    for (file in plan.files()) {
                        val base = file.replace('\\', '/').substringAfterLast('/')
                        val cands = byBase[base.lowercase()] ?: continue
                        // prefer the copy next to the XML, otherwise the closest one
                        val pick = cands.firstOrNull { it.name.replace('\\', '/').substringBeforeLast('/', "") == xmlDir }
                            ?: cands.minByOrNull { it.name.length }!!
                        onProgress("EDL: $base", -1f)
                        zip.getInputStream(pick).use { i -> File(chunks, base).outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    }
                    FirmwareToolset.combineEdl(plan, chunks, raw) { msg -> log(msg) }
                    deleteTree(chunks)
                    FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (label == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (label == "oem") importOem(src)
                        else if (raw.length() <= 128L shl 20) {
                            if (label == "recovery") takeRecovery(raw.inputStream(), "recovery.img") else takeBoot(raw.readBytes())
                        }
                    }
                } catch (t: Throwable) {
                    if (cancelled) throw t
                    log("EDL $label combine skipped: ${t.message ?: t.javaClass.simpleName}")
                } finally { deleteTree(chunks); raw.delete() }
            }
            xmls.forEach { it.delete() }
        } finally { deleteTree(edlDir) }
    }

    /**
     * Commons Compress SevenZFile is a sequential reader: each entry must be consumed
     * through SevenZFile.read() before advancing. Do not create an independent stream
     * per entry; that breaks solid archives and versions of Commons Compress that do
     * not expose entry streams.
     */
    private fun importSevenZ(ch: FileChannel, name: String, depth: Int) {
        // handle() probed the header through the shared channel, which left it at that offset; SevenZFile reads
        // its signature from the current position, hence "Bad 7z signature" on a perfectly good archive
        ch.position(0)
        SevenZFile.builder().setSeekableByteChannel(ch).get().use { z ->
            val sysRoot = findSystemRoot(z.entries.map { it.name.replace('\\', '/').trimStart('/') })
            val nested = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                val base = n.substringAfterLast('/')
                val entryStream = object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        val count = read(one, 0, 1)
                        return if (count < 0) -1 else one[0].toInt() and 0xff
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (cancelled) throw IOException("cancelled")
                        return z.read(b, off, len)
                    }
                }
                when {
                    n.contains("__MACOSX/") || base.startsWith("._") -> drain(entryStream)
                    systemRel(sysRoot, n) != null -> {
                        writeFile(systemRel(sysRoot, n)!!, entryStream, null)
                        gotSystem = true
                    }
                    base.endsWith(".yaffs2.img", true) && !base.equals("system.yaffs2.img", true) -> drain(entryStream)
                    isBootName(base) -> takeBoot(entryStream.readBytes())
                    isRecoveryName(base) && e.size < 64_000_000 -> takeRecovery(entryStream, base)
                    isOemName(base) -> nested.add(spill(entryStream, base) to "oem")
                    isVendorName(base) -> nested.add(spill(entryStream, base) to "vendor")
                    base.equals("system.yaffs2.img", true) ||
                        isSystemImageName(base) -> {
                        nested.add(spill(entryStream, base) to "system")
                    }
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz)")) &&
                        e.size > 0 && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                        nested.add(spill(entryStream, base) to "archive")
                    }
                    base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true) -> {
                        val nestedFile = spillPreservingName(entryStream, base)
                        val key = base.lowercase()
                            .removeSuffix(".br")
                            .removeSuffix(".new.dat")
                            .removeSuffix(".transfer.list")
                        val slot = vdat.getOrPut(key) { linkedMapOf() }
                        when {
                            base.endsWith(".transfer.list", true) -> slot["list"] = nestedFile
                            else -> slot["data"] = nestedFile
                        }
                    }
                    recognizedFirmwareTool(base) -> {
                        val nestedFile = spill(entryStream, base)
                        nested.add(nestedFile to "tool")
                    }
                    else -> if (e.size < (4L shl 20) || !importGptStream(entryStream, base)) drain(entryStream)
                }
                onProgress("Extracting: $base", 0.5f)
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try {
                        importVdatPair(data, list, key, depth + 1)
                    } finally { data.delete(); list.delete() }
                } else {
                    pair.values.forEach { it.delete() }
                    log("incomplete VDAT pair in 7z archive: $key")
                }
            }
            for ((f, kind) in nested) {
                try {
                    FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else if (kind == "vendor") importVendor(src)
                        else handle(src, c, f.name, depth + 1)
                    }
                } finally { f.delete() }
            }
            if (!gotSystem && nested.isEmpty()) log("no system found in archive $name")
        }
    }

    /**
     * Android Things /gapps (Google Mobile Services, mounted nofail): /system/priv-app/PrebuiltGmsCoreThings and friends
     * are symlinks into it. Optional like /oem: a failure is logged and the partition skipped.
     */
    private fun importGapps(src: RandomSource) {
        val dir = File(root, "gapps")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "gapps")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("gapps: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/gapps/") }
            if (!existed) deleteTree(dir)
        }
    }

    private fun applyGptPart(kind: GptDisk.Kind, src: RandomSource) {
        when (kind) {
            GptDisk.Kind.BOOT -> takeBoot(readAllFrom(src))
            GptDisk.Kind.RECOVERY -> takeRecovery(streamOf(src), "recovery.img")
            GptDisk.Kind.OEM -> importOem(src)
            GptDisk.Kind.GAPPS -> importGapps(src)
            GptDisk.Kind.SYSTEM -> {
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                gotSystem = true
            }
        }
    }

    /** Random-access full dump (plain .img, nested file): partitions are used in place, nothing is copied. */
    private fun importGptDisk(src: RandomSource, name: String) {
        val first = ByteBuffer.allocate(8192)
        src.read(0, first)
        val fb = first.array()
        val need = GptDisk.headSize(fb, fb.size) ?: throw IOException("$name: unreadable GPT")
        val head = ByteBuffer.allocate(need).also { src.read(0, it) }.array()
        val picks = GptDisk.pick(GptDisk.parse(head) ?: throw IOException("$name: unreadable GPT"))
        if (picks.isEmpty()) throw IOException("$name: no boot/recovery/system partition in GPT")
        log("$name: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        for ((kind, p) in picks) {
            if (cancelled) throw IOException("cancelled")
            applyGptPart(kind, SliceSource(src, p.start, minOf(p.size, src.size - p.start)))
        }
    }

    /**
     * Sequential full dump (a 7z entry cannot be seeked): only the wanted partitions are written to temp files
     * and decompression stops after the last one, so a 4 GB dump is neither stored nor unpacked completely.
     * Returns false (after consuming only the first bytes) if the stream is not a GPT disk.
     */
    private fun importGptStream(input: InputStream, label: String): Boolean {
        val first = ByteArray(8192)
        val n = GptDisk.readFully(input, first, 0, first.size)
        val need = GptDisk.headSize(first, n) ?: return false
        var head = first.copyOf(n)
        if (need > n) {
            val big = first.copyOf(need)
            val got = GptDisk.readFully(input, big, n, need - n)
            if (n + got < need) return false
            head = big
        }
        val picks = GptDisk.pick(GptDisk.parse(head) ?: return false)
        if (picks.isEmpty()) { log("$label: GPT disk without boot/recovery/system partitions"); return true }
        log("$label: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        val dir = File(tmp, "gpt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val outs = picks.map { (k, p) -> Triple(k, p, File(dir, "${k.name.lowercase()}.img")) }
            val streams = outs.map { it.third.outputStream().buffered(1 shl 20) }
            try {
                val end = picks.maxOf { it.second.start + it.second.size }
                var pos = 0L
                fun feed(buf: ByteArray, len: Int) {
                    for ((i, o) in outs.withIndex()) {
                        val p = o.second
                        val a = maxOf(p.start, pos)
                        val b = minOf(p.start + p.size, pos + len)
                        if (b > a) streams[i].write(buf, (a - pos).toInt(), (b - a).toInt())
                    }
                    pos += len
                }
                feed(head, head.size)
                val buf = ByteArray(1 shl 20)
                var tick = 0
                while (pos < end) {
                    if (cancelled) throw IOException("cancelled")
                    if (tmp.usableSpace < (64L shl 20)) throw IOException("Not enough space to unpack firmware")
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                    if (r < 0) break
                    if (r > 0) feed(buf, r)
                    if (++tick % 16 == 0) onProgress("Reading $label", (pos.toFloat() / end).coerceIn(0f, 1f) * 0.5f)
                }
            } finally { streams.forEach { runCatching { it.close() } } }
            for ((kind, _, f) in outs) {
                if (cancelled) throw IOException("cancelled")
                FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> applyGptPart(kind, ChannelSource(c)) }
                f.delete()
            }
        } finally { deleteTree(dir) }
        return true
    }

    private fun importVdatPair(data: File, list: File, key: String, depth: Int) {
        val part = key.substringAfterLast('/').lowercase()
        val mount = when { part.isBlank() || part == "system" -> "system"; part == "vendor" -> "vendor"; else -> {
            log("skipping OTA partition \"$part\" (not system/vendor)"); return } }
        val raw = File(tmp, "${if (key.isBlank()) "system" else key}.img")
        val actualData = if (data.name.endsWith(".br", true)) {
            val dec = File(tmp, "${if (key.isBlank()) "system" else key}.new.dat")
            BrotliInputStream(BufferedInputStream(FileInputStream(data), 1 shl 20)).use { input ->
                dec.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            dec
        } else data
        try {
            val transfer = list.readText(Charsets.UTF_8)
            FileInputStream(actualData).use { dataIn ->
                TransferList.build(transfer, dataIn, raw)
            }
            keepStockImage = mount == "system"
            FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), mount) }
        } finally {
            keepStockImage = false
            if (actualData !== data) actualData.delete()
            raw.delete()
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) if (cancelled) throw IOException("cancelled")
    }

    /** RAR support is used for RAR4/RAR5 firmware bundles; entries are streamed to temp files. */
    private fun importRar(file: File, name: String, depth: Int) {
        if (depth > 8) throw IOException("nested archive depth exceeded")
        RarArchive(file).use { rar ->
            val sysRoot = findSystemRoot(rar.fileHeaders.map { (it.fileName ?: "").replace('\\', '/').trimStart('/') })
            val pending = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            for (e: RarFileHeader in rar.fileHeaders) {
                if (cancelled) throw IOException("cancelled")
                if (e.isDirectory) continue
                val entryName = (e.fileName ?: "").replace('\\', '/').trimStart('/')
                if (entryName.isBlank() || entryName.split('/').any { it == ".." }) continue
                val base = entryName.substringAfterLast('/')
                val sysRel = systemRel(sysRoot, entryName)
                val interesting = sysRel != null ||
                    isBootName(base) || isRecoveryName(base) || isOemName(base) ||
                    base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base) ||
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz|new\\.dat|transfer\\.list)")) ||
                    recognizedFirmwareTool(base)
                if (!interesting) continue
                val dir = File(tmp, "rar-${System.nanoTime()}").apply { mkdirs() }
                val temp = File(dir, base).apply { parentFile?.mkdirs() }
                try {
                    temp.outputStream().buffered(1 shl 20).use { out -> rar.extractFile(e, out) }
                } catch (t: com.github.junrar.exception.RarException) {
                    temp.delete()
                    pending.forEach { it.first.delete() }
                    vdat.values.forEach { slot -> slot.values.forEach { it.delete() } }
                    throw IOException("RAR entry '$entryName' failed to unpack (${t.javaClass.simpleName}); " +
                        "the archive may be damaged or the RAR decoder (junrar) is too old", t)
                }
                if (sysRel != null) {
                    FileInputStream(temp).use { input -> writeFile(sysRel, input, null) }
                    gotSystem = true
                    temp.delete()
                } else if (isBootName(base)) {
                    takeBoot(temp.readBytes()); temp.delete()
                } else if (isRecoveryName(base) && temp.length() < 64_000_000) {
                    temp.inputStream().use { takeRecovery(it, base) }; temp.delete()
                } else if (isOemName(base)) {
                    pending.add(temp to "oem")
                } else if (base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base)) {
                    pending.add(temp to "system")
                } else if (base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true)) {
                    val key = base.lowercase().removeSuffix(".br").removeSuffix(".new.dat").removeSuffix(".transfer.list")
                    val slot = vdat.getOrPut(key) { linkedMapOf() }
                    when {
                        base.endsWith(".transfer.list", true) -> slot["list"] = temp
                        else -> slot["data"] = temp
                    }
                } else pending.add(temp to "archive")
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try { importVdatPair(data, list, key, depth + 1) }
                    finally { data.delete(); list.delete() }
                } else { pair.values.forEach { it.delete() }; log("incomplete VDAT pair in RAR archive: $key") }
            }
            for ((temp, kind) in pending) {
                try {
                    FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else handle(src, c, temp.name, depth + 1)
                    }
                } finally { temp.delete() }
            }
            if (!gotSystem && pending.isEmpty()) log("no system found in RAR archive $name")
        }
    }

    /** Like [withEntrySource], but never deferred: a .sin must be unpacked before the importer knows what it holds. */
    private fun withSinSource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() }
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) } } finally { deleteTree(t.parentFile ?: t) }
    }

    /**
     * Sony .sin (see [SonySin]): unpacked to the plain partition image, which then goes the usual way by partition:
     * system -> guest /system, kernel/boot -> ramdisk (ELF or "ANDROID!"), fotakernel/recovery -> recovery, oem, vendor.
     * Everything else in an .ftf (userdata, cache, modem, loader, TA units ...) is skipped.
     */
    private fun importSin(src: RandomSource, name: String) {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val part = SonySin.partition(base)
        val kind = SonySin.kind(part)
        if (kind == SonySin.Kind.SKIP) { log("FTF: skipped $base ($part)"); return }
        if (kind == SonySin.Kind.SYSTEM && gotSystem) { log("FTF: skipped $base, system already imported"); return }
        onProgress("Unpacking Sony image $part", -1f)
        val work = File(tmp, "sin-${System.nanoTime()}").apply { mkdirs() }
        try {
            val images = SonySin.unpack(src, work, base, { log(it) }) { cancelled }
            log("FTF: $base -> $part, ${images.sumOf { it.length() } shr 20} MB")
            when (kind) {
                SonySin.Kind.BOOT -> takeBoot(images.first().readBytes())
                SonySin.Kind.RECOVERY -> images.first().inputStream().use { takeRecovery(it, "$part.img") }
                else -> {
                    val chans = images.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    try {
                        val srcs = chans.map { ChannelSource(it) }
                        val img = when {
                            srcs.all { SparseSource.probe(it) } -> SparseSource(srcs)
                            srcs.size == 1 -> srcs[0]
                            else -> throw IOException("$base: ${srcs.size} image parts that are not a sparse set")
                        }
                        when (kind) {
                            SonySin.Kind.OEM -> importOem(img)
                            SonySin.Kind.VENDOR -> importVendor(img)
                            else -> importImage(img, "system")
                        }
                    } finally { chans.forEach { it.close() } }
                }
            }
        } finally { deleteTree(work) }
    }

    private fun withEntrySource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        // несжатую запись читаем прямо из архива по смещению, сжатую — через временный файл
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // вычисляет dataOffset
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        deferred?.let { d -> d.add(t to e.name); return }
        FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        t.delete()
    }

    /** a nested zip: its images are copied out first, the zip deleted, then the images unpacked */
    private fun handleNestedZip(t: File, base: String, depth: Int) {
        val outer = deferred
        val mine = ArrayList<Pair<File, String>>()
        deferred = mine
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, base, depth + 1) }
        } finally { deferred = outer; t.delete() }
        try {
            for ((f, entry) in mine) {
                val entryBase = entry.substringAfterLast('/')
                val part = when { isOemName(entryBase) -> "oem"; entryBase.lowercase().startsWith("vendor") -> "vendor"; else -> "system" }
                FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    val img = if (SparseSource.probe(src)) SparseSource(listOf(src)) else src
                    if (part == "oem") importOem(img)
                    else if (part == "vendor") runCatching { importImage(img, part) } else importImage(img, part)
                }
                f.delete()
            }
        } finally { mine.forEach { it.first.delete() } }
    }

    private fun spillEntry(zip: ZipFile, e: ZipArchiveEntry): File = zip.getInputStream(e).use { spill(it, e.name.substringAfterLast('/')) }

    /**
     * Copies a stream into a temp file that keeps its ORIGINAL name. Uniqueness comes from a
     * private directory instead of a "<nanoTime>-" name prefix: the prefix leaked into
     * "<random>-<random>-system.new.dat" names and made sibling lookups
     * (system.new.dat <-> system.transfer.list) impossible.
     */
    private fun spill(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_').ifEmpty { "entry" }
        val dir = File(tmp, "spill-${System.nanoTime()}").apply { mkdirs() }
        val t = File(dir, safe)
        try {
            t.outputStream().use { o ->
                val buffer = ByteArray(1 shl 20)
                var count = 0L
                while (true) {
                    if (cancelled) throw IOException("cancelled")
                    val n = i.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > 16L * 1024 * 1024 * 1024 || tmp.usableSpace < n + 32L * 1024 * 1024)
                        throw IOException("Not enough space to unpack firmware")
                    o.write(buffer, 0, n)
                }
            }
            return t
        } catch (toss: Throwable) { deleteTree(dir); throw toss }
    }

    /** CPIO (newc/crc/odc), used by recovery packages and Android TV firmware bundles. */
    private fun importCpioStream(input: InputStream, name: String, depth: Int) {
        if (depth > 8) throw IOException("nested archive depth exceeded")
        CpioArchiveInputStream(input, "UTF-8").use { cpio ->
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e: CpioArchiveEntry = cpio.nextEntry ?: break
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isBlank() || n == "TRAILER!!!" || n.split('/').any { it == ".." }) continue
                val base = n.substringAfterLast('/')
                when {
                    e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                    n.startsWith("system/") -> {
                        writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        gotSystem = true
                    }
                    isBootName(base) -> takeBoot(cpio.readBytes())
                    isRecoveryName(base) && e.size < 64_000_000 -> takeRecovery(cpio, base)
                    isOemName(base) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                    }
                    isSystemImageName(base) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                    n.matches(Regex("^(data|dev|sbin|vendor|oem|etc)(/.*)?$")) ||
                        n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" -> {
                        if (e.isSymbolicLink) {
                            // In cpio, a symlink target is stored as the entry payload;
                            // unlike tar entries there is no linkName field.
                            val target = cpio.readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                            if (target.isNotEmpty() && target.length <= 4096) {
                                symlinks.add(target to "/$n")
                            }
                        } else if (e.isRegularFile) {
                            writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        }
                    }
                }
            }
        }
    }

    private fun importTarStream(s: InputStream, name: String, depth: Int) {
        val tar = TarArchiveInputStream(s, "UTF-8")
        var fullRoot: Boolean? = null
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val e: TarArchiveEntry = tar.nextEntry ?: break
            var n = e.name.removePrefix("./").trimStart('/')
            if (n.isEmpty()) continue
            // Odin names members "system.img.md5", "boot.img.md5", ...: match on the real image name
            val base = PartitionNames.stripOdinMd5(n.substringAfterLast('/'))
            // дерево rootfs целиком (system/, data/, dev/, …)
            if (fullRoot == null && (n == "system" || n.startsWith("system/") || n.startsWith("init.rc") || n.startsWith("default.prop"))) fullRoot = true
            when {
                base.equals("system.yaffs2.img", true) && e.isFile -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { importImage(ChannelSource(it), "system") } }
                    finally { t.delete() }
                }
                base.endsWith(".yaffs2.img", true) -> {}
                isOemName(base.replace(Regex("(?i)\\.lz4$"), "")) && e.isFile && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                }
                isSystemImageName(base.replace(Regex("(?i)\\.lz4$"), "")) && e.isFile -> {
                    val t = spill(maybeLz4(tar, base), base)
                    FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                    }
                    t.delete()
                }
                base.matches(Regex("(?i)(boot\\.img|boot_signed(\\.img)?|zImage|kernel)(\\.lz4)?")) && e.isFile && !inSystemDump(n) -> takeBoot(maybeLz4(tar, base).readBytes())
                isRecoveryName(base) && e.isFile && e.size < 64_000_000 && !inSystemDump(n) -> takeRecovery(tar.nonClosing(), base)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5)")) && e.isFile && e.size > 20_000_000 -> importTarStream(tar.nonClosing(), base, depth + 1)
                // factory-образы Google: tgz → image-*.zip → system.img/boot.img; Samsung: zip внутри tar
                base.lowercase().endsWith(".zip") && e.isFile && e.size > 20_000_000 -> {
                    handleNestedZip(spill(tar.nonClosing(), base), base, depth)
                }
                n.matches(Regex("^(system|data|dev|sbin|vendor|oem|etc)(/.*)?$")) || n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" || n.startsWith("dhd.") -> {
                    // файлы корня (рамдиск, dhd.*) берём, только если архив — целое дерево rootfs
                    if (fullRoot != true && !n.startsWith("system") && !(n.startsWith("dhd.") || n.endsWith(".rc") || n == "default.prop" || n.startsWith("sbin"))) continue
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$n")
                        e.isLink -> hardlink(n, e.linkName)
                        e.isFile -> {
                            writeFile(n, tar.nonClosing(), e.mode)
                            if (n.startsWith("system/")) gotSystem = true
                        }
                    }
                }
                // TWRP oem.ext4.win (tar backup of the files): paths without the oem/ prefix
                name.contains("oem", true) && name.endsWith(".win", true) && !name.contains("system", true) -> {
                    val p = "oem/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> writeFile(p, tar.nonClosing(), e.mode)
                    }
                }
                // TWRP system.ext4.win: пути без префикса system/
                name.contains("system", true) && name.endsWith(".win", true) -> {
                    val p = "system/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); gotSystem = true }
                    }
                }
            }
            if (files % 200 == 0) onProgress("Extracting: $base", -1f)
        }
    }

    private fun InputStream.nonClosing(): InputStream = object : java.io.FilterInputStream(this) { override fun close() {} }

    private fun maybeLz4(s: InputStream, name: String): InputStream =
        if (name.endsWith(".lz4", true)) org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(s.nonClosing()) else s.nonClosing()

    private fun hardlink(n: String, target: String) {
        val src = FirmwareContainers.destination(root, target.removePrefix("./").trimStart('/'))
        val dst = FirmwareContainers.destination(root, n)
        if (src.isFile) { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
    }

    // ------------------------------------------------------------------ образы ФС

    /**
     * Set while the system partition image built from a block OTA (system.new.dat + transfer list) is imported: a SquashFS
     * one cannot be rebuilt from its files later, so it is kept for delta OTAs ([StockImage]).
     */
    private var keepStockImage = false

    private fun importImage(source: RandomSource, mount: String) {
        // Motorola signed images carry a signature header in front of the ext4 (see MotoImage)
        val src = MotoImage.unwrap(source)
        if (src !== source) log("image $mount: Motorola signature header skipped, ${src.size shr 20} MB filesystem")
        if (UbiFsReader.probe(src)) { importUbifs(src, mount); return }
        if (Yaffs2Reader.probe(src)) { importYaffs2(src, mount); return }
        if (!Ext4Reader.probe(src)) { importOtherFilesystem(src, mount, keep = keepStockImage && mount == "system" && src === source); return }
        val fs = Ext4Reader(src)
        // Android 7+ A/B ("system-as-root"): the partition IS the root file system, /system is a folder in it.
        // Its top level (init*.rc, sepolicy, sbin/, default.prop...) is the ramdisk, so it goes to the guest root as is.
        val asRoot = mount == "system" && AbLayout.isSystemAsRoot(fs)
        if (asRoot) { systemAsRoot = true; log("image system: system-as-root layout (root file system in the partition)") }
        onProgress("Reading image $mount (ext4, block ${fs.blockSize})", -1f)
        var n = 0
        // what is needed to rebuild this image later from the files (block-based OTAs work on the image)
        val cap = if (mount == "system" && !asRoot) SystemLayout.Capture(fs, src) else null
        fs.walk { path, node ->
            if (cancelled) throw IOException("cancelled")
            if (asRoot && node.isFile && AbLayout.skipRootFile(path)) return@walk
            val rel = if (asRoot) path else "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when {
                node.isDir -> { f.mkdirs(); cap?.dir(path, node.perm) }
                node.isLink -> { val t = fs.linkTarget(node); symlinks.add(t to "/$rel"); cap?.link(path, t) }
                node.isFile -> {
                    f.parentFile?.mkdirs()
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, java.security.DigestOutputStream(it, md)) }
                    cap?.file(path, node, md.digest())
                    applyMode(f, node.perm)
                    files++; bytes += node.size
                }
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (cap != null) {
            onProgress("Recording the layout of image $mount", -1f)
            val lf = SystemLayout.file(paths.dir)
            runCatching { cap.finish(lf) }.onFailure {
                lf.delete()
                log("image $mount: layout not kept (${it.message}); block-based OTAs cannot be applied to this VM")
            }
        }
        if (mount == "system") gotSystem = true
        log("image $mount: $n objects")
    }

    /**
     * Image is neither YAFFS2 nor ext4: identify SquashFS / RFS (FAT) through the firmware toolset,
     * convert it to a temporary zip and unpack that into the guest root. Anything else fails with
     * a message naming the supported formats instead of an opaque ext4 magic error.
     */
    private fun importOtherFilesystem(src: RandomSource, mount: String, keep: Boolean = false) {
        val headFile = File(tmp, "probe-${System.nanoTime()}.bin")
        val kind = try {
            val head = ByteBuffer.allocate(minOf((8L shl 20) + 512, src.size).toInt()); src.read(0, head)
            headFile.writeBytes(head.array())
            FirmwareToolset.detect(headFile)
        } finally { headFile.delete() }
        if (kind != "SQUASHFS" && kind != "RFS")
            throw IOException("unrecognized filesystem for $mount (expected ext4, YAFFS2, UBIFS, SquashFS or RFS)")
        val fsName = if (kind == "SQUASHFS") "SquashFS" else "RFS"
        onProgress("Reading image $mount ($fsName)", -1f)
        val dir = File(tmp, "fs-${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = spill(streamOf(src), "$mount.img").let { File(dir, "$mount.img").also { d -> it.copyTo(d, true); it.delete() } }
            val artifacts = FirmwareToolset.extract(image, File(dir, "out")) { msg -> log(msg) }
            val zipFile = artifacts.firstOrNull { it.file.isFile }?.file
                ?: throw IOException("$fsName image produced no files")
            if (keep && kind == "SQUASHFS") {
                // the partition of a block OTA: delta OTAs run on this image, see StockImage
                val kept = StockImage.file(paths.dir)
                runCatching { image.copyTo(kept, overwrite = true) }.onFailure {
                    kept.delete()
                    log("image $mount: stock image not kept (${it.message}); block-based OTAs cannot be applied to this VM")
                }
            }
            image.delete()
            var n = 0
            ZipFile.builder().setFile(zipFile).get().use { zip ->
                for (e in zip.entries) {
                    if (cancelled) throw IOException("cancelled")
                    // the toolset wraps everything in one top folder named after the image: drop it
                    val inner = e.name.replace('\\', '/').substringAfter('/', "").trimEnd('/')
                    if (inner.isEmpty() || inner.split('/').any { it == ".." }) continue
                    val rel = "$mount/$inner"
                    val f = FirmwareContainers.destination(root, rel)
                    when {
                        e.isDirectory -> f.mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> {
                            zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                        }
                    }
                    if (++n % 150 == 0) onProgress("$mount: ${inner.substringAfterLast('/')}", -1f)
                }
            }
            if (mount == "system") gotSystem = true
            log("$fsName $mount: $n objects")
        } finally { deleteTree(dir) }
    }

    private fun importYaffs2(src: RandomSource, mount: String) {
        onProgress("Reading image $mount (YAFFS2)", -1f)
        val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create $rel") }
                2 -> {
                    val target = if (node.alias.startsWith("/")) node.alias.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.alias
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.alias.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw IOException("YAFFS2 symlink escapes guest root")
                    symlinks.add(node.alias to "/$rel")
                }
                1, 4 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw IOException("YAFFS2 destination is a symlink")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                5 -> {} // Guest /dev is created by the VM; never mknod on the host.
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        val geo = fs.geometry
        log("YAFFS2 $mount: $n objects, page ${geo.pageSize}+${geo.spareSize}, " +
            (if (geo.sequential) "spare-less (sequential), " else "tags at spare+${geo.tagOffset}, start offset ${geo.base}, ") +
            (if (geo.order == java.nio.ByteOrder.LITTLE_ENDIAN) "LE" else "BE"))
        fs.warnings.forEach { log("YAFFS2 $mount: $it") }
    }

    /**
     * UBI image with a UBIFS volume (NAND devices, e.g. MediaTek MT65xx/MT657x phones): the same tree walk as YAFFS2.
     * See [UbiFsReader] for the supported compression types (none, LZO, zlib, zstd and the MediaTek LZ4K).
     */
    private fun importUbifs(src: RandomSource, mount: String) {
        onProgress("Reading image $mount (UBIFS)", -1f)
        val fs = UbiFsReader(src) { if (cancelled) throw IOException("cancelled") }
        log("UBIFS $mount: ${fs.summary}")
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create $rel") }
                2 -> {
                    val target = if (node.target.startsWith("/")) node.target.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.target
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.target.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw IOException("UBIFS symlink escapes guest root")
                    symlinks.add(node.target to "/$rel")
                }
                1 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw IOException("UBIFS destination is a symlink")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                else -> {} // Guest /dev is created by the VM; never mknod on the host.
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        log("UBIFS $mount: $n objects")
        fs.warnings.forEach { log("UBIFS $mount: $it") }
    }

    // "ramdisk.img" is how Google's SDK system images (clockwork_sdk, sysimg_*) ship the root fs: a gzip'd cpio next to system.img
    private val LOOSE_RAMDISK = Regex("(?i)(ramdisk|initrd|initramfs)(\\.cpio)?(\\.(gz|xz|lzma|lz4|img))?")

    /** Ramdisk shipped without a boot.img: a packed ramdisk.gz (cpio) or an unpacked initrd/ folder. */
    private fun takeLooseRamdisk(zip: ZipFile, entries: List<ZipArchiveEntry>, wrap: String, sysRoot: String?) {
        fun rel(e: ZipArchiveEntry) = e.name.replace('\\', '/').removePrefix(wrap)
        val usable = entries.filter { e ->
            val n = rel(e)
            !e.isDirectory && !n.contains("__MACOSX/") && systemRel(sysRoot, n) == null
        }
        // 1. packed ramdisk (keeps modes and symlinks)
        for (e in usable.filter { LOOSE_RAMDISK.matches(rel(it).substringAfterLast('/')) && it.size in 1024..MAX_RAMDISK }
            .sortedBy { rel(it).count { c -> c == '/' } }) {
            val rd = runCatching {
                val raw = zip.getInputStream(e).use { it.readBytes() }
                BootImage.decompress(raw)?.let { BootImage.cpio(it) }
            }.getOrNull()
            if (!rd.isNullOrEmpty() && rd.any { it.name == "init.rc" }) {
                ramdisk = rd
                log("boot: loose ramdisk ${rel(e)}, ${rd.size} files")
                return
            }
        }
        // 2. unpacked ramdisk folder (.../initrd/init.rc, .../initrd/sbin/healthd)
        val rc = usable.firstOrNull { rel(it).substringAfterLast('/') == "init.rc" && rel(it).substringBeforeLast('/', "").let { d ->
            d.substringAfterLast('/').matches(Regex("(?i)(initrd|ramdisk|rootfs)")) } } ?: return
        val dir = rel(rc).substringBeforeLast('/') + "/"
        val out = ArrayList<BootImage.CpioEntry>()
        for (e in usable) {
            val n = rel(e)
            if (!n.startsWith(dir) || e.size > MAX_RAMDISK) continue
            val name = n.removePrefix(dir)
            if (name.isEmpty()) continue
            val data = zip.getInputStream(e).use { it.readBytes() }
            if (e.isUnixSymlink) { out.add(BootImage.CpioEntry(name, 0xA000 or 0x1ff, data)); continue }
            val perm = e.unixMode.and(0xfff).takeIf { it != 0 } ?: if (name.startsWith("sbin/") || name == "init" || name.endsWith(".sh")) 0x1ed else 0x1a4
            out.add(BootImage.CpioEntry(name, 0x8000 or perm, data))
        }
        if (out.any { it.name == "init.rc" }) {
            ramdisk = out
            log("boot: unpacked ramdisk folder ${dir.trimEnd('/')}, ${out.size} files")
        }
    }

    /** After all containers are read: the recovery-only boot ramdisk is the fallback ramdisk of a non-system-as-root firmware. */
    private fun useDeferredBoot() {
        val data = recoveryBoot ?: return
        recoveryBoot = null
        if (systemAsRoot || ramdisk != null) { log("boot: recovery ramdisk ignored (the root file system comes from the system partition)"); return }
        val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        ramdisk = rd
        File(paths.dir, "boot.img").writeBytes(data)
        log("boot: ramdisk, ${rd.size} files")
    }

    private fun takeBoot(raw: ByteArray) {
        if (ramdisk != null) return
        val data = BootImage.stripHtcSignature(raw)
        val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: squashRamdisk(data)
        if (rd.isNullOrEmpty()) { log("boot: ramdisk not recognized"); return }
        if (AbLayout.isRecoveryRamdisk(rd)) {
            // A/B boot = kernel + recovery ramdisk; the real init.rc / sbin/ are in the system partition (system-as-root)
            recoveryBoot = data
            log("boot: recovery ramdisk (A/B boot image), ${rd.size} files; kept aside")
            return
        }
        ramdisk = rd
        File(paths.dir, "boot.img").writeBytes(data)
        log("boot: ramdisk, ${rd.size} files")
    }

    /**
     * Google TV (Marvell Berlin, e.g. Hisense GX1200V) ships boot.img as a plain SquashFS root file system, not an
     * "ANDROID!" image: init*.rc, sbin/ and the glibc userland (lib/ld-linux.so.3, libc, libpthread, ...) that the
     * firmware's native binaries are linked against ("interpreter /lib/ld-linux.so.3"). Without it every guest
     * program dies at once with "Could not open '/lib/ld-linux.so.3'". Returns the same entry list finishTree takes
     * from a boot.img ramdisk, or null if [raw] is not a SquashFS root file system.
     */
    private fun squashRamdisk(raw: ByteArray): List<BootImage.CpioEntry>? {
        if (raw.size < 96) return null
        val magic = String(raw, 0, 4, Charsets.ISO_8859_1)
        if (magic != "hsqs" && magic != "sqsh") return null
        val tmp = File(paths.dir, "boot-rootfs.sqfs")
        return try {
            tmp.writeBytes(raw)
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            var curMode = 0
            var cur: java.io.ByteArrayOutputStream? = null
            FirmwareToolset.walkSquashFs(tmp, { log(it) }, object : FirmwareToolset.SquashVisitor {
                override fun dir(path: String, mode: Int) { out.add(BootImage.CpioEntry(path, 0x4000 or (mode and 0xfff), ByteArray(0))) }
                override fun link(path: String, target: String) { out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, target.toByteArray())) }
                override fun file(path: String, mode: Int, size: Long): java.io.OutputStream? {
                    if (size > MAX_RAMDISK_FILE) { log("boot rootfs: skipped $path (${size shr 20} MB)"); return null }
                    total += size
                    if (total > MAX_RAMDISK) throw IOException("boot rootfs too large")
                    curMode = mode
                    return java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(16)).also { cur = it }
                }
                override fun fileEnd(path: String) {
                    cur?.let { out.add(BootImage.CpioEntry(path, 0x8000 or (curMode and 0xfff), it.toByteArray())) }
                    cur = null
                }
            })
            if (out.none { it.name == "init.rc" }) { log("boot: SquashFS has no init.rc, not a root file system"); null }
            else { log("boot: SquashFS root file system, ${out.size} entries (glibc userland: ${out.count { it.name.startsWith("lib/") }} files in /lib)"); out }
        } catch (ex: Exception) {
            log("boot: SquashFS root file system: ${ex.message}, skipped"); null
        } finally { tmp.delete() }
    }

    // ------------------------------------------------------------------ файлы, ссылки, права

    private fun writeFile(rel: String, i: InputStream, mode: Int?) {
        val f = FirmwareContainers.destination(root, rel)
        f.parentFile?.mkdirs()
        if (runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)) f.delete()
        // Windows/Cygwin archives flatten symlinks into small files: "!<symlink>" + (BOM FF FE + UTF-16LE | UTF-8) target + NUL.
        val src = java.io.BufferedInputStream(i, 1 shl 16)
        src.mark(1024)
        val head = ByteArray(512)
        var hn = 0
        while (hn < head.size) { val r = src.read(head, hn, head.size - hn); if (r < 0) break; hn += r }
        src.reset()
        if (hn in 12 until head.size && String(head, 0, 10, Charsets.ISO_8859_1) == "!<symlink>") {
            val body = head.copyOfRange(10, hn)
            val target = (if (body.size >= 2 && body[0] == 0xff.toByte() && body[1] == 0xfe.toByte())
                String(body, 2, body.size - 2, Charsets.UTF_16LE) else String(body, Charsets.UTF_8)).trimEnd('\u0000', '\n', '\r').trim()
            if (target.isNotEmpty() && !target.contains('\u0000')) {
                symlinks.add(target to "/" + rel.trimStart('/'))
                return
            }
        }
        f.outputStream().buffered(1 shl 20).use { o ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val n = src.read(buffer)
                if (n < 0) break
                bytes += n
                if (bytes > 16L * 1024 * 1024 * 1024 || root.usableSpace < n + 32L * 1024 * 1024)
                    throw IOException("Not enough space to unpack firmware")
                o.write(buffer, 0, n)
            }
        }
        if (mode != null) applyMode(f, mode and 0xfff)
        files++
    }

    private fun applyMode(f: File, perm: Int) {
        if (perm and 0x49 != 0) f.setExecutable(true, false)
        f.setReadable(true, false)
    }

    private fun parseUpdaterScript(s: String) {
        val text = s.replace(Regex("#[^\n]*"), "")
        for (m in Regex("symlink\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val args = Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            if (args.size >= 2) for (link in args.drop(1)) symlinks.add(args[0] to link)
        }
        for (m in Regex("set_perm_recursive\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 5) a[3].toIntOrNull(8)?.let { perms.add(Triple(a[4], it, true)) }
        }
        for (m in Regex("set_perm\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 4) a[2].toIntOrNull(8)?.let { perms.add(Triple(a[3], it, false)) }
        }
        for (m in Regex("set_metadata(_recursive)?\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[2].split(',').map { it.trim().trim('"') }
            if (a.isEmpty()) continue
            val key = if (m.groupValues[1].isNotEmpty()) "fmode" else "mode"
            val i = a.indexOf(key)
            if (i > 0 && i + 1 < a.size) a[i + 1].toIntOrNull(8)?.let { perms.add(Triple(a[0], it, m.groupValues[1].isNotEmpty())) }
        }
        log("updater-script: ${symlinks.size} symlinks, ${perms.size} perms")
    }

    /** Ссылки (с переводом абсолютных целей в относительные), права, служебные ссылки корня. */
    private fun finishTree() {
        // рамдиск: init*.rc, default.prop, sbin/, file_contexts…
        ramdisk?.forEach { e ->
            val type = e.mode and 0xF000
            val n = e.name
            if (n.isEmpty() || n == "." || n.startsWith("system/") || n.startsWith("data/") || n.startsWith("dev/") || n.startsWith("proc") || n.startsWith("sys/")) return@forEach
            val f = FirmwareContainers.destination(root, n)
            when (type) {
                0x4000 -> f.mkdirs()
                0xA000 -> symlinks.add(String(e.data).trimEnd('\u0000') to "/$n")
                0x8000 -> {
                    if (n == "init" || n.startsWith("sbin/ueventd") || n.startsWith("sbin/adbd")) return@forEach
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    applyMode(f, e.mode and 0xfff)
                }
            }
        }
        var made = 0
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            val f = FirmwareContainers.destination(root, rel)
            FirmwareContainers.checkLink(rel, target)
            f.parentFile?.mkdirs()
            val t = relTarget(link, target)
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(t, f.absolutePath); made++
            }
        }
        for ((p, mode, rec) in perms) {
            val f = FirmwareContainers.destination(root, p.trimStart('/'))
            if (rec) f.walkTopDown().filter { it.isFile }.forEach { applyMode(it, mode) } else if (f.isFile) applyMode(f, mode)
        }
        // исполняемые — всё в bin/xbin/sbin
        for (d in listOf("system/bin", "system/xbin", "sbin", "vendor/bin", "system/vendor/bin")) {
            File(root, d).listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        }
        // zips made without symlink support leave "sh" containing the text "mksh" (see FlattenedLinks)
        made += app.aemu.core.FlattenedLinks.repair(root, log)
        standardLinks()
        fun rootLink(name: String, target: String) {
            val f = File(root, name)
            if (!f.exists() && !isLink(f) && File(root, target).exists()) runCatching { Os.symlink(target, f.absolutePath) }
        }
        rootLink("vendor", "system/vendor")
        rootLink("etc", "system/etc")
        rootLink("bin", "system/bin")
        runCatching { File(root, "system/etc/mtab").let { if (!it.exists() && !isLink(it)) Os.symlink("../../proc/mounts", it.absolutePath) } }
        log("symlinks created: $made")
    }

    private fun isLink(f: File) = runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)

    /** Абсолютную цель ссылки гостя делаем относительной — иначе ядро телефона уйдёт за пределы дерева. */
    /**
     * Архивы без updater-script и без ссылок в самом архиве (multirom, выгрузки system/ из TWRP) не
     * содержат sh → mksh и ссылок на апплеты toolbox — без них не стартует ни одна служба init.
     * Создаём их сами, если их нет: апплет берём, только если его имя есть в самом toolbox.
     */
    private fun standardLinks() {
        val bin = File(root, "system/bin")
        fun missing(n: String) = File(bin, n).let { !it.exists() && !isLink(it) }
        var made = 0
        if (missing("sh")) {
            val sh = listOf("mksh", "ash").firstOrNull { File(bin, it).isFile }
            if (sh != null && runCatching { Os.symlink(sh, File(bin, "sh").absolutePath) }.isSuccess) made++
        }
        val toolbox = File(bin, "toolbox")
        if (toolbox.isFile) {
            val text = runCatching { String(toolbox.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            for (a in TOOLBOX_APPLETS) {
                if (!missing(a)) continue
                if (!text.contains("\u0000$a\u0000")) continue
                if (runCatching { Os.symlink("toolbox", File(bin, a).absolutePath) }.isSuccess) made++
            }
        }
        if (made > 0) log("sh/toolbox symlinks created: $made (missing from archive)")
    }

    private fun relTarget(link: String, target: String): String {
        if (!target.startsWith("/")) return target
        val from = link.trimStart('/').split('/').dropLast(1)
        val to = target.trimStart('/').split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++
        val ups = List(from.size - i) { ".." }
        return (ups + to.drop(i)).joinToString("/").ifEmpty { "." }
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        runCatching { f.delete() }
    }

    /** Image names that always mean the system partition (RFS dumps call it factoryfs). */
    private fun isSystemImageName(base: String) = PartitionNames.isSystem(base)

    /**
     * Folder dump of the system partition at any depth ("system/…", "Firmware/system/…").
     * Returns the prefix in front of "system/" ("" or "Firmware/"), or null if there is no dump.
     * Below the top level a folder only counts if it holds typical system content, so that an
     * unrelated "system" folder is not mistaken for the partition. The shallowest dump wins.
     */
    private fun findSystemRoot(names: Collection<String>): String? {
        val markers = listOf("framework/", "app/", "bin/", "lib/", "etc/")
        val found = LinkedHashMap<String, Boolean>() // prefix -> has build.prop
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            var i = n.indexOf('/')
            var start = 0
            while (true) {
                if (n.regionMatches(start, "system/", 0, 7, ignoreCase = true)) {
                    val prefix = n.substring(0, start)
                    val rest = n.substring(start + 7)
                    if (start == 0) found.putIfAbsent("", false) // plain top-level system/ (legacy layout)
                    if (rest == "build.prop") found[prefix] = true
                    else if (markers.any { rest.startsWith(it) }) found.putIfAbsent(prefix, false)
                }
                if (i < 0) break
                start = i + 1
                i = n.indexOf('/', start)
            }
        }
        if (found.isEmpty()) return null
        return found.entries.sortedWith(compareBy<Map.Entry<String, Boolean>>({ it.key.count { c -> c == '/' } }, { !it.value })).first().key
    }

    /** "system/…" path for an entry under the dump rooted at [root], or null if it is outside it. */
    private fun systemRel(root: String?, n: String): String? {
        if (root == null || n.length < root.length + 7 || !n.startsWith(root)) return null
        if (!n.regionMatches(root.length, "system/", 0, 7, ignoreCase = true)) return null
        return "system/" + n.substring(root.length + 7)
    }

    /** A boot.img in a folder named system is part of that dump, not the boot partition. */
    private fun inSystemDump(n: String) = n.startsWith("system/") || n.contains("/system/")

    private fun bootFromArchive(f: File, name: String) {
        runCatching { FileInputStream(f).use { takeBoot(BootPartitionSource.read(it, name, tmp)) } }
    }

    companion object {
        private val SKIP_NESTED = Regex("(?i).*(gapps|supersu|magisk|busybox|xposed|twrp|modem|csc).*")
        /** Апплеты toolbox Android 2.2–6.0 (ссылка создаётся, только если апплет есть в бинарнике). */
        private val TOOLBOX_APPLETS = listOf(
            "cat", "chcon", "chmod", "chown", "clear", "cmp", "cp", "date", "dd", "df", "dmesg", "du", "getenforce",
            "getevent", "getprop", "getsebool", "grep", "hd", "id", "ifconfig", "iftop", "insmod", "ioctl", "ionice",
            "kill", "ln", "load_policy", "log", "ls", "lsmod", "lsof", "md5", "mkdir", "mkswap", "mount", "mv",
            "nandread", "netstat", "newfs_msdos", "nohup", "notify", "printenv", "ps", "readlink", "renice",
            "restorecon", "rm", "rmdir", "rmmod", "route", "runcon", "schedtop", "sendevent", "setenforce",
            "setprop", "setsebool", "sleep", "smd", "start", "stop", "swapoff", "swapon", "sync", "top", "touch",
            "umount", "uptime", "vmstat", "watchprops", "wipe", "chroot", "sh",
        ).filter { it != "sh" }
    }
}