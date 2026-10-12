package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.mothvpn.core.XrayJsonFixtures as F

class Beta3Test {
    @Test fun dnsPresetsAreIpLabelledDohAndPlainCloudflareMigrates() {
        assertEquals(listOf("Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Quad9 (9.9.9.9)", "AdGuard (94.140.14.14)"), DnsServers.presets.map { it.label })
        DnsServers.presets.forEach { p -> assertTrue(p.address.startsWith("https://") && p.address.contains(p.label.substringAfter('(').removeSuffix(")"))) }
        assertNull(DnsServers.presetOf("1.1.1.1"))
        assertEquals(DnsServers.DEFAULT, ClientSettings.from(ClientSettings().json().put("dns", "1.1.1.1")).dns)
        assertEquals("8.8.4.4", ClientSettings.from(ClientSettings().json().put("dns", "8.8.4.4")).dns) // custom plain DNS is kept
        assertEquals("https://8.8.8.8/dns-query", ClientSettings.from(ClientSettings().json().put("dns", "https://8.8.8.8/dns-query")).dns)
    }
    @Test fun killSwitchAndFavoritesPersist() {
        val d = ClientSettings()
        assertFalse(d.killSwitch); assertTrue(d.favorites.isEmpty())
        val id = "a".repeat(64)
        val s = d.copy(killSwitch = true, favorites = listOf(id))
        assertEquals(s, ClientSettings.from(s.json()))
        val old = d.json().apply { remove("killSwitch"); remove("favorites") }
        assertEquals(d, ClientSettings.from(old))
        assertEquals(listOf(id), ClientSettings.from(d.json().put("favorites", JSONArray(listOf(id, id, "bad id")))).favorites)
        assertFailsWith<IllegalArgumentException> { d.copy(favorites = listOf("x")).validate() }
        assertEquals(listOf("killSwitch=false->true", "favorites=0->1"), LogFacts.settingsChanges(d, s))
    }
    @Test fun favoritesFirstKeepsOrderWithinGroups() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), Favorites.first(items, listOf("c")) { it })
        assertEquals(listOf("a", "c", "b", "d"), Favorites.first(items, listOf("c", "a")) { it })
        assertEquals(items, Favorites.first(items, emptyList()) { it })
        assertEquals(listOf("x", "y"), Favorites.toggle(listOf("x"), "y"))
        assertEquals(listOf("x"), Favorites.toggle(listOf("x", "y"), "y"))
    }
    @Test fun autoSelectReusesFreshResultsOnly() {
        val now = 1_000_000_000L
        val fresh = mapOf("a" to (120L to now - 1000), "b" to (null to now - 2000), "c" to (80L to now - 60_000))
        assertEquals(listOf("c", "a"), ServerPicker.recentRanking(listOf("a", "b", "c"), fresh, now))
        assertNull(ServerPicker.recentRanking(listOf("a", "b", "c", "d"), fresh, now)) // d never checked
        assertNull(ServerPicker.recentRanking(listOf("a", "c"), fresh + ("c" to (80L to now - ServerPicker.freshMs - 1)), now)) // stale
        assertNull(ServerPicker.recentRanking(listOf("b"), fresh, now)) // nothing answered
        assertNull(ServerPicker.recentRanking(emptyList(), fresh, now))
    }
    @Test fun subscriptionQuota() {
        assertEquals(mapOf("upload" to 10L, "download" to 20L, "total" to 100L, "expire" to 1700000000L), SubscriptionQuota.parse("upload=10; download=20;total=100; expire=1700000000; bad; x=y"))
        assertEquals(70L, SubscriptionQuota.remaining(30, 100)); assertEquals(0L, SubscriptionQuota.remaining(130, 100)); assertNull(SubscriptionQuota.remaining(5, 0))
        assertNull(SubscriptionQuota.expiry(0)); assertEquals(1700000000L, SubscriptionQuota.expiry(1700000000L))
    }
    private fun roundTrip(link: String) {
        val p = Subscriptions.parseLink(link)
        val generated = ShareLinks.of(p.copy(link = ""))
        assertNotNull(generated, link.substringBefore("://"))
        val again = Subscriptions.parseLink(generated)
        assertEquals(JSONObject(p.outbound).toMap(), JSONObject(again.outbound).toMap(), link.substringBefore("://"))
        assertEquals(p.name, again.name)
    }
    @Test fun shareLinksRebuiltFromSingBoxOutbounds() {
        val u = F.UUID1
        roundTrip("vless://$u@a.example.com:443?type=tcp&security=reality&sni=front.example.com&pbk=${F.KEY}&sid=ab12&fp=chrome&flow=xtls-rprx-vision#Server%201")
        roundTrip("vless://$u@a.example.com:443?type=ws&security=tls&sni=t.example.com&host=h.example.com&path=%2Fws&alpn=h2%2Chttp%2F1.1#WS")
        roundTrip("trojan://pass@t.example.com:443?type=grpc&serviceName=svc&sni=t.example.com#Trojan")
        roundTrip("ss://YWVzLTEyOC1nY206cHc@s.example.com:8388#SS")
        roundTrip("hy2://secret@h.example.com:443?sni=h.example.com&alpn=h3&obfs=salamander&obfs-password=o#Hy2")
        roundTrip("tuic://$u:pw@q.example.com:443?sni=q.example.com&congestion_control=bbr&udp_relay_mode=native#Tuic")
        val vmess = JSONObject().put("v", "2").put("ps", "VM").put("add", "v.example.com").put("port", "443").put("id", u).put("aid", "0").put("net", "ws").put("host", "v.example.com").put("path", "/v").put("tls", "tls").put("sni", "v.example.com")
        roundTrip("vmess://" + java.util.Base64.getEncoder().encodeToString(vmess.toString().toByteArray()))
        // Clash entries already carry the link they were built from.
        val clash = ClashSubscriptions.parse("proxies:\n  - {name: b, type: ss, server: t.example, port: 8388, cipher: aes-128-gcm, password: pw}\n")
        assertEquals(clash.profiles[0].link, ShareLinks.of(clash.profiles[0]))
    }
    @Test fun shareLinksForXrayJsonServers() {
        val r = Subscriptions.parse(JSONArray().put(F.config("Germany", F.tcpReality())).put(F.config("France", F.grpcReality())).put(F.config("Game", F.hysteria())).put(F.config("X", F.xhttp())).toString())
        val links = r.profiles.associate { it.name to ShareLinks.of(it) }
        links.values.forEach { assertNotNull(it) }
        val de = JSONObject(Subscriptions.parseLink(links.getValue("Germany")!!).outbound)
        assertEquals("a.example.com", de.getString("server")); assertEquals("xtls-rprx-vision", de.getString("flow"))
        assertEquals(F.KEY, de.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
        assertEquals("front.example.com", de.getJSONObject("tls").getString("server_name"))
        val fr = JSONObject(Subscriptions.parseLink(links.getValue("France")!!).outbound)
        assertEquals("svc", fr.getJSONObject("transport").getString("service_name"))
        assertEquals("test-auth", JSONObject(Subscriptions.parseLink(links.getValue("Game")!!).outbound).getString("password"))
        assertEquals("xray", Subscriptions.parseLink(links.getValue("X")!!).engine)
        // A balancer group cannot be shared as one link.
        val group = Subscriptions.parse(F.config("Fastest", F.ws(), F.xhttp(), balancer = true).toString()).profiles.single()
        assertNull(ShareLinks.of(group))
    }
}

