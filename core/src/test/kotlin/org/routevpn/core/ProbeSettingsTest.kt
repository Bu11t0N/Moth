package org.routevpn.core

import org.json.JSONObject
import kotlin.test.*

class ProbeSettingsTest {
    @Test fun defaultsAndRoundTrip() {
        val s = ClientSettings(probeParallel = 5, probeTimeoutSeconds = 7).validate()
        val back = ClientSettings.from(s.json())
        assertEquals(5, back.probeParallel); assertEquals(7, back.probeTimeoutSeconds)
    }
    @Test fun oldJsonGetsDefaults() {
        val old = ClientSettings(timeoutSeconds = 12).json().apply { remove("probeParallel"); remove("probeTimeoutSeconds") }
        val s = ClientSettings.from(JSONObject(old.toString()))
        assertEquals(3, s.probeParallel); assertEquals(12, s.probeTimeoutSeconds)
    }
    @Test fun validation() {
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeParallel = 0).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeParallel = 9).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(probeTimeoutSeconds = 2).validate() }
    }
}
