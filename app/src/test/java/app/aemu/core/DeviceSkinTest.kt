package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSkinTest {
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()

    /** a 100x200 "phone": grey body, [screen] filled with [fill], everything outside [body] see-through */
    private fun phone(fill: Int, screenL: Int = 10, screenT: Int = 30, screenR: Int = 90, screenB: Int = 160, bodyMargin: Int = 0): IntArray {
        val w = 100; val h = 200
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < bodyMargin || x >= w - bodyMargin || y < bodyMargin || y >= h - bodyMargin -> 0x00000000
                x in screenL until screenR && y in screenT until screenB -> fill
                else -> grey
            }
        }
    }

    @Test fun findsCutOutScreen() {
        val found = DeviceSkin.findScreen(phone(0x00000000), 100, 200)!!
        assertEquals(0.10f, found.l, 0.001f); assertEquals(0.15f, found.t, 0.001f)
        assertEquals(0.90f, found.r, 0.001f); assertEquals(0.80f, found.b, 0.001f)
    }

    @Test fun findsFlatDarkScreenInsideTransparentSurroundings() {
        val found = DeviceSkin.findScreen(phone(black, bodyMargin = 4), 100, 200)!!
        assertEquals(0.10f, found.l, 0.001f); assertEquals(0.80f, found.b, 0.001f)
    }

    @Test fun regionTouchingTheEdgeIsBackgroundNotScreen() {
        // the centre is see-through and connected to the outside: no screen to find
        val pixels = phone(0x00000000, screenL = 0, screenR = 100)
        assertNull(DeviceSkin.findScreen(pixels, 100, 200))
    }

    @Test fun tinyRegionIsIgnored() {
        assertNull(DeviceSkin.findScreen(phone(black, screenL = 48, screenT = 98, screenR = 52, screenB = 102), 100, 200))
    }

    @Test fun layoutFitsPictureAndGuest() {
        // 400x800 picture into a 1080x1920 area: scaled by 2.4, centred horizontally
        val l = DeviceSkin.layout(1080, 1920, 400, 800, SkinRect(0.1f, 0.15f, 0.9f, 0.8f),
            listOf(SkinKey(NavButton.HOME, SkinRect(0.4f, 0.85f, 0.6f, 0.95f))), guestW = 480, guestH = 800)
        assertEquals(DeviceSkin.Box(60, 0, 960, 1920), l.image)
        assertEquals(DeviceSkin.Box(156, 288, 768, 1248), l.screen)
        // 480x800 (0.6) inside 768x1248 (0.615): full height... width-limited at 768? 1248*0.6 = 748.8 -> height-limited
        assertEquals(1248, l.guest.height)
        assertEquals(749, l.guest.width)
        assertTrue(l.guest.left >= l.screen.left && l.guest.right <= l.screen.right)
        assertEquals(NavButton.HOME, l.keys.single().first)
        assertEquals(DeviceSkin.Box(444, 1632, 192, 192), l.keys.single().second)
    }

    @Test fun aspectFollowsResolution() {
        val r = DeviceSkin.withAspect(SkinRect(0.1f, 0.2f, 0.9f, 0.6f), 480, 800, 400, 800)
        // width 0.8 of 400 px = 320 px; 320 * 800/480 = 533.3 px of 800 = 0.6667
        assertEquals(0.6667f, r.height, 0.001f)
        assertEquals(0.4f, (r.t + r.b) / 2, 0.001f)
    }

    @Test fun rectStaysInsidePicture() {
        val moved = SkinRect(0.1f, 0.1f, 0.5f, 0.5f).moved(0.9f, -0.5f)
        assertEquals(SkinRect(0.6f, 0f, 1f, 0.4f).l, moved.l, 0.0001f)
        assertEquals(0f, moved.t, 0.0001f)
        val n = SkinRect(0.5f, 0.5f, 0.5f, 0.2f).normalized()
        assertTrue(n.width >= SkinRect.MIN - 1e-5f && n.t < n.b)
    }
}
