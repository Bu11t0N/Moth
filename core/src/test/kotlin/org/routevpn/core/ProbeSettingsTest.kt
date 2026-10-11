package org.routevpn.core

import org.json.JSONObject
import kotlin.test.*

class ProbeSettingsTest {
    @Test fun defaultsAndRoundTrip() {
        val d = ClientSettings()
        assertEquals(5, d.probeParallel); assertEquals(5000, d.probeTimeoutMs)
        val back = ClientSettings.from(ClientSettings(probeParallel = 16, probeTimeoutMs = 750).validate().json())
        assertEquals(16, back.probeParallel); assertEquals(750, back.probeTimeoutMs)
    }
    @Test fun oldJsonMigrates() {
        fun old(block: JSONObject.() -> Unit) = ClientSettings.from(JSONObject(ClientSettings(timeoutSeconds = 12).json().apply { remove("probeParallel"); remove("probeTimeoutMs"); block() }.toString()))
        assertEquals(5, old { }.probeParallel)
        assertEquals(12000, old { }.probeTimeoutMs)
        assertEquals(7000, old { put("probeTimeoutSeconds", 7) }.probeTimeoutMs)
        assertEquals(1, old { put("probeParallel", 0) }.probeParallel)
        assertEquals(16, old { put("probeParallel", 40) }.probeParallel)
    }
    @Test fun validation() {
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeParallel = 0).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeParallel = 17).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeTimeoutMs = 499).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeTimeoutMs = 30001).validate() }
    }
    @Test fun userAgentFollowsAppVersion() {
        assertEquals("Moth/0.4.2", ClientSettings().withAppVersion("0.4.2").userAgent)
        assertEquals("Moth/0.4.2", ClientSettings(userAgent = "Moth/0.2").withAppVersion("0.4.2").userAgent)
        assertEquals("v2rayN/6", ClientSettings(userAgent = "v2rayN/6").withAppVersion("0.4.2").userAgent)
    }
}
