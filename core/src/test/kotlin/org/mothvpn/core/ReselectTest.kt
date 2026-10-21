package org.mothvpn.core

import org.mothvpn.core.Reselect.Decision
import org.mothvpn.core.Reselect.Reason
import org.mothvpn.core.Reselect.StartPlan
import kotlin.test.*

class ReselectTest {
    @Test fun failoverSwitchesOnlyToTheFastestServerThatAnswered() {
        assertEquals(Decision.Switch("b"), Reselect.decide(Reason.FAILOVER, "a", listOf("b", "c")))
        // The dead current server is skipped even when the probe list still has it first.
        assertEquals(Decision.Switch("c"), Reselect.decide(Reason.FAILOVER, "a", listOf("a", "c")))
    }
    @Test fun nothingAliveMeansStayOnTheCurrentServer() {
        assertEquals(Decision.Stay, Reselect.decide(Reason.FAILOVER, "a", emptyList()))
        assertEquals(Decision.Stay, Reselect.decide(Reason.RESELECT, "a", emptyList()))
    }
    @Test fun onlyTheCurrentServerAnsweringRestartsItsCoreOnFailover() {
        assertEquals(Decision.Switch("a"), Reselect.decide(Reason.FAILOVER, "a", listOf("a")))
    }
    @Test fun aRecoveredServerIsKept() {
        assertEquals(Decision.Keep, Reselect.decide(Reason.FAILOVER, "a", listOf("b"), recovered = true))
    }
    @Test fun reselectKeepsTheCurrentServerWhenItIsTheBest() {
        assertEquals(Decision.Keep, Reselect.decide(Reason.RESELECT, "a", listOf("a", "b")))
        assertEquals(Decision.Switch("b"), Reselect.decide(Reason.RESELECT, "a", listOf("b", "a")))
    }
    @Test fun neverSwitchesToAServerThatDidNotAnswer() {
        val ids = listOf("a", "b", "c", "d")
        fun subsets(l: List<String>): List<List<String>> = if (l.isEmpty()) listOf(emptyList()) else subsets(l.drop(1)).flatMap { listOf(it, listOf(l[0]) + it) }
        for (reason in Reason.values()) for (current in ids) for (alive in subsets(ids)) for (recovered in listOf(false, true)) {
            val d = Reselect.decide(reason, current, alive, recovered)
            if (d is Decision.Switch) assertTrue(d.id in alive, "$reason current=$current alive=$alive -> $d")
            if (alive.isEmpty()) assertTrue(d !is Decision.Switch, "$reason with nothing alive must not switch")
        }
    }
    @Test fun aTunnelThatIsUpIsNeverClosedToMeasure() {
        for (auto in listOf(false, true)) for (pick in listOf(false, true)) for (n in 0..3)
            assertNotEquals(StartPlan.MEASURE_THEN_CONNECT, Reselect.startPlan(tunnelUp = true, autoSelect = auto, pickBest = pick, servers = n))
        assertEquals(StartPlan.MEASURE_WHILE_UP, Reselect.startPlan(true, true, true, 3))
        // From off (nothing to keep) the initial auto-select measures before the tunnel exists, as before.
        assertEquals(StartPlan.MEASURE_THEN_CONNECT, Reselect.startPlan(false, true, true, 3))
        assertEquals(StartPlan.CONNECT, Reselect.startPlan(false, true, true, 1))
        assertEquals(StartPlan.CONNECT, Reselect.startPlan(true, false, true, 3))
        assertEquals(StartPlan.CONNECT, Reselect.startPlan(true, true, false, 3))
    }
    @Test fun quickCandidatesSkipTheCurrentTriedAndFilteredServers() {
        assertEquals(listOf("c", "e"), Reselect.quickCandidates(listOf("a", "b", "c", "d", "e", "f"), "a", setOf("b"), setOf("a", "b", "c", "e", "f"), 2))
        assertEquals(emptyList(), Reselect.quickCandidates(listOf("a"), "a", emptySet(), setOf("a"), 5))
    }
    @Test fun emptyRoundsBackOff() {
        assertEquals(listOf(0L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (0..6).map { Reselect.backoffMs(it) })
        assertTrue(Reselect.mayMeasure(100, 100)); assertFalse(Reselect.mayMeasure(99, 100))
    }
    @Test fun onlyFreshSuccessfulResultsCountAsAlive() {
        assertTrue(Reselect.alive(42, false, false, true, 1000, 1000))
        assertFalse(Reselect.alive(null, false, false, true, 1000, 1000)) // failed
        assertFalse(Reselect.alive(42, true, false, true, 1000, 1000)) // still checking
        assertFalse(Reselect.alive(42, false, true, true, 1000, 1000)) // cancelled
        assertFalse(Reselect.alive(42, false, false, false, 1000, 1000)) // another network
        assertFalse(Reselect.alive(42, false, false, true, 999, 1000)) // older than this measurement (restored result)
    }
    /** A dead current server and a list where every other server is dead too: repeated rounds stay, back off and never switch. */
    @Test fun failoverRoundsWithEverythingDeadStayPut() {
        var rounds = 0; var notBefore = 0L; var now = 0L; var measurements = 0
        repeat(40) {
            now += 30_000 // a failed health check every 30 s
            if (!Reselect.mayMeasure(now, notBefore)) return@repeat
            measurements++
            val d = Reselect.decide(Reason.FAILOVER, "a", alive = emptyList())
            assertEquals(Decision.Stay, d)
            rounds++; notBefore = now + Reselect.backoffMs(rounds)
        }
        assertTrue(measurements in 5..12, "measurements=$measurements")
    }
}
