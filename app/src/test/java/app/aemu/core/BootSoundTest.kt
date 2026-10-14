package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BootSoundTest {
    @Test fun builtInSoundsAreShortAudibleAndUnclipped() {
        for (s in listOf(BootSound.Sound.CHIME, BootSound.Sound.RETRO, BootSound.Sound.RISE)) {
            val pcm = BootSound.pcm(s)
            val seconds = pcm.size.toDouble() / BootSound.RATE
            assertTrue("$s length $seconds s", seconds in 0.3..2.5)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$s peak $peak", peak in 15000..21000)      // about 60% of full scale
            assertTrue("$s ends quietly", abs(pcm.last().toInt()) < 2000)
        }
    }

    @Test fun offAndCustomHaveNoSynthesizedSamples() {
        assertEquals(0, BootSound.pcm(BootSound.Sound.OFF).size)
        assertEquals(0, BootSound.pcm(BootSound.Sound.CUSTOM).size)
    }
}
