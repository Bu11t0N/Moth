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
    @Test fun durationFormat() {
        assertEquals("00:00", TrafficFormat.duration(0))
        assertEquals("00:05", TrafficFormat.duration(5))
        assertEquals("12:34", TrafficFormat.duration(754))
        assertEquals("59:59", TrafficFormat.duration(3599))
        assertEquals("1:00:00", TrafficFormat.duration(3600))
        assertEquals("1:02:03", TrafficFormat.duration(3723))
        assertEquals("100:00:01", TrafficFormat.duration(360001))
        assertEquals("00:00", TrafficFormat.duration(-3))
    }
    @Test fun connectedStatsShowTheFullLayoutWithZeros() {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        val c = TrafficFormat.Stats.CONNECTED
        assertEquals(listOf("0 B", "0 B", "0 B", "0 B", "00:00"),
            listOf(TrafficFormat.bytes(c.txRate, units), TrafficFormat.bytes(c.rxRate, units), TrafficFormat.bytes(c.txTotal, units), TrafficFormat.bytes(c.rxTotal, units), TrafficFormat.duration(c.seconds)))
    }
    @Test fun sampleComputesSpeedTotalsAndTime() {
        // Connected at 10 000 ms with counters 1000/5000; previous sample at 11 000 ms, this one at 12 000 ms.
        val s = TrafficFormat.Stats.sample(baseTx = 1000, baseRx = 5000, lastTx = 1500, lastRx = 6000, tx = 3548, rx = 7024, startedAt = 10_000, lastAt = 11_000, at = 12_000)
        assertEquals(TrafficFormat.Stats(txRate = 2048, rxRate = 1024, txTotal = 2548, rxTotal = 2024, seconds = 2), s)
        // A first tick right at connect time gives the same zeros as CONNECTED.
        assertEquals(TrafficFormat.Stats.CONNECTED, TrafficFormat.Stats.sample(7, 9, 7, 9, 7, 9, 500, 500, 500))
        // Counter reset (lower than the base): never negative.
        assertEquals(0, TrafficFormat.Stats.sample(100, 100, 100, 100, 50, 50, 0, 0, 1000).txTotal)
    }
}