class NetworkSwitchTest {
    @Test fun initialStateIsNotASwitch() {
        val s = DefaultNetworkState(); s.available(1, "wlan0")
        assertEquals(DefaultNetworkState.Action.NONE, s.settle())
        assertEquals(DefaultNetworkState.Action.NONE, s.settle())
    }
    @Test fun newNetworkBeforeOldLost() {
        val s = DefaultNetworkState(); s.available(1, "wlan0"); s.settle()
        s.available(2, "rmnet0"); s.lost(1) // old network lost after the switch: ignored
        assertEquals(DefaultNetworkState.Action.SWITCHED, s.settle())
        assertEquals(2L, s.current?.handle)
    }
    @Test fun lostWithoutReplacementThenNewNetwork() {
        val s = DefaultNetworkState(); s.available(1, "wlan0"); s.settle()
        s.lost(1); assertEquals(DefaultNetworkState.Action.LOST, s.settle())
        s.available(2, "rmnet0"); assertEquals(DefaultNetworkState.Action.SWITCHED, s.settle())
    }
    @Test fun flapWithinDebounceBackToSameIsNoop() {
        val s = DefaultNetworkState(); s.available(1, "wlan0"); s.settle()
        s.lost(1); s.available(1, "wlan0")
        assertEquals(DefaultNetworkState.Action.NONE, s.settle())
    }
    @Test fun burstOfEventsIsNotDropped() {
        val s = DefaultNetworkState(); s.available(1, "wlan0"); s.settle()
        s.available(2, "rmnet0"); s.linkChanged(2, "rmnet1"); s.linkChanged(1, "wlan9")
        assertEquals(DefaultNetworkState.Action.SWITCHED, s.settle())
        assertEquals(DefaultNetworkState.Snapshot(2, "rmnet1"), s.current)
    }
    @Test fun sameHandleNewInterfaceIsASwitch() {
        val s = DefaultNetworkState(); s.available(1, "rmnet0"); s.settle()
        s.linkChanged(1, "rmnet1"); assertEquals(DefaultNetworkState.Action.SWITCHED, s.settle())
        s.linkChanged(1, "rmnet1"); assertEquals(DefaultNetworkState.Action.NONE, s.settle())
    }
    @Test fun recoveryPolicy() {
        assertEquals(NetworkRecovery.Step.RESTART, NetworkRecovery.afterSwitch("xray"))
        assertEquals(NetworkRecovery.Step.RESET, NetworkRecovery.afterSwitch("sing-box"))
        assertNull(NetworkRecovery.afterVerify(true))
        assertEquals(NetworkRecovery.Step.RESTART, NetworkRecovery.afterVerify(false))
    }
}

