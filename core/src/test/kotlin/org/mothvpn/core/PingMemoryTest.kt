package org.mothvpn.core

import kotlin.test.*

class PingMemoryTest {
    private val a = PingMemory.Entry("wifi:1", 120, null, "HTTPS", 1000, 42)
    private val b = PingMemory.Entry("mobile:1", null, "TIMEOUT", "TCP", 2000, 7)

    @Test fun roundTripKeepsEachPage() {
        val back = PingMemory.decode(PingMemory.encode(listOf(a, b)))
        assertEquals(setOf(a, b), back.toSet()) // the Wi-Fi and the mobile page keep their own values
    }
    @Test fun noExpiry() {
        val old = a.copy(checkedAt = 1) // decades old: still kept
        assertEquals(listOf(old), PingMemory.decode(PingMemory.encode(listOf(old))))
    }
    @Test fun badInputIsIgnored() {
        assertTrue(PingMemory.decode("").isEmpty())
        assertTrue(PingMemory.decode("garbage\nx\ty").isEmpty())
        assertEquals(listOf(a), PingMemory.decode(PingMemory.encode(listOf(a)) + "broken line\n\tx\t\t\t\t\n"))
    }
    @Test fun capKeepsNewest() {
        val many = (0 until PingMemory.MAX + 10).map { a.copy(key = "wifi:$it", checkedAt = it.toLong()) }
        val back = PingMemory.decode(PingMemory.encode(many))
        assertEquals(PingMemory.MAX, back.size)
        assertTrue(back.none { it.checkedAt < 10 })
    }
    @Test fun onlyFinishedResultsAreKept() {
        assertTrue(PingMemory.keeps(false, false, 80, false))
        assertTrue(PingMemory.keeps(false, false, null, true))
        assertFalse(PingMemory.keeps(true, false, null, false))
        assertFalse(PingMemory.keeps(false, true, 80, false))
        assertFalse(PingMemory.keeps(false, false, null, false))
    }
}
