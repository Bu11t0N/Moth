package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveConnectionsTest {
    @Test fun portsAndHosts() {
        assertEquals(443, LiveConnections.port("1.2.3.4:443"))
        assertEquals(53, LiveConnections.port("[2001:db8::1]:53"))
        assertNull(LiveConnections.port("[2001:db8::1]"))
        assertNull(LiveConnections.port("example.org"))
        assertEquals("1.2.3.4", LiveConnections.host("1.2.3.4:443"))
        assertEquals("2001:db8::1", LiveConnections.host("[2001:db8::1]:53"))
    }

    @Test fun targetPrefersDomain() {
        assertEquals("example.org:443", LiveConnections.target("example.org", "1.2.3.4:443"))
        assertEquals("1.2.3.4:443", LiveConnections.target("", "1.2.3.4:443"))
        assertEquals("[2001:db8::1]:53", LiveConnections.target("", "[2001:db8::1]:53"))
    }

    @Test fun labelsAndRates() {
        assertEquals("TCP · TLS", LiveConnections.protocolLabel("tcp", "tls"))
        assertEquals("UDP", LiveConnections.protocolLabel("udp", ""))
        assertEquals(2048, LiveConnections.perSecond(2048, 1000))
        assertEquals(1024, LiveConnections.perSecond(2048, 2000))
        assertEquals(0, LiveConnections.perSecond(-5, 1000))
    }

    @Test fun sorting() {
        fun item(id: String, at: Long, total: Long, speed: Long) = LiveConnections.Item(id, "tcp", "", "", "", "", "", at, speed, 0, total, 0, null)
        val list = listOf(item("a", 1, 10, 5), item("b", 3, 1, 0), item("c", 2, 100, 50))
        assertEquals(listOf("b", "c", "a"), LiveConnections.sorted(list, LiveConnections.Sort.NEWEST).map { it.id })
        assertEquals(listOf("c", "a", "b"), LiveConnections.sorted(list, LiveConnections.Sort.TRAFFIC).map { it.id })
        assertEquals(listOf("c", "a", "b"), LiveConnections.sorted(list, LiveConnections.Sort.SPEED).map { it.id })
    }
}
