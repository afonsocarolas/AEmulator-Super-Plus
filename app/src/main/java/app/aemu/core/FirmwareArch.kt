/* AEmulator Super Plus, 2026-10-14: which CPU a firmware's programs are built for. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * The engine runs 32-bit ARM guest code only (qemu-arm). Firmware for Intel phones (ASUS ZenFone 2/4/5/6, Lenovo K900,
 * Dell Venue...) or 64-bit builds cannot run at all: every program exited at once (code 255, "Invalid ELF image for
 * this architecture"). build.prop's ro.product.cpu.abi is not reliable for ports, so the programs themselves are read.
 * A 64-bit phone's firmware also carries a 32-bit linker for its few 32-bit programs (Nexus 5X 8.0: mediaserver,
 * audioserver, the secondary zygote), while servicemanager, surfaceflinger, installd and the main zygote are 64-bit:
 * the core programs decide, the linkers only when those are missing.
 */
object FirmwareArch {
    enum class Arch(val label: String, val runs: Boolean) {
        ARM("ARM 32-bit", true), ARM64("ARM 64-bit", false), X86("Intel x86", false), X86_64("Intel x86-64", false),
        MIPS("MIPS", false), OTHER("unknown", false),
    }

    /** CPU of an ELF file from its header (e_machine), null when it is not an ELF file. */
    fun ofElf(header: ByteArray): Arch? {
        if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return null
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        return when (machine) {
            40 -> Arch.ARM
            183 -> Arch.ARM64
            3 -> Arch.X86
            62 -> Arch.X86_64
            8, 10 -> Arch.MIPS
            else -> Arch.OTHER
        }
    }

    private fun read(f: File): Arch? = runCatching {
        RandomAccessFile(f, "r").use { r -> ByteArray(20).also { r.readFully(it) } }
    }.getOrNull()?.let(::ofElf)

    /** Programs every Android boot starts first; real files (not links) in every version. */
    private val CORE = listOf("system/bin/servicemanager", "system/bin/surfaceflinger", "system/bin/installd")

    /**
     * The CPU the firmware's core programs are built for; else its 32-bit linker, else the 64-bit one. Null when the tree
     * has none of them (glibc firmware, or a tree the importer could not finish): nothing is decided then.
     */
    fun of(root: File): Arch? =
        CORE.firstNotNullOfOrNull { read(File(root, it)) }
            ?: read(File(root, "system/bin/linker")) ?: read(File(root, "system/bin/linker64"))

    /**
     * The binder protocol the firmware's Treble library was built for: 8 (64-bit binder ABI, every device that launched
     * with 8.0) or 7 (BINDER_IPC_32BIT, 32-bit phones upgraded from 7.x). libhwbinder holds the ioctl number of its
     * BINDER_WRITE_READ, which encodes the size of struct binder_write_read: 0xc0306201 (48 bytes) or 0xc0186201 (24).
     * Null without a 32-bit libhwbinder or when it holds neither.
     */
    fun hwbinderProtocol(root: File): Int? {
        val lib = File(root, "system/lib/libhwbinder.so").takeIf { it.isFile && it.length() < (16L shl 20) } ?: return null
        return binderProtocolOf(runCatching { lib.readBytes() }.getOrNull() ?: return null)
    }

    fun binderProtocolOf(bytes: ByteArray): Int? {
        fun u(i: Int) = bytes[i].toInt() and 0xff
        // the whole number in a literal pool (01 62 xx c0), or its upper half built by MOVT #0xc0xx:
        // Thumb-2 (CC F2 xx 0R, imm3 = 0) or ARM (xx R0 4C E3, imm12 = 0x0xx)
        fun has(b2: Int) = (0..bytes.size - 4).any { i ->
            (u(i) == 0x01 && u(i + 1) == 0x62 && u(i + 2) == b2 && u(i + 3) == 0xc0) ||
                (u(i) == 0xcc && u(i + 1) == 0xf2 && u(i + 2) == b2 && (u(i + 3) and 0xf0) == 0) ||
                (u(i) == b2 && (u(i + 1) and 0x0f) == 0 && u(i + 2) == 0x4c && u(i + 3) == 0xe3)
        }
        val wide = has(0x30); val narrow = has(0x18)
        return when { wide -> 8; narrow -> 7; else -> null }
    }

    /**
     * What /dev/hwbinder speaks for this firmware: what libhwbinder says, else 7. libbinder and libhwbinder are built
     * with the same BINDER_IPC_32BIT setting, and the engine's /dev/binder only speaks the 32-bit format, so a firmware
     * that gets as far as Treble uses the 32-bit ABI.
     */
    fun hwbinderProtocolOrDefault(root: File): Int = hwbinderProtocol(root) ?: 7

    fun problem(arch: Arch): String? = when {
        arch.runs -> null
        arch == Arch.ARM64 -> "this firmware's system programs are 64-bit (ARM64); AEmulator runs 32-bit ARM Android code only"
        else -> "this firmware is built for ${arch.label} processors; AEmulator runs ARM Android firmware only"
    }
}
