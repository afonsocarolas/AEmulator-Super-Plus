/* AEmulator Super Plus, 2026-10-11: Sony FTF / SIN import. GPL-3.0; see LICENSE and NOTICE.md.
 * Layouts follow Androxyde Flashtool's org.flashtool.parsers.sin (GPL-3.0). */
package app.aemu.importer

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Sony Xperia `.sin` partition images, the members of a Flashtool `.ftf` bundle (a ZIP: system.sin, kernel.sin,
 * fotakernel.sin, userdata.sin, loader.sin, *.ta ...). Every version is unpacked to the plain partition image:
 *
 *  - v1 (X10 era, 2010) and v2 (2011-2013, Android 2.3-4.3): big-endian header, a table of hash blocks
 *    (offset, length, SHA-1/SHA-256) and a certificate; the blocks follow the header back to back and each lands at
 *    its offset in the image. NAND payloads (YAFFS2 with spare) stretch the offsets by the spare area.
 *  - v3 (2013-2016, Android 4.3-6.x): "SIN" magic, SHA-256 hash table, then optionally an "MMCF" map of
 *    ADDR (raw) / LZ4A (LZ4 block-compressed) records that place the data at file offsets (sparse partitions).
 *  - v4 (2016+, Android 7+): a tar (optionally gzipped) holding a .cms signature and the image itself.
 *
 * Old devices' kernel.sin / fotakernel.sin hold an ELF with the kernel and the ramdisk as segments, not an
 * "ANDROID!" image; [BootImage.ramdisk] reads those.
 */
internal object SonySin {
    enum class Kind { SYSTEM, BOOT, RECOVERY, OEM, VENDOR, SKIP }

    private const val MAX_HEADER = 64L shl 20
    private const val MAX_BLOCK = 256L shl 20
    private val V3_HASH_LEN = intArrayOf(0, 0, 32)

    /** v1-v3 are recognized by their header; v4 is a plain tar / gzip and only recognized together with the .sin name. */
    fun probe(h: ByteArray, size: Long): Boolean {
        if (h.size < 24) return false
        val bb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        return when (h[0].toInt()) {
            1, 2 -> {
                val headerLen = bb.getInt(2).toLong() and 0xffffffffL
                val hashLen = bb.getInt(11).toLong() and 0xffffffffL
                headerLen in 15..minOf(size, MAX_HEADER) && hashLen + 15 + 4 <= headerLen
            }
            3 -> String(h, 1, 3, Charsets.ISO_8859_1) == "SIN" &&
                (bb.getInt(4).toLong() and 0xffffffffL) in 24..minOf(size, MAX_HEADER)
            else -> false
        }
    }

    /** Partition behind a Sony file name: "system_S1-SW-LIVE-2B54-PID1-0001-MMC.sin" -> "system" (Flashtool's getShortName). */
    fun partition(fileName: String): String {
        var n = fileName.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
        for (cut in listOf("_AID", "_PLATFORM", "_S1", "_X-", "_X_BOOT", "_X_Boot")) {
            val i = n.indexOf(cut)
            if (i > 0) n = n.substring(0, i)
        }
        return n.lowercase()
    }

    fun kind(partition: String): Kind = when (partition) {
        "system", "system_a" -> Kind.SYSTEM
        "kernel", "boot", "boot_a" -> Kind.BOOT
        "fotakernel", "recovery" -> Kind.RECOVERY
        "oem", "oem_a" -> Kind.OEM
        "vendor", "vendor_a" -> Kind.VENDOR
        // userdata, cache, apps_log, modem, loader, partition, rpm, sbl1, tz, elabel, *.ta ...: not part of the guest
        else -> Kind.SKIP
    }

    /**
     * Unpacks [src] (the whole .sin) into [dir] and returns the partition image file(s); several files only for a v4
     * whose tar splits the image into parts (each an Android sparse image, combined with [SparseSource]).
     */
    fun unpack(src: RandomSource, dir: File, label: String, log: (String) -> Unit, cancelled: () -> Boolean = { false }): List<File> {
        val h = read(src, 0, minOf(src.size, 1100L).toInt())
        val out = File(dir, partition(label) + ".img")
        when {
            h.isNotEmpty() && (h[0].toInt() == 1 || h[0].toInt() == 2) && probe(h, src.size) -> { unpackV12(src, out, log, cancelled); return listOf(out) }
            h.isNotEmpty() && h[0].toInt() == 3 && probe(h, src.size) -> { unpackV3(src, out, log, cancelled); return listOf(out) }
        }
        return unpackV4(src, dir, label, log, cancelled)
    }

    // ------------------------------------------------------------------ v1 / v2

    private class HashBlock(var offset: Long, val length: Long, val hash: ByteArray)

