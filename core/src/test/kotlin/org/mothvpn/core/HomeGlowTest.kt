package org.mothvpn.core

import org.mothvpn.core.HomeGlow.Level
import kotlin.test.*

class HomeGlowTest {
    @Test fun levelsFollowTheMoth() {
        assertEquals(Level.OFF, HomeGlow.level(on = false, beat = false))
        assertEquals(Level.ON, HomeGlow.level(on = true, beat = false))
        assertEquals(Level.BEAT, HomeGlow.level(on = false, beat = true)) // connecting, or checking with the VPN off
        assertEquals(Level.BEAT, HomeGlow.level(on = true, beat = true)) // checking while connected
    }
    @Test fun offIsDimOnIsBrightAndSteady() {
        for (dark in listOf(true, false)) {
            val off = HomeGlow.intensity(Level.OFF, dark, true); val beat = HomeGlow.intensity(Level.BEAT, dark, true); val on = HomeGlow.intensity(Level.ON, dark, true)
            assertTrue(off.base < beat.base && beat.base + beat.pulse < on.base + 0.05f)
            assertEquals(0f, off.pulse); assertEquals(0f, on.pulse); assertTrue(beat.pulse > 0f)
            assertTrue(HomeGlow.alpha(on, 1f) <= 0.4f, "subtle, never neon")
        }
    }
    @Test fun pulseFollowsTheWings() {
        val beat = HomeGlow.intensity(Level.BEAT, true, true)
        assertTrue(HomeGlow.alpha(beat, 1f) > HomeGlow.alpha(beat, 0f))
        assertEquals(HomeGlow.alpha(beat, 0f), HomeGlow.alpha(beat, -1f))
    }
    @Test fun animationsOffShowAStaticGlow() {
        for (level in Level.values()) {
            val i = HomeGlow.intensity(level, true, animations = false)
            assertEquals(0f, i.pulse); assertTrue(i.base > 0f)
            assertEquals(HomeGlow.alpha(i, 0f), HomeGlow.alpha(i, 1f))
        }
    }
}
