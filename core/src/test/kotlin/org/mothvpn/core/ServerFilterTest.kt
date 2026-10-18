package org.mothvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFilterTest {
    private val names = listOf("🇩🇪 Germany 1", "🇳🇱 Netherlands", "Russia bypass", "germany-2 (gaming)")

    @Test fun excludeHidesMatchingNamesIgnoringCase() {
        assertEquals(listOf("🇳🇱 Netherlands", "Russia bypass"), names.filter { ServerFilter.shows(it, listOf("GERMANY"), ServerFilter.EXCLUDE) })
    }

    @Test fun onlyShowsMatchingNames() {
        assertEquals(listOf("🇩🇪 Germany 1", "Russia bypass", "germany-2 (gaming)"),
            names.filter { ServerFilter.shows(it, listOf("germany", "russia"), ServerFilter.ONLY) })
    }

    @Test fun noKeywordsShowsEverythingInBothModes() {
        ServerFilter.modes.forEach { mode -> assertTrue(names.all { ServerFilter.shows(it, emptyList(), mode) }) }
    }

    @Test fun addTrimsAndSkipsBlankDuplicateAndTooLong() {
        var k = ServerFilter.add(emptyList(), "  de  ")
        assertEquals(listOf("de"), k)
        k = ServerFilter.add(k, "DE"); k = ServerFilter.add(k, "   "); k = ServerFilter.add(k, "x".repeat(65))
        assertEquals(listOf("de"), k)
        assertEquals(ServerFilter.MAX_KEYWORDS, (1..80).fold(emptyList<String>()) { a, i -> ServerFilter.add(a, "k$i") }.size)
    }

    @Test fun filterPersistsAndDefaultsToExcludeWithNoKeywords() {
        val d = ClientSettings.from(ClientSettings().json().apply { remove("serverFilter"); remove("serverFilterMode") })
        assertEquals(emptyList<String>(), d.serverFilter); assertEquals(ServerFilter.EXCLUDE, d.serverFilterMode)
        val s = ClientSettings(serverFilter = listOf("Germany", "gaming"), serverFilterMode = ServerFilter.ONLY)
        assertEquals(s, ClientSettings.from(s.json()))
        val broken = s.json().put("serverFilter", org.json.JSONArray(listOf(" a ", "A", "", "b"))).put("serverFilterMode", "weird")
        val fixed = ClientSettings.from(broken)
        assertEquals(listOf("a", "b"), fixed.serverFilter); assertEquals(ServerFilter.EXCLUDE, fixed.serverFilterMode)
    }

    @Test fun logShowsOnlyTheKeywordCount() {
        val changes = ClientSettings().let { LogFacts.settingsChanges(it, it.copy(serverFilter = listOf("SecretProvider"))) }
        assertEquals(listOf("serverFilter=0->1"), changes)
        assertFalse(changes.joinToString().contains("SecretProvider"))
    }

    private fun p(name: String) = Profile(name.lowercase().hashCode().toUInt().toString(16).padStart(16, '0'), name, "vless", "{}")

    @Test fun autoSelectPoolSkipsServersHiddenByTheFilter() {
        val all = listOf(p("Germany 1"), p("Netherlands"), p("Germany 2"))
        val nd = ClientSettings(newDesign = true, serverFilter = listOf("germany"))
        assertEquals(listOf("Netherlands"), ServerFilter.pool(all, nd).map { it.name })
        assertEquals(listOf("Germany 1", "Germany 2"), ServerFilter.pool(all, nd.copy(serverFilterMode = ServerFilter.ONLY)).map { it.name })
        // Ranking and failover only ever see pool ids, so a hidden server can never be next.
        val ids = ServerFilter.pool(all, nd).map { it.id }
        val ranking = ServerPicker.ranking(ids, all.associate { it.id to 50L })
        assertEquals(listOf(all[1].id), ranking)
        assertEquals(null, ServerPicker.next(ranking, all[1].id, emptySet()))
    }

    @Test fun poolIsEverythingWithoutAFilterOrInTheClassicDesign() {
        val all = listOf(p("Germany 1"), p("Netherlands"))
        assertEquals(all, ServerFilter.pool(all, ClientSettings(newDesign = true)))
        assertEquals(all, ServerFilter.pool(all, ClientSettings(newDesign = false, serverFilter = listOf("germany"))))
    }

    @Test fun poolIsEmptyWhenTheFilterHidesEverything() {
        val all = listOf(p("Germany 1"), p("Germany 2"))
        assertTrue(ServerFilter.pool(all, ClientSettings(newDesign = true, serverFilter = listOf("germany"))).isEmpty())
    }
}