    private fun unpackV12(src: RandomSource, out: File, log: (String) -> Unit, cancelled: () -> Boolean) {
        val fixed = ByteBuffer.wrap(read(src, 0, 15)).order(ByteOrder.BIG_ENDIAN)
        val version = fixed.get(0).toInt()
        val headerLen = fixed.getInt(2).toLong() and 0xffffffffL
        val payloadType = fixed.get(6).toInt() and 0xff
        val hashLen = fixed.getInt(11).toLong() and 0xffffffffL
        val table = ByteBuffer.wrap(read(src, 15, hashLen.toInt())).order(ByteOrder.BIG_ENDIAN)
        val blocks = ArrayList<HashBlock>()
        while (table.remaining() >= 9) {
            val off = table.int.toLong() and 0xffffffffL
            val len = table.int.toLong() and 0xffffffffL
            val hl = table.get().toInt() and 0xff
            if (hl > table.remaining() || len > MAX_BLOCK) throw IOException("SIN v$version: corrupt hash table")
            blocks.add(HashBlock(off, len, ByteArray(hl).also { table.get(it) }))
        }
        log("SIN v$version: header $headerLen bytes, payload type 0x${payloadType.toString(16)}, ${blocks.size} block(s)")
        if (blocks.isEmpty()) {
            // no hash table: the image follows the header as is
            copyRange(src, headerLen, src.size - headerLen, out, 0, cancelled)
            return
        }
        if (blocks.size == 1) blocks[0].offset = 0
        // a first 16-byte block is partition info (NAND geometry), not image data
        val partInfo = blocks.size > 1 && blocks[0].length == 16L
        // v1 "with spare" NAND payload: every block carries its OOB bytes, the offsets in the table do not
        if (version == 1 && payloadType == 0x0A && partInfo) {
            val spare = blocks[1].length % 131072
            for (i in 1 until blocks.size) blocks[i].offset += spare * (i - 1)
        }
        val nand = payloadType == 0x09 || payloadType == 0x0A || payloadType == 0x0F
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            var pos = headerLen
            var written = 0L
            for ((i, b) in blocks.withIndex()) {
                if (cancelled()) throw IOException("cancelled")
                if (pos + b.length > src.size) throw IOException("SIN v$version: truncated (block $i)")
                val data = read(src, pos, b.length.toInt())
                pos += b.length
                verify(data, b.hash, i)
                if (partInfo && i == 0) continue
                // erased NAND reads as 0xFF: a gap left as zeros would look like programmed YAFFS2 tags
                if (nand && b.offset > written) fill(f, written, b.offset, 0xFF.toByte())
                f.seek(b.offset)
                f.write(data)
                written = maxOf(written, b.offset + b.length)
            }
        }
    }

    // ------------------------------------------------------------------ v3

    private fun unpackV3(src: RandomSource, out: File, log: (String) -> Unit, cancelled: () -> Boolean) {
        val fixed = ByteBuffer.wrap(read(src, 0, 24)).order(ByteOrder.BIG_ENDIAN)
        val headerLen = fixed.getInt(4).toLong() and 0xffffffffL
        val hashType = fixed.getInt(12)
        val hashLen = fixed.getInt(20).toLong() and 0xffffffffL
        if (hashType !in V3_HASH_LEN.indices || 24 + hashLen > headerLen) throw IOException("SIN v3: unsupported hash type $hashType")
        val hl = V3_HASH_LEN[hashType]
        val table = ByteBuffer.wrap(read(src, 24, hashLen.toInt())).order(ByteOrder.BIG_ENDIAN)
        val blocks = ArrayList<HashBlock>()
        while (table.remaining() >= 4 + hl) {
            val len = table.int.toLong() and 0xffffffffL
            if (len > MAX_BLOCK) throw IOException("SIN v3: corrupt hash table")
            blocks.add(HashBlock(0, len, ByteArray(hl).also { table.get(it) }))
        }
        val mmcf = read(src, headerLen, 16)
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            if (String(mmcf, 0, 4, Charsets.ISO_8859_1) != "MMCF") {
                log("SIN v3: ${blocks.size} contiguous block(s)")
                var pos = headerLen
                for ((i, b) in blocks.withIndex()) {
                    if (cancelled()) throw IOException("cancelled")
                    if (pos + b.length > src.size) throw IOException("SIN v3: truncated (block $i)")
                    val data = read(src, pos, b.length.toInt())
                    verify(data, b.hash, i)
                    f.seek(pos - headerLen)
                    f.write(data)
                    pos += b.length
                }
                return
            }
            val bb = ByteBuffer.wrap(mmcf).order(ByteOrder.BIG_ENDIAN)
            val mmcfLen = bb.getInt(4).toLong() and 0xffffffffL
            val gptpLen = bb.getInt(12).toLong() and 0xffffffffL
            if (String(mmcf, 8, 4, Charsets.ISO_8859_1) != "GPTP" || gptpLen < 8 || gptpLen > mmcfLen || mmcfLen > MAX_HEADER)
                throw IOException("SIN v3: corrupt MMCF header")
            val dataOffset = headerLen + mmcfLen + 8
            val map = ByteBuffer.wrap(read(src, headerLen + 8 + gptpLen, (mmcfLen - gptpLen).toInt())).order(ByteOrder.BIG_ENDIAN)
            var records = 0
            var lz4 = 0
            while (map.remaining() >= 8) {
                if (cancelled()) throw IOException("cancelled")
                val start = map.position()
                val magic = String(ByteArray(4).also { map.get(it) }, Charsets.ISO_8859_1)
                val blockLen = map.int
                when (magic) {
                    "ADDR" -> {
                        val off = map.long; val len = map.long; val fileOff = map.long
                        if (len !in 0..MAX_BLOCK) throw IOException("SIN v3: corrupt ADDR record")
                        f.seek(fileOff)
                        f.write(read(src, dataOffset + off, len.toInt()))
                    }
                    "LZ4A" -> {
                        val off = map.long; val plain = map.long; val packed = map.long; val fileOff = map.long
                        if (plain !in 0..MAX_BLOCK || packed !in 0..MAX_BLOCK) throw IOException("SIN v3: corrupt LZ4A record")
                        val data = BlockLZ4CompressorInputStream(ByteArrayInputStream(read(src, dataOffset + off, packed.toInt()))).use { s ->
                            ByteArray(plain.toInt()).also { readFully(s, it) }
                        }
                        f.seek(fileOff)
                        f.write(data)
                        lz4++
                    }
                    else -> throw IOException("SIN v3: unknown data record \"$magic\"")
                }
                if (blockLen < 8 || start + blockLen > map.limit()) throw IOException("SIN v3: corrupt data map")
                map.position(start + blockLen)
                records++
            }
            log("SIN v3: $records data record(s)" + if (lz4 > 0) ", $lz4 LZ4-compressed" else "")
        }
    }

    // ------------------------------------------------------------------ v4

    private fun unpackV4(src: RandomSource, dir: File, label: String, log: (String) -> Unit, cancelled: () -> Boolean): List<File> {
        val h = read(src, 0, minOf(src.size, 512L).toInt())
        val gz = h.size >= 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte()
        val tar = h.size > 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar"
        if (!gz && !tar) throw IOException("$label: not a Sony SIN image")
        val raw = stream(src)
        val parts = ArrayList<File>()
        TarArchiveInputStream(if (gz) GZIPInputStream(raw, 1 shl 16) else raw).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                if (cancelled()) throw IOException("cancelled")
                if (!e.isFile || e.name.endsWith("cms", true)) continue
                val f = File(dir, "part-%03d".format(parts.size))
                f.outputStream().use { o -> t.copyTo(o, 1 shl 20) }
                parts.add(f)
            }
        }
        if (parts.isEmpty()) throw IOException("SIN v4: no image in $label")
        log("SIN v4: ${parts.size} part(s)" + if (gz) " (gzip)" else "")
        return parts
    }

    // ------------------------------------------------------------------ helpers

    private fun verify(data: ByteArray, hash: ByteArray, index: Int) {
        val algo = when (hash.size) { 20 -> "SHA-1"; 32 -> "SHA-256"; else -> return }
        if (!MessageDigest.getInstance(algo).digest(data).contentEquals(hash)) throw IOException("SIN: block $index is corrupted (hash mismatch)")
    }

    private fun read(src: RandomSource, pos: Long, len: Int): ByteArray {
        if (len < 0) throw IOException("SIN: negative length")
        val buf = ByteBuffer.allocate(len)
        src.read(pos, buf)
        return buf.array()
    }

    private fun copyRange(src: RandomSource, from: Long, len: Long, out: File, at: Long, cancelled: () -> Boolean) {
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            f.seek(at)
            var p = 0L
            while (p < len) {
                if (cancelled()) throw IOException("cancelled")
                val n = minOf(len - p, 1L shl 20).toInt()
                f.write(read(src, from + p, n))
                p += n
            }
        }
    }

    private fun fill(f: RandomAccessFile, from: Long, to: Long, v: Byte) {
        val chunk = ByteArray(1 shl 16) { v }
        f.seek(from)
        var p = from
        while (p < to) { val n = minOf(to - p, chunk.size.toLong()).toInt(); f.write(chunk, 0, n); p += n }
    }

    private fun readFully(s: InputStream, b: ByteArray) {
        var o = 0
        while (o < b.size) { val n = s.read(b, o, b.size - o); if (n < 0) throw IOException("SIN v3: short LZ4 block"); o += n }
    }

    private fun stream(src: RandomSource): InputStream = object : InputStream() {
        var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= src.size) return -1
            val n = minOf(len.toLong(), src.size - pos).toInt()
            src.read(pos, ByteBuffer.wrap(b, off, n))
            pos += n
            return n
        }
    }
}
