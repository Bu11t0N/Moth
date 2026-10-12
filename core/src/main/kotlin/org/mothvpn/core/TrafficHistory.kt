package org.mothvpn.core

import org.json.JSONObject
import java.time.LocalDate

/** VPN traffic per local day ("2026-10-12" -> sent/received bytes), kept for [KEEP_DAYS] days. */
data class TrafficHistory(val days: Map<String, Pair<Long, Long>> = emptyMap()) {
    companion object {
        const val KEEP_DAYS = 90L
        fun from(json: JSONObject?): TrafficHistory {
            if (json == null) return TrafficHistory()
            val out = sortedMapOf<String, Pair<Long, Long>>()
            for (k in json.keys()) {
                if (runCatching { LocalDate.parse(k) }.isFailure) continue
                val v = json.optJSONObject(k) ?: continue
                out[k] = v.optLong("tx").coerceAtLeast(0) to v.optLong("rx").coerceAtLeast(0)
            }
            return TrafficHistory(out)
        }
        /**
         * Bytes since the previous sample of a per-uid counter. A smaller value means the counter was reset
         * (reboot or uid stats cleared): the new value is counted from zero. Unsupported (-1) counts as nothing.
         */
        fun delta(previous: Long, current: Long): Long = when {
            current < 0 || previous < 0 -> 0
            current >= previous -> current - previous
            else -> current
        }
    }
    fun add(day: LocalDate, tx: Long, rx: Long): TrafficHistory {
        if (tx <= 0 && rx <= 0) return this
        val key = day.toString()
        val old = days[key] ?: (0L to 0L)
        return TrafficHistory(days + (key to (old.first + tx.coerceAtLeast(0) to old.second + rx.coerceAtLeast(0))))
    }
    fun prune(today: LocalDate): TrafficHistory {
        val oldest = today.minusDays(KEEP_DAYS - 1)
        return TrafficHistory(days.filterKeys { k -> runCatching { !LocalDate.parse(k).isBefore(oldest) }.getOrDefault(false) })
    }
    private fun sum(filter: (LocalDate) -> Boolean): Pair<Long, Long> = days.entries.filter { filter(LocalDate.parse(it.key)) }
        .fold(0L to 0L) { acc, e -> acc.first + e.value.first to acc.second + e.value.second }
    fun day(date: LocalDate) = days[date.toString()] ?: (0L to 0L)
    fun month(today: LocalDate) = sum { it.year == today.year && it.month == today.month && !it.isAfter(today) }
    fun lastDays(today: LocalDate, count: Int = 30) = sum { !it.isAfter(today) && it.isAfter(today.minusDays(count.toLong())) }
    /** [count] days ending today, oldest first, with zero for days without traffic. */
    fun series(today: LocalDate, count: Int = 30): List<Pair<LocalDate, Pair<Long, Long>>> =
        (count - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to day(it) }
    fun json(): JSONObject = JSONObject().apply { days.toSortedMap().forEach { (k, v) -> put(k, JSONObject().put("tx", v.first).put("rx", v.second)) } }
}

/** Subscription end reminder: 3 days before (once) and on the last day (once), never more than one per day. */
object ExpiryWarning {
    enum class Kind { SOON, TODAY }
    const val SOON_DAYS = 3L
    /**
     * [expires]: epoch seconds from subscription-userinfo (0 = none). [shown]: "<kind>:<expires>" already shown,
     * [lastShownDay]: the day of the last reminder. Returns the reminder to show today, or null.
     */
    fun due(expires: Long, today: LocalDate, zone: java.time.ZoneId, shown: Set<String>, lastShownDay: String): Kind? {
        val end = SubscriptionQuota.expiry(expires) ?: return null
        if (lastShownDay == today.toString()) return null
        val endDay = java.time.Instant.ofEpochSecond(end).atZone(zone).toLocalDate()
        val left = java.time.temporal.ChronoUnit.DAYS.between(today, endDay)
        val kind = when { left == 0L -> Kind.TODAY; left in 1..SOON_DAYS -> Kind.SOON; else -> return null }
        return kind.takeIf { key(it, expires) !in shown }
    }
    fun key(kind: Kind, expires: Long) = "${kind.name}:$expires"
    fun daysLeft(expires: Long, today: LocalDate, zone: java.time.ZoneId): Long =
        java.time.temporal.ChronoUnit.DAYS.between(today, java.time.Instant.ofEpochSecond(expires).atZone(zone).toLocalDate())
}
