package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class ServerOrderTest {
    private val names = listOf("b", "Server 10", "server 2", "A", "a1")
    @Test fun nameOrderIsCaseInsensitiveAndNatural() {
        assertEquals(listOf("A", "a1", "b", "server 2", "Server 10"), ServerOrder.sort(names, ServerOrder.NAME, { it }, { null }))
    }
    @Test fun latencyOrderPutsUnmeasuredLastAndKeepsSubscriptionOrderInTies() {
        val ping = mapOf("b" to 50L, "A" to 50L, "server 2" to 10L)
        assertEquals(listOf("server 2", "b", "A", "Server 10", "a1"), ServerOrder.sort(names, ServerOrder.LATENCY, { it }, { ping[it] }))
    }
    @Test fun subscriptionOrderKeepsTheGivenOrder() {
        assertEquals(names, ServerOrder.sort(names, ServerOrder.SUBSCRIPTION, { it }, { 1L }))
    }
    @Test fun nameOrderIgnoresLeadingFlagsAndEmoji() {
        val list = listOf("\uD83C\uDDEA\uD83C\uDDFA Netherlands 2", "\uD83C\uDDE9\uD83C\uDDEA Germany", "\uD83C\uDDEA\uD83C\uDDFA Austria",
            "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 Rainbow", "  - Finland", "\u2B50\uFE0F Zurich", "\uD83C\uDDF3\uD83C\uDDF1 Netherlands 10", "Belgium")
        assertEquals(listOf("\uD83C\uDDEA\uD83C\uDDFA Austria", "Belgium", "  - Finland", "\uD83C\uDDE9\uD83C\uDDEA Germany",
            "\uD83C\uDDEA\uD83C\uDDFA Netherlands 2", "\uD83C\uDDF3\uD83C\uDDF1 Netherlands 10", "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 Rainbow", "\u2B50\uFE0F Zurich"),
            ServerOrder.sort(list, ServerOrder.NAME, { it }, { null }))
        assertEquals("Austria", ServerOrder.sortKey("\uD83C\uDDEA\uD83C\uDDFA Austria"))
    }
    @Test fun equalStrippedNamesTieBreakByFullName() {
        val a = "\uD83C\uDDE9\uD83C\uDDEA Server"; val b = "\uD83C\uDDEA\uD83C\uDDFA Server"
        assertEquals(listOf(a, b), ServerOrder.sort(listOf(b, a), ServerOrder.NAME, { it }, { null }))
        assertEquals(listOf(a, b), ServerOrder.sort(listOf(a, b), ServerOrder.NAME, { it }, { null }))
        assertTrue(ServerOrder.naturalName.compare(a, b) < 0 && ServerOrder.naturalName.compare(b, a) > 0)
    }
    @Test fun favoritesStayOnTopInSubscriptionOrder() {
        val ids = listOf("c", "a", "b")
        assertEquals(listOf("b", "c", "a"), Favorites.first(ServerOrder.sort(ids, ServerOrder.SUBSCRIPTION, { it }, { null }), setOf("b")) { it })
    }
    @Test fun importAndMultipleSubscriptionsKeepProviderOrder() {
        val u = "11111111-2222-3333-4444-555555555555"
        val body = listOf("Zeta", "Alpha", "Mid").joinToString("\n") { "vless://$u@$it.example.com:443?security=tls&sni=$it.example.com#$it" }
        val first = Subscriptions.parse(body).profiles
        assertEquals(listOf("Zeta", "Alpha", "Mid"), first.map { it.name })
        val second = Subscriptions.parse(listOf("Yak", "Bee").joinToString("\n") { "vless://$u@$it.example.org:443?security=tls&sni=$it.example.org#$it" }).profiles
        val book = SubscriptionBook(listOf(SubscriptionSource("s1", "", "https://one.example/sub", first), SubscriptionSource("s2", "", "https://two.example/sub", second)))
        val expected = listOf("Zeta", "Alpha", "Mid", "Yak", "Bee")
        assertEquals(expected, book.profiles.map { it.name })
        assertEquals(expected, SubscriptionBook.from(book.json()).profiles.map { it.name })
        assertEquals(expected, ServerOrder.sort(book.profiles, ServerOrder.SUBSCRIPTION, { it.name }, { null }).map { it.name })
    }
    @Test fun settingPersistsAndDefaultsToSubscription() {
        assertEquals(ServerOrder.SUBSCRIPTION, ClientSettings().serverSort)
        assertEquals(ServerOrder.SUBSCRIPTION, ClientSettings.from(ClientSettings().json()).serverSort)
        assertEquals(ServerOrder.NAME, ClientSettings.from(ClientSettings(serverSort = "name").json()).serverSort)
        assertEquals(ServerOrder.LATENCY, ClientSettings.from(ClientSettings(serverSort = "latency").json()).serverSort)
        assertFails { ClientSettings(serverSort = "x").validate() }
    }
    @Test fun oldSortByLatencyFlagMigrates() {
        fun old(flag: Boolean) = ClientSettings.from(ClientSettings().json().apply { remove("serverSort"); put("sortByLatency", flag) })
        assertEquals(ServerOrder.LATENCY, old(true).serverSort)
        assertEquals(ServerOrder.NAME, old(false).serverSort)
    }
    @Test fun newDesignToggleIsOffByDefaultAndPersists() {
        assertFalse(ClientSettings().newDesign)
        assertFalse(ClientSettings.from(ClientSettings().json().apply { remove("newDesign") }).newDesign)
        assertTrue(ClientSettings.from(ClientSettings(newDesign = true).json()).newDesign)
        assertEquals(listOf("newDesign=false->true"), ClientSettings().let { s -> LogFacts.settingsChanges(s, s.copy(newDesign = true)) })
    }
}
