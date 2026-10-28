package org.mothvpn.core

import kotlin.test.*

class LargeTitleSizeTest {
    @Test fun defaultRoundTripAndClamps() {
        assertEquals(20, ClientSettings().largeTitlePercent)
        assertEquals(27, ClientSettings.from(ClientSettings(largeTitlePercent = 27).validate().json()).largeTitlePercent)
        val old = ClientSettings().json().apply { remove("largeTitlePercent") }
        assertEquals(20, ClientSettings.from(old).largeTitlePercent)
        assertEquals(10, ClientSettings.from(ClientSettings().json().put("largeTitlePercent", 2)).largeTitlePercent)
        assertEquals(30, ClientSettings.from(ClientSettings().json().put("largeTitlePercent", 90)).largeTitlePercent)
        assertFailsWith<IllegalArgumentException> { ClientSettings(largeTitlePercent = 50).validate() }
    }
    @Test fun extraHeightStaysWithinSoftLimits() {
        assertEquals(160f, LargeTitleSize.extraDp(20, 800))
        assertEquals(56f, LargeTitleSize.extraDp(10, 400))
        assertEquals(260f, LargeTitleSize.extraDp(30, 1200))
        assertEquals(260f, LargeTitleSize.extraDp(99, 1200))
    }
}
