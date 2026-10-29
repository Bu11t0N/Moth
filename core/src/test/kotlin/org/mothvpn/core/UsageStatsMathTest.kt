package org.mothvpn.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageStatsMathTest {
    @Test fun aggregatesByUidDropsEmptyAndSortsBiggestFirst() {
        val rows = listOf(UsageStatsMath.UidTraffic(10100, 100, 10), UsageStatsMath.UidTraffic(10200, 5, 5), UsageStatsMath.UidTraffic(10100, 50, 0),
            UsageStatsMath.UidTraffic(10300, 0, 0), UsageStatsMath.UidTraffic(10400, 500, 1))
        assertEquals(listOf(UsageStatsMath.UidTraffic(10400, 500, 1), UsageStatsMath.UidTraffic(10100, 150, 10), UsageStatsMath.UidTraffic(10200, 5, 5)),
            UsageStatsMath.aggregate(rows))
    }
    @Test fun periodStartsAtMidnight() {
        val zone = ZoneId.of("Europe/Moscow"); val today = LocalDate.of(2026, 10, 29)
        assertEquals(today.atStartOfDay(zone).toInstant().toEpochMilli(), UsageStatsMath.periodStart(1, today, zone))
        assertEquals(LocalDate.of(2026, 10, 23).atStartOfDay(zone).toInstant().toEpochMilli(), UsageStatsMath.periodStart(7, today, zone))
        assertEquals(LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli(), UsageStatsMath.periodStart(30, today, zone))
    }
    @Test fun unitsAndPercent() {
        assertEquals(1.0, UsageStatsMath.mAh(3_600_000))
        assertEquals(0.0, UsageStatsMath.mAh(-5))
        assertEquals(25.0, UsageStatsMath.percent(1, 4))
        assertEquals(0.0, UsageStatsMath.percent(1, 0))
        assertEquals(100.0, UsageStatsMath.percent(10, 4))
    }
}
