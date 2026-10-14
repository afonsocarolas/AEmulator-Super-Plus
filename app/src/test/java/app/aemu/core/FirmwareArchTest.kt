package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirmwareArchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun elf(machine: Int, cls: Int = 1) = ByteArray(52).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = cls.toByte(); it[5] = 1
        it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
    }

    @Test fun readsMachine() {
        assertEquals(FirmwareArch.Arch.ARM, FirmwareArch.ofElf(elf(40)))
        assertEquals(FirmwareArch.Arch.X86, FirmwareArch.ofElf(elf(3)))
        assertEquals(FirmwareArch.Arch.X86_64, FirmwareArch.ofElf(elf(62, 2)))
        assertEquals(FirmwareArch.Arch.ARM64, FirmwareArch.ofElf(elf(183, 2)))
        assertNull(FirmwareArch.ofElf("#!/system/bin/sh\n".toByteArray() + ByteArray(20)))
    }

    private fun tree(vararg files: Pair<String, ByteArray>): File {
        val root = tmp.newFolder()
        for ((rel, bytes) in files) File(root, rel).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return root
    }

    @Test fun intelPhoneIsRefused() {
        val arch = FirmwareArch.of(tree("system/bin/linker" to elf(3)))!!
        assertEquals(FirmwareArch.Arch.X86, arch)
        assertNotNull(FirmwareArch.problem(arch))
    }

    @Test fun thirtyTwoBitFirmwareOnA64BitPhoneRuns() {
        // 32-bit Android on a 64-bit chip (budget phones of 2017-2018): core programs are 32-bit
        val arch = FirmwareArch.of(tree("system/bin/servicemanager" to elf(40), "system/bin/linker" to elf(40),
            "system/bin/linker64" to elf(183, 2)))!!
        assertEquals(FirmwareArch.Arch.ARM, arch)
        assertNull(FirmwareArch.problem(arch))
    }

    @Test fun sixtyFourBitCoreIsRefusedDespiteA32BitLinker() {
        // Nexus 5X 8.0: a 32-bit linker for mediaserver & co., but servicemanager and the main zygote are 64-bit
        val arch = FirmwareArch.of(tree("system/bin/servicemanager" to elf(183, 2), "system/bin/linker" to elf(40),
            "system/bin/linker64" to elf(183, 2)))!!
        assertEquals(FirmwareArch.Arch.ARM64, arch)
        assertNotNull(FirmwareArch.problem(arch))
    }

    @Test fun linkerDecidesWhenCoreProgramsAreMissing() {
        assertEquals(FirmwareArch.Arch.ARM, FirmwareArch.of(tree("system/bin/linker" to elf(40))))
    }

    @Test fun sixtyFourBitOnlyIsRefused() {
        assertEquals(FirmwareArch.Arch.ARM64, FirmwareArch.of(tree("system/bin/linker64" to elf(183, 2))))
    }

    @Test fun binderProtocolFromLibhwbinder() {
        fun lib(vararg code: Int) = ByteArray(64) + code.flatMap { listOf(1, 0x62, it, 0xc0).map(Int::toByte) }.toByteArray() + ByteArray(16)
        assertEquals(7, FirmwareArch.binderProtocolOf(lib(0x18)))
        assertEquals(8, FirmwareArch.binderProtocolOf(lib(0x30)))
        // a 64-bit build also carries the 32-bit number for its compat path: 8 wins
        assertEquals(8, FirmwareArch.binderProtocolOf(lib(0x18, 0x30)))
        assertNull(FirmwareArch.binderProtocolOf(ByteArray(100)))
        assertEquals(7, FirmwareArch.hwbinderProtocol(tree("system/lib/libhwbinder.so" to lib(0x18))))
    }

    @Test fun binderProtocolFromMovt() {
        fun bytes(vararg b: Int) = ByteArray(32) + b.map(Int::toByte).toByteArray() + ByteArray(32)
        // Thumb-2 movt r3, #0xc018 = F2CC 0318 (halfwords little endian: CC F2 18 03)
        assertEquals(7, FirmwareArch.binderProtocolOf(bytes(0xcc, 0xf2, 0x18, 0x03)))
        assertEquals(8, FirmwareArch.binderProtocolOf(bytes(0xcc, 0xf2, 0x30, 0x01)))
        // ARM movt r1, #0xc018 = E34C1018
        assertEquals(7, FirmwareArch.binderProtocolOf(bytes(0x18, 0x10, 0x4c, 0xe3)))
        // unknown firmware: the engine's 32-bit /dev/binder means protocol 7
        assertEquals(7, FirmwareArch.hwbinderProtocolOrDefault(tree("system/build.prop" to "x=1".toByteArray())))
    }

    @Test fun noLinkerDecidesNothing() {
        assertNull(FirmwareArch.of(tree("system/build.prop" to "x=1".toByteArray())))
    }
}