class NetworkServerChoiceTest {
    private val all = setOf("w", "m", "x")
    private fun d(prev: String, next: String, active: String, sel: String?, auto: Boolean = false, manual: Boolean = false) =
        NetworkServerChoice.decide(prev, next, active, sel, all, auto, manual)
    @Test fun wifiToMobileSwitchesToMobileSelection() = assertEquals(NetworkServerChoice.Decision.Switch("m"), d("wifi", "mobile", "w", "m"))
    @Test fun mobileToWifiSwitchesBack() = assertEquals(NetworkServerChoice.Decision.Switch("w"), d("mobile", "wifi", "m", "w"))
    @Test fun sameServerOrSameTypeKeeps() {
        assertEquals(NetworkServerChoice.Decision.Keep, d("wifi", "mobile", "w", "w"))
        assertEquals(NetworkServerChoice.Decision.Keep, d("wifi", "wifi", "w", "m"))
    }
    @Test fun missingOrRemovedSelectionKeeps() {
        assertEquals(NetworkServerChoice.Decision.Keep, d("wifi", "mobile", "w", null))
        assertEquals(NetworkServerChoice.Decision.Keep, d("wifi", "mobile", "w", ""))
        assertEquals(NetworkServerChoice.Decision.Keep, d("wifi", "mobile", "w", "gone"))
    }
    @Test fun autoSelectMeasuresUnlessManualPick() {
        assertEquals(NetworkServerChoice.Decision.AutoSelect, d("wifi", "mobile", "w", "m", auto = true))
        assertEquals(NetworkServerChoice.Decision.Switch("m"), d("wifi", "mobile", "w", "m", auto = true, manual = true))
        assertEquals(NetworkServerChoice.Decision.Keep, NetworkServerChoice.decide("wifi", "mobile", "w", null, setOf("w"), true, false))
    }
}
