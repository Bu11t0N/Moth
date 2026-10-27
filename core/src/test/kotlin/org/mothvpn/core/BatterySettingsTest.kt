package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

/** 0.7.6: health-check interval and skipping, notification lines, auto-select reuse, ping timeout default. */
class BatterySettingsTest {
    /** A settings file as written by 0.7.5 and earlier (no "settingsVersion", no "healthCheckSeconds"). */
    private fun legacy(block: JSONObject.() -> Unit = {}) = ClientSettings.from(JSONObject(ClientSettings().json().apply {
        remove("settingsVersion"); remove("healthCheckSeconds"); remove("notifySpeed"); remove("notifyTotal"); remove("notifyUptime"); remove("autoSelectReuseMinutes")
        put("healthIntervalSeconds", 60); put("probeTimeoutMs", 5000); block()
    }.toString()))

    @Test fun defaults() {
        val d = ClientSettings()
        assertEquals(300, d.healthIntervalSeconds); assertTrue(d.healthEnabled)
        assertEquals(3000, d.probeTimeoutMs)
        assertTrue(d.notifySpeed && d.notifyTotal && d.notifyUptime && d.notifyAny)
        assertEquals(30, d.autoSelectReuseMinutes)
    }

    @Test fun oldDefaultsMigrateOnce() {
        val s = legacy()
        assertEquals(300, s.healthIntervalSeconds) // 60 s -> 5 min
        assertEquals(3000, s.probeTimeoutMs) // 5000 ms -> 3000 ms
        assertTrue(s.notifySpeed && s.notifyTotal && s.notifyUptime); assertEquals(30, s.autoSelectReuseMinutes)
        // Written back by 0.7.6, a chosen 1 min and 5000 ms stay as they are.
        val chosen = ClientSettings.from(s.copy(healthIntervalSeconds = 60, probeTimeoutMs = 5000).validate().json())
        assertEquals(60, chosen.healthIntervalSeconds); assertEquals(5000, chosen.probeTimeoutMs)
    }

    @Test fun oldCustomValuesAreKept() {
        assertEquals(120, legacy { put("healthIntervalSeconds", 120) }.healthIntervalSeconds)
        assertEquals(600, legacy { put("healthIntervalSeconds", 600) }.healthIntervalSeconds)
        assertEquals(300, legacy { put("healthIntervalSeconds", 240) }.healthIntervalSeconds) // nearest option
        assertEquals(60, legacy { put("healthIntervalSeconds", 30) }.healthIntervalSeconds)
        assertEquals(4000, legacy { put("probeTimeoutMs", 4000) }.probeTimeoutMs)
    }

    @Test fun roundTripAndOlderBuildsStayValid() {
        val s = ClientSettings(healthIntervalSeconds = 900, notifySpeed = false, notifyUptime = false, autoSelectReuseMinutes = 60).validate()
        val j = s.json()
        assertEquals(600, j.getInt("healthIntervalSeconds")) // 0.7.5 accepts at most 600
        val back = ClientSettings.from(j)
        assertEquals(900, back.healthIntervalSeconds); assertFalse(back.notifySpeed); assertTrue(back.notifyTotal); assertFalse(back.notifyUptime)
        assertEquals(60, back.autoSelectReuseMinutes)
        assertEquals(30, ClientSettings.from(ClientSettings(healthIntervalSeconds = 30).validate().json()).healthIntervalSeconds) // e2e value
        assertEquals(30, ClientSettings.from(ClientSettings().json().put("autoSelectReuseMinutes", 7)).autoSelectReuseMinutes)
        assertFailsWith<IllegalArgumentException> { ClientSettings(healthIntervalSeconds = 901).validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(autoSelectReuseMinutes = 7).validate() }
        assertFalse(ClientSettings(notifySpeed = false, notifyTotal = false, notifyUptime = false).notifyAny)
    }

    @Test fun healthCheckSkips() {
        val big = HealthSchedule.TRAFFIC_BYTES
        // No network: always skipped.
        assertEquals(HealthSchedule.Skip.NO_NETWORK, HealthSchedule.skip(false, 0, 0, 0, 0))
        assertEquals(HealthSchedule.Skip.NO_NETWORK, HealthSchedule.skip(false, 2, -1, 0, 0))
        // Traffic since the previous scheduled check: skipped.
        assertEquals(HealthSchedule.Skip.TRAFFIC, HealthSchedule.skip(true, 0, 1000, 1000 + big, 60_000))
        // Too little traffic (failed handshakes, resets), the first check, a failing server, or a long run of skips: checked.
        assertNull(HealthSchedule.skip(true, 0, 1000, 1000 + big - 1, 60_000))
        assertNull(HealthSchedule.skip(true, 0, -1, 10 * big, 60_000))
        assertNull(HealthSchedule.skip(true, 1, 1000, 1000 + 10 * big, 60_000))
        assertNull(HealthSchedule.skip(true, 0, 1000, 1000 + 10 * big, HealthSchedule.MAX_SKIP_MS))
        // TrafficStats unsupported (-1): checked.
        assertNull(HealthSchedule.skip(true, 0, -1, -1, 60_000))
    }

    @Test fun reuseWindowForNetworkSwitch() {
        val now = 100 * 60_000L
        val results = mapOf("a" to (90L to now - 20 * 60_000), "b" to (null to now - 25 * 60_000))
        assertEquals(listOf("a"), ServerPicker.recentRanking(listOf("a", "b"), results, now, AutoSelectReuse.DEFAULT * 60_000L))
        assertNull(ServerPicker.recentRanking(listOf("a", "b"), results, now, 15 * 60_000L))
        assertNull(ServerPicker.recentRanking(listOf("a", "b"), results, now)) // connect keeps the 5-minute window
    }
}
