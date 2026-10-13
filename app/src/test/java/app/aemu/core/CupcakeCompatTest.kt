package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CupcakeCompatTest {
    private fun r(prio: Char, tag: String, msg: String) = GuestLog.Rec(prio, tag, msg)

    @Test fun onlyCupcakeGetsTheProperty() {
        assertEquals("0", CupcakeCompat.props(3)["debug.egl.hw"])
        assertTrue(CupcakeCompat.props(4).isEmpty())
        assertTrue(CupcakeCompat.props(8).isEmpty())
    }

    @Test fun importDefaultsAreHvgaSoftware() {
        val s = CupcakeCompat.importDefaults(VmSettings())
        assertEquals(320, s.width); assertEquals(480, s.height); assertEquals(160, s.density)
        assertEquals(false, s.gpu); assertEquals(false, s.hwui); assertEquals(CupcakeCompat.SINGLE_TOUCH, s.mtMode)
    }

    @Test fun javaCrashInSystemServerIsReported() {
        val log = listOf(
            r('I', "SystemServer", "Starting Power Manager."),
            r('E', "AndroidRuntime", "*** FATAL EXCEPTION IN SYSTEM PROCESS: android.server.ServerThread"),
            r('E', "AndroidRuntime", "java.lang.UnsatisfiedLinkError: init1"),
            r('E', "AndroidRuntime", "\tat com.android.server.SystemServer.init1(Native Method)"),
            r('I', "Zygote", "Exit zygote because system server (91) has terminated"),
        )
        val out = CupcakeCompat.crashSummary(log)
        assertEquals("I/Zygote: Exit zygote because system server (91) has terminated", out[0])
        assertEquals("E/AndroidRuntime: java.lang.UnsatisfiedLinkError: init1", out[2])
        assertEquals(4, out.size)
    }

    @Test fun nativeCrashIsReported() {
        val log = listOf(
            r('I', "DEBUG", "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"),
            r('I', "DEBUG", "pid: 91, tid: 101  >>> system_server <<<"),
            r('I', "DEBUG", "signal 11 (SIGSEGV), fault addr 00000000"),
            r('I', "DEBUG", "         #00  pc 0000a1b4  /system/lib/libhgl.so"),
            r('E', "Other", "unrelated"),
        )
        val out = CupcakeCompat.crashSummary(log)
        assertEquals("I/DEBUG: signal 11 (SIGSEGV), fault addr 00000000", out.first())
        assertTrue(out.any { it.contains("libhgl.so") })
    }

    @Test fun otherwiseTheLastLinesBeforeZygoteNoticedAndQemuSignal() {
        val log = listOf(
            r('I', "SystemServer", "Entered the Android system server!"),
            r('W', "Something", "a warning"),
            r('I', "Zygote", "Process 91 terminated by signal (11)"),
            r('I', "Zygote", "Exit zygote because system server (91) has terminated"),
            r('E', "Later", "after the fact"),
        )
        val out = CupcakeCompat.crashSummary(log, listOf("starting", "qemu: uncaught target signal 11 (Segmentation fault) - core dumped"))
        assertEquals(listOf(
            "I/Zygote: Process 91 terminated by signal (11)",
            "I/Zygote: Exit zygote because system server (91) has terminated",
            "I/SystemServer: Entered the Android system server!",
            "W/Something: a warning",
            "zygote output: qemu: uncaught target signal 11 (Segmentation fault) - core dumped",
        ), out)
    }
}
