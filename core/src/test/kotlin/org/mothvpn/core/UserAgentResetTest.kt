package org.mothvpn.core

import kotlin.test.*

class UserAgentResetTest {
    @Test fun resetRestoresTheAutomaticValue() {
        val custom = ClientSettings(userAgent = "clash-verge/1.0")
        assertTrue(custom.customUserAgent("0.7.2"))
        val reset = custom.resetUserAgent("0.7.2")
        assertEquals("Moth/0.7.2", reset.userAgent)
        assertFalse(reset.customUserAgent("0.7.2"))
        assertEquals(custom.copy(userAgent = "Moth/0.7.2"), reset) // nothing else changes
    }
    @Test fun defaultIsNotCustom() {
        assertFalse(ClientSettings().withAppVersion("0.7.2").customUserAgent("0.7.2"))
        assertTrue(ClientSettings(userAgent = "Moth/0.6.0").customUserAgent("0.7.2")) // shown until the app version is applied
        // After reset the value follows app updates again.
        assertEquals("Moth/0.8.0", ClientSettings(userAgent = "x").resetUserAgent("0.7.2").withAppVersion("0.8.0").userAgent)
    }
}
