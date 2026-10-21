package org.mothvpn.core

import kotlin.test.*

/** A server or balancer picked by hand while connected is kept while it answers and replaced by failover once it is dead. */
class ManualPickTest {
    private val after = 3
    private val plain = listOf("a", "b", "c")

    @Test fun manualPickAliveIsNotSwitched() {
        // Health checks pass: no failover, even with a faster server around.
        assertFalse(Reselect.failoverDue(autoSelect = true, failures = 0, after = after))
        assertFalse(Reselect.failoverDue(autoSelect = true, failures = after - 1, after = after))
        // No auto-select reshuffle on a network change either.
        val d = NetworkServerChoice.decide("wifi", "mobile", "picked", null, setOf("picked", "a", "b"), autoSelect = true, manualPick = true)
        assertNotEquals<NetworkServerChoice.Decision>(NetworkServerChoice.Decision.AutoSelect, d)
        assertEquals(NetworkServerChoice.Decision.AutoSelect, NetworkServerChoice.decide("wifi", "mobile", "picked", null, setOf("picked", "a", "b"), autoSelect = true, manualPick = false))
    }

    @Test fun manualPickDeadSwitchesToALivePlainServer() {
        assertTrue(Reselect.failoverDue(autoSelect = true, failures = after, after = after))
        assertEquals(Reselect.Decision.Switch("b"), Reselect.decide(Reselect.Reason.FAILOVER, "picked", listOf("b", "a")))
        // A balancer picked by hand is replaced the same way; balancers are never candidates.
        val group = Profile("g", "Fastest", "vless · balancer", """{"group":true,"outbounds":[]}""", "xray")
        val pool = ProbePlan.autoPool(listOf(group) + plain.map { Profile(it, it, "vless", "{}") }, ClientSettings(), "wifi").map { it.id }
        assertEquals(plain, pool)
        assertEquals(Reselect.Decision.Switch("c"), Reselect.decide(Reselect.Reason.FAILOVER, "g", listOf("c").filter { it in pool }))
    }

    @Test fun manualPickDeadAndNothingAliveStaysWithBackoff() {
        assertEquals(Reselect.Decision.Stay, Reselect.decide(Reselect.Reason.FAILOVER, "picked", emptyList()))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (1..6).map(Reselect::backoffMs))
    }

    @Test fun autoSelectOffNeverSwitches() {
        assertFalse(Reselect.failoverDue(autoSelect = false, failures = 100, after = after))
    }
}
