package org.mothvpn.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Dates and times shown for a subscription: expiry from the provider, and the last update or content change.
 * Time-of-day uses the 24-hour clock when [hour24] is true (the system setting on the device).
 */
object SubscriptionMoments {
    /**
     * Whether an expiry unix timestamp (seconds) has a meaningful time-of-day in [zone]. Midnight is treated as
     * date-only: providers that only send a day often use 00:00:00.
     */
    fun expiryHasTime(epochSeconds: Long, zone: ZoneId): Boolean {
        if (SubscriptionQuota.expiry(epochSeconds) == null) return false
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime() != LocalTime.MIDNIGHT
    }

    /** Formats [epochMillis] in [zone] with a localized date, and a time when [withTime] is true. */
    fun format(epochMillis: Long, locale: Locale, zone: ZoneId, withTime: Boolean, hour24: Boolean): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return DateTimeFormatter.ofPattern(pattern(locale, withTime, hour24), locale).format(zoned)
    }

    /**
     * Expiry line value, or null when the provider sent no date. When [allowTime] is true, the time is included only
     * if [expiryHasTime] is true for [zone].
     */
    fun formatExpiry(epochSeconds: Long, locale: Locale, zone: ZoneId, hour24: Boolean, allowTime: Boolean): String? {
        val sec = SubscriptionQuota.expiry(epochSeconds) ?: return null
        return format(sec * 1000, locale, zone, withTime = allowTime && expiryHasTime(sec, zone), hour24 = hour24)
    }

    /** Localized date (and optional time) pattern, with the hour cycle set by [hour24]. */
    fun pattern(locale: Locale, withTime: Boolean, hour24: Boolean): String {
        val raw = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            FormatStyle.MEDIUM, if (withTime) FormatStyle.SHORT else null, IsoChronology.INSTANCE, locale)
        return if (withTime) adjustHour(raw, hour24) else raw
    }

    /** Rewrites hour letters in a [DateTimeFormatter] pattern so the clock matches [hour24]. Quoted text is left as is. */
    fun adjustHour(pattern: String, hour24: Boolean): String {
        val out = StringBuilder()
        var quote = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                out.append(c)
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') { out.append('\''); i += 2; continue }
                quote = !quote; i++; continue
            }
            if (!quote && c in "aHhKk") {
                var j = i + 1
                while (j < pattern.length && pattern[j] == c) j++
                val n = j - i
                when (c) {
                    'a' -> if (!hour24) out.append("a".repeat(n))
                    'h', 'K' -> out.append((if (hour24) 'H' else 'h').toString().repeat(n))
                    'H', 'k' -> out.append((if (hour24) 'H' else 'h').toString().repeat(n))
                }
                i = j
            } else {
                out.append(c); i++
            }
        }
        var s = out.toString()
        if (hour24) {
            s = s.replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+([,\\.])"), "$1").trim()
        } else if ('a' !in s.replace(Regex("'.*?'"), "")) {
            s = "$s a"
        }
        return s
    }
}
