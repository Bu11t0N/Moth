package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class RoutingTest {
    private val profile = Subscriptions.parseLink("trojan://secret@example.com:443?security=tls#Name")

    @Test fun splitTunnelPlans() {
        assertEquals(SplitTunnel.Plan(listOf("a.b", "c.d", "org.mothvpn.android"), emptyList()), SplitTunnel.plan("only", listOf("c.d", "a.b", "org.mothvpn.android"), "org.mothvpn.android"))
        assertEquals(SplitTunnel.Plan(emptyList(), listOf("a.b")), SplitTunnel.plan("bypass", listOf("a.b", "org.mothvpn.android"), "org.mothvpn.android"))
        assertEquals(SplitTunnel.Plan(emptyList(), emptyList()), SplitTunnel.plan("only", emptyList(), "org.mothvpn.android"))
        assertEquals(SplitTunnel.Plan(emptyList(), emptyList()), SplitTunnel.plan("off", listOf("a.b"), "x.y"))
        assertFalse(SplitTunnel.validPackage("bad"))
        assertFailsWith<IllegalArgumentException> { ClientSettings(splitMode = "all").validate() }
    }
    @Test fun settingsRoundTripAndDefaults() {
        val d = ClientSettings()
        assertEquals("off", d.splitMode); assertFalse(d.directRu); assertEquals(0, d.subUpdateHours); assertFalse(d.autoSelect); assertFalse(d.bootConnect); assertTrue(d.dnsLeakProtection)
        val s = d.copy(splitMode = "bypass", splitApps = listOf("com.example.app"), directRu = true, subUpdateHours = 12, autoSelect = true, bootConnect = true, dnsLeakProtection = false, dns = "tls://dns.example")
        assertEquals(s, ClientSettings.from(s.json()))
        val old = d.json().apply { listOf("splitMode", "splitApps", "directRu", "subUpdateHours", "autoSelect", "bootConnect", "dnsLeakProtection").forEach { remove(it) } }
        assertEquals(d, ClientSettings.from(old))
        assertFailsWith<IllegalArgumentException> { d.copy(subUpdateHours = 5).validate() }
        assertEquals(listOf(0, 1, 2, 3, 6, 12, 24), SubscriptionSchedule.intervals)
        assertEquals(listOf(0, 0, 1, 3, 6, 12, 24, 24), listOf(-1, 0, 1, 3, 4, 7, 13, 48).map(SubscriptionSchedule::normalize))
        assertEquals(6, ClientSettings.from(d.json().put("subUpdateHours", 5)).subUpdateHours)
    }
    @Test fun dnsAddresses() {
        listOf("https://1.1.1.1/dns-query", "tls://dns.google", "1.1.1.1").forEach { assertTrue(DnsServers.valid(it), it) }
        listOf("http://1.1.1.1", "", " 1.1.1.1", "999.1.1.1", "udp://x", "https://u@h/").forEach { assertFalse(DnsServers.valid(it), it) }
        DnsServers.presets.forEach { assertTrue(DnsServers.valid(it.address)) }
    }
    @Test fun defaultTunnelConfigIsUnchanged() {
        val j = JSONObject(TunnelConfig.create(profile))
        assertTrue(j.getJSONArray("inbounds").getJSONObject(0).getBoolean("strict_route"))
        val rules = j.getJSONObject("route").getJSONArray("rules")
        assertEquals(1, rules.length()); assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertFalse(j.getJSONObject("route").has("rule_set"))
    }
    @Test fun directRussiaRules() {
        val j = JSONObject(TunnelConfig.create(profile, ClientSettings(directRu = true), mapOf("geoip-ru" to "/data/geoip-ru.srs")))
        val route = j.getJSONObject("route"); val rules = route.getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("direct", rules.getJSONObject(2).getString("outbound"))
        assertEquals("geoip-ru", rules.getJSONObject(3).getJSONArray("rule_set").getString(0))
        assertEquals("local", route.getJSONArray("rule_set").getJSONObject(0).getString("type"))
        val noCache = JSONObject(TunnelConfig.create(profile, ClientSettings(directRu = true))).getJSONObject("route")
        assertFalse(noCache.has("rule_set")); assertEquals(3, noCache.getJSONArray("rules").length())
        assertFalse(JSONObject(TunnelConfig.create(profile, ClientSettings(dnsLeakProtection = false))).getJSONArray("inbounds").getJSONObject(0).getBoolean("strict_route"))
        assertTrue(DirectRoutes.stale(0, 1)); assertFalse(DirectRoutes.stale(1000, 2000))
    }
    @Test fun customDnsGoesThroughTheProxy() {
        val dns = JSONObject(TunnelConfig.create(profile, ClientSettings(dns = "tls://dns.google"))).getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("tls", dns.getString("type")); assertEquals("dns.google", dns.getString("server")); assertEquals("proxy", dns.getString("detour")); assertEquals("bootstrap", dns.getString("domain_resolver"))
    }
    @Test fun pickerAndFailover() {
        val order = listOf("a", "b", "c", "d")
        val r = ServerPicker.ranking(order, mapOf("a" to 300L, "b" to null, "c" to 100L, "d" to 100L))
        assertEquals(listOf("c", "d", "a"), r)
        assertEquals("d", ServerPicker.next(r, "c", emptySet()))
        assertEquals("a", ServerPicker.next(r, "c", setOf("d")))
        assertNull(ServerPicker.next(r, "a", setOf("c", "d")))
    }
    @Test fun scheduleAndSpeed() {
        assertFalse(SubscriptionSchedule.due(0, 0, Long.MAX_VALUE))
        assertTrue(SubscriptionSchedule.due(6, 0, 6 * 3_600_000L))
        assertFalse(SubscriptionSchedule.due(24, 0, 3_600_000L))
        assertEquals(80.0, SpeedTest.mbps(10_000_000, 1000), 0.001)
    }
    @Test fun importInputAndLinks() {
        assertEquals(ImportInput.Kind.Subscription("https://x.example/s"), ImportInput.classify("  https://x.example/s \n"))
        assertTrue(ImportInput.classify("vless://a@b:1#x\ntrojan://p@h:443") is ImportInput.Kind.Links)
        assertEquals(ImportInput.Kind.Invalid, ImportInput.classify("hello"))
        assertEquals(ImportInput.Kind.Invalid, ImportInput.classify("http://x"))
        assertEquals("trojan://secret@example.com:443?security=tls#Name", profile.link)
        val again = SubscriptionRecord.from(SubscriptionRecord("moth:local", listOf(profile), profile.id).json())
        assertEquals(profile, again.profiles[0]); assertTrue(again.local)
        assertEquals(1, ImportInput.merge(listOf(profile), listOf(profile)).size)
    }
}
