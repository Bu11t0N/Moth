package org.mothvpn.core

import java.time.LocalDate
import java.time.ZoneId

/** Android-independent helpers for Settings › Data › App data usage and Battery (aggregation, periods, units). */
object UsageStatsMath {
    /** One app's (uid's) traffic in a period. */
    data class UidTraffic(val uid: Int, val rx: Long, val tx: Long) { val total get() = rx + tx }

    /** Sums the per-bucket rows of each uid (several buckets per uid, Wi-Fi and mobile), drops the ones without traffic, biggest first. */
    fun aggregate(rows: List<UidTraffic>): List<UidTraffic> =
        rows.groupBy { it.uid }.map { (uid, list) -> UidTraffic(uid, list.sumOf { it.rx.coerceAtLeast(0) }, list.sumOf { it.tx.coerceAtLeast(0) }) }
            .filter { it.total > 0 }.sortedWith(compareByDescending<UidTraffic> { it.total }.thenBy { it.uid })

    /** Start of a period of [days] calendar days ending today (1 = since midnight), in epoch ms. */
    fun periodStart(days: Int, today: LocalDate, zone: ZoneId): Long =
        today.minusDays((days.coerceAtLeast(1) - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Android's battery counters are milliamp-milliseconds; mAh for display. */
    fun mAh(mAms: Long): Double = mAms.coerceAtLeast(0) / 3_600_000.0

    /** Share of [part] in [whole] in percent, one decimal place worth of precision; 0 when [whole] is not positive. */
    fun percent(part: Long, whole: Long): Double = if (whole <= 0) 0.0 else (part.coerceAtLeast(0) * 100.0 / whole).coerceIn(0.0, 100.0)
}
