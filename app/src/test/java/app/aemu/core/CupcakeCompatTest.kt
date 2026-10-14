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

    @Test fun donutRunsLikeCupcake() {
        assertTrue(CupcakeCompat.preEclair(3)); assertTrue(CupcakeCompat.preEclair(4)); assertEquals(false, CupcakeCompat.preEclair(5))
        assertEquals(false, CupcakeCompat.applies(4))
        fun img(api: Int) = GuestImage(id = "x", name = "x", release = "1.6", api = api, brand = "", model = "", skin = "",
            engine = Engine.KK, bootclasspath = "", exports = emptyMap(), dirs = emptyList(), services = emptyList(),
            sdcardPath = "/sdcard", settings = VmSettings(gpu = true, hwui = true, mtMode = 0), createdAt = 0)
        val donut = CupcakeCompat.adjust(img(4)).settings
        assertEquals(false, donut.gpu); assertEquals(CupcakeCompat.SINGLE_TOUCH, donut.mtMode)
        val cupcake = CupcakeCompat.adjust(img(3)).settings
        assertEquals(false, cupcake.gpu); assertEquals(CupcakeCompat.SINGLE_TOUCH, cupcake.mtMode)
        assertEquals(VmSettings(gpu = true, hwui = true, mtMode = 0), CupcakeCompat.adjust(img(8)).settings)
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

    @Test fun shimCrashReportShowsItsHeadAndWhatTheProgramSaid() {
        val log = listOf(
            r('I', "Zygote", "earlier"),
            r('F', "libc", "Fatal signal 6 (SIGABRT)"),
            r('F', "aemu_crash", "*** SIGABRT pid 23938 tid 23938 addr 0x00005d82"),
            r('F', "aemu_crash", "thread libqemu_kk.so"),
            r('F', "aemu_crash", "maps exec 120 stack end 0x40801000"),
            r('F', "aemu_crash", "pc 0x420d17a4 /system/lib/libaemushim.so+0x000017a4"),
        ) + (0..30).map { r('F', "aemu_crash", "  #$it sp+0x10  0x0 /system/lib/libc.so") }
        val out = CupcakeCompat.crashSummary(log, listOf("dhdrun: socket zygote",
            "app_process: Unable to determine ABI list from property ro.product.cpu.abilist32.",
            "*** SIGABRT pid 23938 tid 23938 addr 0x00005d82", "qemu: uncaught target signal 6 (Aborted) - core dumped"))
        assertTrue(out.contains("F/libc: Fatal signal 6 (SIGABRT)"))
        assertTrue(out.contains("F/aemu_crash: pc 0x420d17a4 /system/lib/libaemushim.so+0x000017a4"))
        assertTrue(out.none { it.contains("#30") })
        assertTrue(out.contains("zygote output: app_process: Unable to determine ABI list from property ro.product.cpu.abilist32."))
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
