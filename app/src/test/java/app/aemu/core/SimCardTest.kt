/* AEmulator Super Plus, 2026-10-11: configurable fake SIM cards. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimCardTest {
    private val apn = "310" to "260"

    @Test fun untouchedSimKeepsTheIdentifiersExistingVmsHad() {
        val r = SimCards.resolve(SimCard(), apn)
        assertEquals("AEmulator", r.operator)
        assertEquals(LegacySimIo.ICCID, r.iccid)
        assertEquals("310260" + "012345678", r.imsi)   // the old mcc + mnc + "0123456789".take(...)
    }

    @Test fun customOperatorAndNumber() {
        val r = SimCards.resolve(SimCard(mcc = "268", mnc = "06", number = "+351912345678"), apn)
        assertEquals("MEO", r.operator)                 // name from the preset list when left blank
        assertEquals("26806", r.imsi.take(5))
        assertEquals(15, r.imsi.length)
        assertEquals("912345678", r.imsi.takeLast(9))
        assertEquals(19, r.iccid.length)
        assertEquals(SimCards.luhn(r.iccid.dropLast(1)), r.iccid.last() - '0')
        assertEquals("Own name", SimCards.resolve(SimCard(operator = "Own name", mcc = "268", mnc = "06"), apn).operator)
    }

    @Test fun threeDigitMncAndInvalidCodesFallBack() {
        assertEquals("405857", SimCards.resolve(SimCard(mcc = "405", mnc = "857"), apn).imsi.take(6))
        assertEquals(byteArrayOf(0, 0, 0, 3).toList(), SimCards.efAd("857").toList())
        assertEquals("310260", SimCards.resolve(SimCard(mcc = "26", mnc = "06"), apn).imsi.take(6))
    }

    @Test fun luhnMatchesTheTextbookExample() = assertEquals(3, SimCards.luhn("7992739871"))

    @Test fun cleaning() {
        assertEquals("+351912", SimCard.cleanNumber("+351 912"))
        assertEquals("351", SimCard.cleanNumber("351"))
        assertEquals("268", SimCard.cleanMcc("2a6b8c9"))
        assertEquals(16, SimCard.cleanOperator("x".repeat(40)).length)
    }

    @Test fun spnAndMsisdnFiles() {
        val spn = SimCards.efSpn("MEO")
        assertEquals(17, spn.size)
        assertEquals("MEO", String(spn, 1, 3, Charsets.US_ASCII))
        assertEquals(0xff.toByte(), spn[4])
        val rec = SimCards.efMsisdn("+351912345678")
        assertEquals(SimCards.MSISDN_RECORD, rec.size)
        assertEquals(7.toByte(), rec[14])               // TON/NPI + 6 BCD bytes for 12 digits
        assertEquals(0x91.toByte(), rec[15])
        assertEquals(0x53.toByte(), rec[16])            // "35" nibble-swapped
        assertEquals(0xff.toByte(), SimCards.efMsisdn("")[14])
    }


    // ---------------------------------------------------------------- SIM_IO on the new files

    private fun io(command: Int, file: Int, p1: Int, p2: Int, p3: Int, path: String = "3F007FFF"): ByteBuffer {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(file).putInt(path.length)
        path.forEach { b.putChar(it) }; b.putChar('\u0000')
        while (b.position() % 4 != 0) b.put(0)
        b.putInt(p1).putInt(p2).putInt(p3)
        b.flip()
        return b
    }

    @Test fun simIoServesOperatorNumberAndMncLength() {
        val files = LegacySimIo.Files(SimCards.efIccid("8935106000000000001"), SimCards.efSpn("NOS"),
            SimCards.efMsisdn("+351930000000"), SimCards.efAd("03"))
        val hdr = LegacySimIo.handle(io(0xc0, 0x6f40, 0, 0, 15), files)
        assertEquals(0x90, hdr.sw1)
        assertEquals("01", hdr.hex!!.substring(26, 28))  // linear fixed
        assertEquals("1C", hdr.hex.substring(28, 30))    // record length 28
        val rec = LegacySimIo.handle(io(0xb2, 0x6f40, 1, 4, 28), files)
        assertEquals(56, rec.hex!!.length)
        val spn = LegacySimIo.handle(io(0xb0, 0x6f46, 0, 0, 17), files)
        assertEquals("00", spn.hex!!.take(2))
        assertEquals("02", LegacySimIo.handle(io(0xb0, 0x6fad, 0, 3, 1), files).hex)
        assertNull(LegacySimIo.handle(io(0xb0, 0x6f40, 0, 0, 28), files).hex)    // records are not read as binary
        assertArrayEquals(SimCards.efIccid(LegacySimIo.ICCID), SimCards.efIccid("89000000000000000004"))
    }
}
