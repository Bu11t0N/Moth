package org.mothvpn.core

import kotlin.test.*

class HomeButtonTest {
    @Test fun checksWhileOffArePinging() {
        assertTrue(HomeButton.pinging(probing = true, running = false, transition = false))
        assertFalse(HomeButton.pinging(probing = false, running = false, transition = false))
    }
    @Test fun checksWhileConnectedOrSwitchingDoNotChangeTheButton() {
        assertFalse(HomeButton.pinging(probing = true, running = true, transition = false))
        assertFalse(HomeButton.pinging(probing = true, running = false, transition = true))
    }
    @Test fun aTapDuringChecksStopsThemAndNeverConnects() {
        assertEquals(HomeButton.Tap.STOP_CHECKS, HomeButton.tap(probing = true, running = false, transition = false))
        assertEquals(HomeButton.Tap.CONNECT, HomeButton.tap(probing = false, running = false, transition = false))
        assertEquals(HomeButton.Tap.DISCONNECT, HomeButton.tap(probing = true, running = true, transition = false))
        assertEquals(HomeButton.Tap.DISCONNECT, HomeButton.tap(probing = false, running = true, transition = false))
    }
}
