package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStallTest {
    private fun r(prio: Char, tag: String, msg: String) = GuestLog.Rec(prio, tag, msg)

    @Test fun showsWhatSystemServerWaitsFor() {
        val system = listOf(
            r('I', "SystemServer", "StartPackageManagerService"),
            r('I', "SystemServiceManager", "Starting com.android.server.StorageManagerService\$Lifecycle"),
        ) + List(5) { r('W', "StorageManagerService", "vold isn't up yet; waiting for service") }
        val main = listOf(r('I', "chatty", "noise"), r('E', "AudioFlinger", "no audio HAL"))
        val out = BootStall.report(system, main)
        assertEquals("waiting / failing:", out[0])
        assertTrue(out.contains("  W/StorageManagerService: vold isn't up yet; waiting for service"))
        assertTrue("repeats collapse", out.contains("  W/StorageManagerService: vold isn't up yet; waiting for service (×5)"))
        assertTrue(out.contains("  E/AudioFlinger: no audio HAL"))
        assertTrue(out.none { it.contains("chatty") })
        assertTrue(out.size <= 35)
    }

    @Test fun emptyGuestLogSaysSo() {
        assertEquals(listOf("the guest has logged nothing yet"), BootStall.report(emptyList(), emptyList()))
    }
}
