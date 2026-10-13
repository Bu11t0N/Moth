package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class TrafficWarningTest {
    private val gb = 1_000_000_000L
    @Test fun oncePerThresholdAndPeriod() {
        assertNull(TrafficWarning.check(5 * gb, 0, 0, 90, null), "unlimited")
        var d = TrafficWarning.check(85 * gb, 100 * gb, 1000, 90, null)!!
        assertFalse(d.notify); assertEquals(85, d.percent)
        d = TrafficWarning.check(91 * gb, 100 * gb, 1000, 90, d.state)!!
        assertTrue(d.notify); assertEquals(91, d.percent)
        d = TrafficWarning.check(93 * gb, 100 * gb, 1000, 90, d.state)!!
        assertFalse(d.notify, "same threshold, same period")
        d = TrafficWarning.check(96 * gb, 100 * gb, 1000, 95, d.state)!!
        assertTrue(d.notify, "a higher threshold is new")
        assertFalse(TrafficWarning.check(97 * gb, 100 * gb, 1000, 90, d.state)!!.notify, "lower threshold after a higher one")
        // Usage reset by the provider: warn again.
        val reset = TrafficWarning.check(1 * gb, 100 * gb, 1000, 95, d.state)!!
        assertFalse(reset.notify); assertEquals(0, reset.state.notified)
        assertTrue(TrafficWarning.check(96 * gb, 100 * gb, 1000, 95, reset.state)!!.notify)
        // New period: total or end date changed.
        assertTrue(TrafficWarning.check(96 * gb, 100 * gb, 2000, 95, d.state)!!.notify)
        assertTrue(TrafficWarning.check(190 * gb, 200 * gb, 1000, 95, d.state)!!.notify)
        assertFalse(TrafficWarning.check(99 * gb, 100 * gb, 1000, 0, null)!!.notify, "off")
        assertTrue(TrafficWarning.check(120 * gb, 100 * gb, 0, 80, null)!!.notify, "over the limit")
    }
    @Test fun stateAndSettings() {
        val s = TrafficWarning.State("100:5", 42, 90)
        assertEquals(s, TrafficWarning.State.decode(s.encode()))
        assertNull(TrafficWarning.State.decode("x|y")); assertNull(TrafficWarning.State.decode(null))
        assertEquals(90, ClientSettings().trafficWarning)
        val c = ClientSettings(trafficWarning = 80)
        assertEquals(c, ClientSettings.from(c.json()))
        assertEquals(90, ClientSettings.from(c.json().put("trafficWarning", 50)).trafficWarning)
        assertEquals(90, ClientSettings.from(c.json().apply { remove("trafficWarning") }).trafficWarning)
        assertFailsWith<IllegalArgumentException> { ClientSettings(trafficWarning = 85).validate() }
        assertEquals(listOf("trafficWarning=90->0"), LogFacts.settingsChanges(ClientSettings(hwid = c.hwid), ClientSettings(hwid = c.hwid, trafficWarning = 0)))
        // Included in backups.
        val back = Backup.read(Backup.export(Backup.Content(ClientSettings(trafficWarning = 95), SubscriptionBook(emptyList())), null))
        assertEquals(95, back.settings.trafficWarning)
        assertEquals(95, JSONObject(Backup.export(Backup.Content(ClientSettings(trafficWarning = 95), SubscriptionBook(emptyList())), null)).getJSONObject("payload").getJSONObject("settings").getInt("trafficWarning"))
    }
}
