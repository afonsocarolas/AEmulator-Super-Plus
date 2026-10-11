/* AEmulator Super Plus, 2026-10-11: Wi-Fi signal level and neighbouring networks. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class WifiNeighborsTest {
    private fun props(s: VmSettings) = Properties().apply { load(ByteArrayInputStream(WifiEmu.encode(WifiEmu.properties(s)))) }

    @Test fun signalBarsMapToRssi() {
        assertEquals("-48", props(VmSettings()).getProperty("rssi"))     // the old fixed value stays the default
        assertEquals("-84", props(VmSettings(wifiSignal = 1)).getProperty("rssi"))
        // Android's calculateSignalLevel(rssi, 5): (rssi + 100) * 4 / 45
        for (bars in 1..4) assertEquals(bars, (WifiEmu.rssiFor(bars) + 100) * 4 / 45)
    }

    @Test fun neighboursAreStableLockedAndParseable() {
        val a = WifiEmu.neighbors("Home")
        assertEquals(a, WifiEmu.neighbors("Home"))
        assertTrue(a.size in 4..5)
        for (line in a) {
            val f = line.split('|')
            assertEquals(5, f.size)
            assertTrue(f[2].contains("WPA2"))                                // never joinable
            assertTrue(f[3].toInt() < WifiEmu.rssiFor(4))                     // weaker than ours
            assertTrue(f[4].toInt() in 2400..5900)
        }
        // survives the properties file (newline separated) intact
        assertEquals(a.joinToString("\n"), props(VmSettings(wifiSsid = "Home")).getProperty("neighbors"))
        assertFalse(props(VmSettings(wifiNeighbors = false)).containsKey("neighbors"))
        assertTrue(WifiEmu.neighbors("TP-Link_3F2A").none { it.startsWith("TP-Link_3F2A|") })
    }
}
