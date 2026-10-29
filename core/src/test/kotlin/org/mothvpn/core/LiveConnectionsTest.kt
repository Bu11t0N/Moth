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

    @Test fun grouping() {
        fun item(id: String, app: String?, domain: String, at: Long, total: Long, speed: Long) =
            LiveConnections.Item(id, "tcp", "", "", "9.9.9.9:443", domain, "", at, speed, 0, total, 0, app)
        val list = listOf(
            item("1", "com.android.vending", "play.googleapis.com", 1, 10, 0),
            item("2", "com.android.vending", "play.googleapis.com", 5, 20, 1),
            item("3", "com.android.vending", "", 2, 30, 0),
            item("4", "org.telegram", "t.me", 3, 500, 0),
            item("5", null, "", 4, 1, 100),
        )
        val byApp = LiveConnections.grouped(list, LiveConnections.GroupBy.APP, LiveConnections.Sort.NEWEST)
        assertEquals(listOf("com.android.vending", "", "org.telegram"), byApp.map { it.key })
        assertEquals(listOf("2", "3", "1"), byApp[0].items.map { it.id })
        assertEquals(60, byApp[0].total)
        assertEquals(listOf("org.telegram", "com.android.vending", ""),
            LiveConnections.grouped(list, LiveConnections.GroupBy.APP, LiveConnections.Sort.TRAFFIC).map { it.key })
        assertEquals(listOf("", "com.android.vending", "org.telegram"),
            LiveConnections.grouped(list, LiveConnections.GroupBy.APP, LiveConnections.Sort.SPEED).map { it.key })
        val byHost = LiveConnections.grouped(list, LiveConnections.GroupBy.HOST, LiveConnections.Sort.TRAFFIC)
        assertEquals(listOf("t.me", "9.9.9.9", "play.googleapis.com"), byHost.map { it.key })
        assertEquals(listOf(1, 2, 2), byHost.map { it.items.size })
        assertEquals(5, LiveConnections.grouped(list, LiveConnections.GroupBy.NONE, LiveConnections.Sort.NEWEST).size)
    }
}
