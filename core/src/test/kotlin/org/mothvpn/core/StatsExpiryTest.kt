package org.mothvpn.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class TrafficHistoryTest {
    private val today = LocalDate.of(2026, 10, 12)
    @Test fun counterDeltasHandleResets() {
        assertEquals(100, TrafficHistory.delta(50, 150))
        assertEquals(30, TrafficHistory.delta(5000, 30)) // reboot: counted from zero
        assertEquals(0, TrafficHistory.delta(-1, 30)); assertEquals(0, TrafficHistory.delta(30, -1))
    }
    @Test fun aggregatesAndPersists() {
        val h = TrafficHistory().add(today, 10, 20).add(today, 1, 2).add(today.minusDays(1), 5, 5)
            .add(LocalDate.of(2026, 9, 30), 100, 100).add(today.minusDays(40), 7, 7).add(today, 0, 0)
        assertEquals(11L to 22L, h.day(today))
        assertEquals(16L to 27L, h.month(today))
        assertEquals(116L to 127L, h.lastDays(today, 30))
        assertEquals(30, h.series(today).size); assertEquals(today, h.series(today).last().first)
        assertEquals(h, TrafficHistory.from(h.json()))
        assertEquals(h.days.size, TrafficHistory.from(h.json().put("bad", "x").put("2026-13-01", org.json.JSONObject())).days.size)
    }
    @Test fun keeps90Days() {
        val h = TrafficHistory().add(today.minusDays(89), 1, 1).add(today.minusDays(90), 1, 1).prune(today)
        assertEquals(setOf(today.minusDays(89).toString()), h.days.keys)
    }
}

class ExpiryWarningTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 12)
    private fun at(day: LocalDate) = day.atTime(15, 0).atZone(zone).toEpochSecond()
    @Test fun soonAndToday() {
        assertEquals(ExpiryWarning.Kind.SOON, ExpiryWarning.due(at(today.plusDays(3)), today, zone, emptySet(), ""))
        assertEquals(ExpiryWarning.Kind.SOON, ExpiryWarning.due(at(today.plusDays(1)), today, zone, emptySet(), ""))
        assertEquals(ExpiryWarning.Kind.TODAY, ExpiryWarning.due(at(today), today, zone, emptySet(), ""))
        assertNull(ExpiryWarning.due(at(today.plusDays(4)), today, zone, emptySet(), ""))
        assertNull(ExpiryWarning.due(at(today.minusDays(1)), today, zone, emptySet(), ""))
        assertNull(ExpiryWarning.due(0, today, zone, emptySet(), ""))
    }
    @Test fun onceEachAndOncePerDay() {
        val e = at(today.plusDays(2))
        assertNull(ExpiryWarning.due(e, today, zone, setOf(ExpiryWarning.key(ExpiryWarning.Kind.SOON, e)), ""))
        assertNull(ExpiryWarning.due(at(today), today, zone, emptySet(), today.toString()))
        // a renewed subscription (new expiry) warns again
        assertEquals(ExpiryWarning.Kind.SOON, ExpiryWarning.due(e + 86400, today, zone, setOf(ExpiryWarning.key(ExpiryWarning.Kind.SOON, e)), ""))
    }
}
