package org.mothvpn.core

import java.util.Locale

/** Android-independent text helpers for the VPN notification: byte counts, speed and uptime. */
object TrafficFormat {
    /** "512 B", "120 KB", "1.2 MB" (1024-based); [units] are the labels for B, KB, MB, GB, TB. */
    fun bytes(value: Long, units: List<String>, locale: Locale = Locale.ROOT): String {
        require(units.size >= 2)
        var v = value.coerceAtLeast(0).toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${v.toLong()} ${units[0]}"
        else String.format(locale, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }
    /** Bytes per second between two samples [elapsedMs] apart; never negative. */
    fun rate(previous: Long, current: Long, elapsedMs: Long): Long =
        if (elapsedMs <= 0 || current < previous) 0 else (current - previous) * 1000 / elapsedMs
    /** "00:12:34", hours keep growing past 99. */
    fun uptime(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}
