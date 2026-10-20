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

    @Test fun eachNetworkHasItsOwnFilterAndBothPersist() {
        val d = ClientSettings.from(ClientSettings().json().apply { remove("serverFilterWifi"); remove("serverFilterMobile") })
        assertEquals(ServerFilter.Rule(), d.wifiFilter); assertEquals(ServerFilter.Rule(), d.mobileFilter)
        val s = ClientSettings().withFilter("wifi", ServerFilter.Rule(listOf("Germany"), ServerFilter.ONLY)).withFilter("mobile", ServerFilter.Rule(listOf("gaming")))
        assertEquals(listOf("Germany"), s.filterFor("wifi").keywords); assertEquals(listOf("gaming"), s.filterFor("mobile").keywords)
        assertEquals(ServerFilter.ONLY, s.wifiFilter.mode); assertEquals(ServerFilter.EXCLUDE, s.mobileFilter.mode)
        assertEquals(s, ClientSettings.from(s.json()))
        val broken = s.json().put("serverFilterMobile", org.json.JSONObject().put("keywords", org.json.JSONArray(listOf(" a ", "A", "", "b"))).put("mode", "weird"))
        assertEquals(ServerFilter.Rule(listOf("a", "b")), ClientSettings.from(broken).mobileFilter)
    }

    @Test fun theBeta4SingleFilterIsMigratedToBothNetworks() {
        val old = ClientSettings().json().apply { remove("serverFilterWifi"); remove("serverFilterMobile") }
            .put("serverFilter", org.json.JSONArray(listOf("Germany", "gaming"))).put("serverFilterMode", "only")
        val migrated = ClientSettings.from(old)
        val rule = ServerFilter.Rule(listOf("Germany", "gaming"), ServerFilter.ONLY)
        assertEquals(rule, migrated.wifiFilter); assertEquals(rule, migrated.mobileFilter)
        assertFalse(migrated.json().has("serverFilter"))
    }

    @Test fun logShowsOnlyTheKeywordCount() {
        val changes = ClientSettings().let { LogFacts.settingsChanges(it, it.withFilter("mobile", ServerFilter.Rule(listOf("SecretProvider")))) }
        assertEquals(listOf("mobileFilter=0->1"), changes)
        assertFalse(changes.joinToString().contains("SecretProvider"))
    }

    private fun p(name: String) = Profile(name.lowercase().hashCode().toUInt().toString(16).padStart(16, '0'), name, "vless", "{}")

    @Test fun autoSelectPoolSkipsServersHiddenByTheCurrentNetworksFilter() {
        val all = listOf(p("Germany 1"), p("Netherlands"), p("Germany 2"))
        val nd = ClientSettings().withFilter("wifi", ServerFilter.Rule(listOf("germany")))
        assertEquals(listOf("Netherlands"), ServerFilter.pool(all, nd, "wifi").map { it.name })
        // The mobile data page has no keywords: every server is a candidate there.
        assertEquals(all, ServerFilter.pool(all, nd, "mobile"))
        assertEquals(listOf("Germany 1", "Germany 2"), ServerFilter.pool(all, nd.withFilter("mobile", ServerFilter.Rule(listOf("germany"), ServerFilter.ONLY)), "mobile").map { it.name })
        // Ranking and failover only ever see pool ids, so a hidden server can never be next.
        val ids = ServerFilter.pool(all, nd, "wifi").map { it.id }
        val ranking = ServerPicker.ranking(ids, all.associate { it.id to 50L })
        assertEquals(listOf(all[1].id), ranking)
        assertEquals(null, ServerPicker.next(ranking, all[1].id, emptySet()))
    }

    @Test fun poolIsEverythingWithoutAFilter() {
        val all = listOf(p("Germany 1"), p("Netherlands"))
        assertEquals(all, ServerFilter.pool(all, ClientSettings(), "wifi"))
    }

    @Test fun poolIsEmptyWhenTheFilterHidesEverything() {
        val all = listOf(p("Germany 1"), p("Germany 2"))
        assertTrue(ServerFilter.pool(all, ClientSettings().withFilter("mobile", ServerFilter.Rule(listOf("germany"))), "mobile").isEmpty())
    }
}
