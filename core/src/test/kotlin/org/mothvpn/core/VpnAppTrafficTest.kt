package org.mothvpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VpnAppTrafficTest {
    @Test fun ownerKeysMatchCoreSources() {
        assertEquals(VpnAppTraffic.ownerKey("tcp", "172.19.0.1", 43210), VpnAppTraffic.ownerKey("tcp", "172.19.0.1:43210"))
        assertEquals(VpnAppTraffic.ownerKey("udp", "fdfe:dcba:9876::1", 5353), VpnAppTraffic.ownerKey("udp", "[fdfe:dcba:9876::1]:5353"))
        assertEquals("udp", VpnAppTraffic.network(17)); assertEquals("tcp", VpnAppTraffic.network(6))
        assertNull(VpnAppTraffic.ownerKey("tcp", "garbage"))
    }
    @Test fun deltasCountEachByteOnce() {
        val d = VpnAppTraffic.Deltas()
        assertEquals(listOf(10L, 100L), d.update("a", 10, 100, false)!!.toList())
        assertEquals(listOf(5L, 0L), d.update("a", 15, 100, false)!!.toList())
        assertEquals(listOf(1L, 50L), d.update("a", 16, 150, true)!!.toList())
        assertNull(d.update("a", 16, 150, true))
        assertEquals(listOf(3L, 4L), d.update("b", 3, 4, true)!!.toList()) // short connection seen only after it closed
    }
    @Test fun ledgerSumsByPeriodAndNetworkAndSurvivesJson() {
        val l = VpnAppTraffic.Ledger(keepDays = 31)
        l.add(100, "wifi", "app.a", 10, 90); l.add(100, "mobile", "app.a", 1, 9); l.add(99, "wifi", "app.b", 500, 500)
        assertEquals(listOf(VpnAppTraffic.AppBytes("app.a", 11, 99)), l.query(100, 100))
        assertEquals(listOf(VpnAppTraffic.AppBytes("app.a", 1, 9)), l.query(100, 100, "mobile"))
        assertEquals("app.b", l.query(94, 100).first().app)
        val copy = VpnAppTraffic.Ledger().apply { load(l.toJson()) }
        assertEquals(l.query(0, 200), copy.query(0, 200))
        l.add(140, "wifi", "app.c", 1, 1) // older days than 31 are dropped
        assertEquals(listOf("app.c"), l.query(0, 200).map { it.app })
    }
}
