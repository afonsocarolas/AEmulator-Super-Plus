package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BuiltInSkinsTest {
    @Test fun screensHaveTheShapeOfTheirResolution() {
        for (a in BuiltInSkins.ART) {
            val shape = (a.sr - a.sl).toDouble() / (a.sb - a.st)
            val res = a.resW.toDouble() / a.resH
            assertTrue("${a.id}: screen ${a.sr - a.sl}x${a.sb - a.st} vs ${a.resW}x${a.resH}", abs(shape - res) / res < 0.01)
            assertTrue(a.id, a.sl > 0 && a.st > 0 && a.sr < a.w && a.sb < a.h)
        }
    }

    @Test fun keysSitOnTheBodyBelowTheScreenAndDoNotOverlap() {
        for (a in BuiltInSkins.ART) {
            for (k in a.keys) {
                assertTrue("${a.id} ${k.glyph}", k.l >= 0 && k.r <= a.w && k.b <= a.h && k.l < k.r && k.t < k.b)
                assertTrue("${a.id} ${k.glyph} below the screen", k.t > a.sb)
            }
            for (i in a.keys.indices) for (j in i + 1 until a.keys.size) {
                val x = a.keys[i]; val y = a.keys[j]
                val overlap = x.l < y.r && y.l < x.r && x.t < y.b && y.t < x.b
                assertTrue("${a.id}: ${x.glyph} and ${y.glyph} overlap", !overlap)
            }
        }
    }

    @Test fun themesAreCompleteSkins() {
        val themes = BuiltInSkins.themes()
        assertEquals(BuiltInSkins.ART.size, themes.map { it.id }.toSet().size)
        for (t in themes) {
            assertTrue(t.id, t.isSkin && t.builtIn && t.skinWidth > 0)
            assertTrue(t.id, BuiltInSkins.isBuiltInFile(t.frameImage!!))
            assertTrue("file name passes the theme loader's check", VmTheme.safeFileName(t.frameImage!!))
        }
        // the slider era phone: its keys work, the trackball presses the d-pad centre
        val slider = themes.first { it.id == "skin-slider2008" }
        assertEquals(setOf(NavButton.HOME, NavButton.MENU, NavButton.BACK, NavButton.POWER, NavButton.CENTER, NavButton.SEARCH),
            slider.skinKeys.map { it.button }.toSet())
        assertTrue(themes.first { it.id == "skin-modern2013" }.skinKeys.isEmpty())
    }
}
