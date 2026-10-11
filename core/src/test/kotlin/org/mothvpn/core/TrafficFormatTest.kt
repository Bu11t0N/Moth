package org.mothvpn.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficFormatTest {
    private val units = listOf("B", "KB", "MB", "GB", "TB")
    @Test fun bytesPickTheUnit() {
        assertEquals("0 B", TrafficFormat.bytes(0, units))
        assertEquals("512 B", TrafficFormat.bytes(512, units))
        assertEquals("120 KB", TrafficFormat.bytes(120 * 1024, units))
        assertEquals("1.2 MB", TrafficFormat.bytes((1.2 * 1048576).toLong(), units))
        assertEquals("12.3 MB", TrafficFormat.bytes((12.3 * 1048576).toLong(), units))
        assertEquals("140 MB", TrafficFormat.bytes(140L * 1048576, units))
        assertEquals("2.0 GB", TrafficFormat.bytes(2L * 1073741824, units))
        assertEquals("0 B", TrafficFormat.bytes(-5, units))
    }
    @Test fun russianDecimalComma() = assertEquals("1,5 МБ", TrafficFormat.bytes(1572864, listOf("Б", "КБ", "МБ"), Locale("ru")))
    @Test fun rateIsNeverNegative() {
        assertEquals(1000, TrafficFormat.rate(0, 1000, 1000))
        assertEquals(500, TrafficFormat.rate(0, 1000, 2000))
        assertEquals(0, TrafficFormat.rate(5000, 100, 1000))
        assertEquals(0, TrafficFormat.rate(0, 100, 0))
    }
    @Test fun uptimeFormat() {
        assertEquals("00:12:34", TrafficFormat.uptime(754))
        assertEquals("100:00:01", TrafficFormat.uptime(360001))
        assertEquals("00:00:00", TrafficFormat.uptime(-3))
    }
}
