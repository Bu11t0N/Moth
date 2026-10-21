package org.mothvpn.core

import kotlin.test.*

class ProbePlanTest {
    private fun p(id: String, name: String) = Profile(id, name, "vless", "{}")
    private val all = listOf(p("1", "Germany 1"), p("2", "Netherlands 1"), p("3", "Germany 2"), p("4", "Finland 1"))
    private val excludeDe = ClientSettings().withFilter("wifi", ServerFilter.Rule(listOf("Germany"), ServerFilter.EXCLUDE))
    private val onlyDe = ClientSettings().withFilter("wifi", ServerFilter.Rule(listOf("Germany"), ServerFilter.ONLY))

    @Test fun checkAllSkipsServersTheFilterHides() {
        // The reported bug: with Germany excluded, "Check all" measured the shown servers first and then every German one.
        assertEquals(listOf("2", "4"), ProbePlan.targets(all, excludeDe, "wifi").map { it.id })
        assertEquals(listOf("2", "4"), ProbePlan.targets(all, excludeDe, "wifi", only = setOf("2", "4")).map { it.id })
    }
    @Test fun filterChangeDuringTheCheckDoesNotExtendIt() {
        // The list is taken once at the start; switching the filter to "only Germany" later must not add the German servers.
        val snapshot = ProbePlan.targets(all, excludeDe, "wifi").map { it.id }
        val later = onlyDe // filter changed while checking
        val stillChecked = ProbePlan.targets(all, later, "wifi", only = snapshot.toSet()).map { it.id }
        assertTrue(stillChecked.none { it in setOf("1", "3") })
        assertEquals(listOf("2", "4"), snapshot)
    }
    @Test fun subscriptionCheckIsNarrowedByTheFilterToo() {
        assertEquals(listOf("2"), ProbePlan.targets(all, excludeDe, "wifi", only = setOf("1", "2", "3")).map { it.id })
        assertTrue(ProbePlan.targets(all, excludeDe, "wifi", only = setOf("1", "3")).isEmpty()) // never falls back to hidden servers
    }
    @Test fun eachNetworkPageUsesItsOwnFilter() {
        assertEquals(all, ProbePlan.targets(all, excludeDe, "mobile")) // the mobile page has no filter
        assertEquals(all, ProbePlan.targets(all, ClientSettings(), "wifi"))
    }
    @Test fun singleServer() {
        assertEquals(listOf("3"), ProbePlan.targets(all, excludeDe, "wifi", id = "3").map { it.id })
    }
}
