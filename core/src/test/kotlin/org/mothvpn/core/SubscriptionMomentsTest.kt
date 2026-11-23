package org.mothvpn.core

import kotlin.test.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class SubscriptionMomentsTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val london = ZoneId.of("Europe/London")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId = moscow): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toEpochSecond()

    @Test fun expiryTimeIsShownOnlyWhenTheTimeOfDayIsNotMidnight() {
        assertFalse(SubscriptionMoments.expiryHasTime(0, moscow))
        assertFalse(SubscriptionMoments.expiryHasTime(at(2026, 11, 17, 0, 0), moscow))
        assertTrue(SubscriptionMoments.expiryHasTime(at(2026, 11, 17, 0, 0, 1), moscow))
        assertTrue(SubscriptionMoments.expiryHasTime(at(2026, 11, 17, 15, 30), moscow))
        assertTrue(SubscriptionMoments.expiryHasTime(at(2026, 11, 17, 23, 59, 59), moscow))
        // A midnight in another zone can be a real time locally.
        val utcMidnight = at(2026, 11, 17, 0, 0, zone = ZoneId.of("UTC"))
        assertTrue(SubscriptionMoments.expiryHasTime(utcMidnight, moscow))
        assertFalse(SubscriptionMoments.expiryHasTime(utcMidnight, ZoneId.of("UTC")))
    }

    @Test fun formatExpiryOmitsTimeAtMidnightAndIncludesItOtherwise() {
        val midnight = at(2026, 11, 17, 0, 0)
        val afternoon = at(2026, 11, 17, 15, 30)
        val dateOnly = SubscriptionMoments.formatExpiry(midnight, Locale.UK, moscow, hour24 = true, allowTime = true)
        val withTime = SubscriptionMoments.formatExpiry(afternoon, Locale.UK, moscow, hour24 = true, allowTime = true)
        val servers = SubscriptionMoments.formatExpiry(afternoon, Locale.UK, moscow, hour24 = true, allowTime = false)
        assertNotNull(dateOnly); assertNotNull(withTime); assertNotNull(servers)
        assertFalse(dateOnly!!.contains("00:00"), dateOnly)
        assertTrue(withTime!!.contains("15:30"), withTime)
        assertFalse(servers!!.contains("15:30"), servers)
        assertNull(SubscriptionMoments.formatExpiry(0, Locale.UK, moscow, hour24 = true, allowTime = true))
    }

    @Test fun timeFollowsThe24HourSetting() {
        val afternoon = at(2026, 11, 17, 15, 30) * 1000
        val en24 = SubscriptionMoments.format(afternoon, Locale.US, moscow, withTime = true, hour24 = true)
        val en12 = SubscriptionMoments.format(afternoon, Locale.US, moscow, withTime = true, hour24 = false)
        val ru24 = SubscriptionMoments.format(afternoon, Locale.forLanguageTag("ru"), moscow, withTime = true, hour24 = true)
        assertTrue(en24.contains("15:30"), en24)
        assertFalse(en24.contains("PM") || en24.contains("pm"), en24)
        assertTrue(en12.contains("3:30") && (en12.contains("PM") || en12.contains("pm")), en12)
        assertTrue(ru24.contains("15:30"), ru24)
        val dateOnly = SubscriptionMoments.format(afternoon, Locale.US, moscow, withTime = false, hour24 = true)
        assertFalse(dateOnly.contains("15"), dateOnly)
    }

    @Test fun adjustHourRewritesPatternsAndLeavesQuotedText() {
        assertEquals("d MMM y, HH:mm", SubscriptionMoments.adjustHour("d MMM y, hh:mm a", true))
        assertEquals("d MMM y, h:mm a", SubscriptionMoments.adjustHour("d MMM y, H:mm", false))
        assertEquals("'hour' HH:mm", SubscriptionMoments.adjustHour("'hour' hh:mm a", true))
        assertEquals("HH:mm", SubscriptionMoments.adjustHour("hh:mm a", true))
    }

    @Test fun londonMidnightIsDateOnlyInLondon() {
        val sec = at(2026, 6, 1, 0, 0, zone = london)
        assertFalse(SubscriptionMoments.expiryHasTime(sec, london))
        val text = SubscriptionMoments.formatExpiry(sec, Locale.UK, london, hour24 = true, allowTime = true)!!
        assertFalse(text.contains(":"), text)
    }
}
