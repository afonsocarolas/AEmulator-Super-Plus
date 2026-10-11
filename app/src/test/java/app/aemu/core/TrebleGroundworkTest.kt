/* AEmulator Super Plus, 2026-10-11: Android 8.x (Treble) groundwork. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrebleGroundworkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun oreoTree(): File {
        val root = tmp.newFolder("root")
        for (bin in listOf("system/bin/servicemanager", "system/bin/hwservicemanager", "vendor/bin/vndservicemanager",
                "system/bin/surfaceflinger", "vendor/bin/hw/android.hardware.graphics.allocator@2.0-service"))
            File(root, bin).apply { parentFile.mkdirs(); writeText("") }
        File(root, "vendor/manifest.xml").writeText("<manifest/>")
        return root
    }

    private fun rc(root: File): InitPlan.Rc {
        val f = File(root, "init.rc")
        f.writeText("""
            service servicemanager /system/bin/servicemanager
            service hwservicemanager /system/bin/hwservicemanager
            service vndservicemanager /vendor/bin/vndservicemanager /dev/vndbinder
            service surfaceflinger /system/bin/surfaceflinger
            service vendor.gralloc-2-0 /vendor/bin/hw/android.hardware.graphics.allocator@2.0-service
        """.trimIndent())
        return InitPlan.parse(listOf(f))
    }

    @Test fun serviceManagersStartBeforeSurfaceFlinger() {
        val root = oreoTree()
        val names = InitPlan.plan(rc(root), 26, root).map { it.name }
        assertEquals(listOf("servicemanager", "hwservicemanager", "vndservicemanager", "surfaceflinger"), names.take(4))
        assertTrue("vendor HAL kept as an optional extra", "vendor.gralloc-2-0" in names)
    }

    @Test fun reportListsHidlServicesAndMissingHals() {
        val root = oreoTree()
        val lines = TrebleReport.lines(26, root, InitPlan.plan(rc(root), 26, root))
        assertTrue(lines.any { it.contains("VINTF manifest /vendor/manifest.xml") })
        assertTrue(lines.any { it.contains("3 HIDL service(s)") && it.contains("vendor.gralloc-2-0") })
        assertTrue(lines.any { it.contains("no android.hardware.graphics.composer") })
        assertTrue(lines.none { it.contains("no android.hardware.graphics.allocator") })
    }

    @Test fun olderReleasesGetNoReport() = assertTrue(TrebleReport.lines(25, oreoTree(), emptyList()).isEmpty())
}
