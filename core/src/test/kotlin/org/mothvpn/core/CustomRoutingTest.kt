package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class CustomRoutingTest {
    private val profile = Subscriptions.parseLink("trojan://secret@example.com:443?security=tls#Name")
    private val rules = listOf(RoutingRule("domain", "mail.example.com", "direct"), RoutingRule("domain_suffix", "ads.example", "block"),
        RoutingRule("domain_keyword", "tracker", "block"), RoutingRule("ip_cidr", "192.168.0.0/16", "direct"), RoutingRule("ip_cidr", "2001:db8::/32", "proxy"))

    @Test fun validatesAndNormalizesInput() {
        assertEquals("example.com", CustomRouting.normalize("domain", "  Example.COM "))
        assertEquals("example.com", CustomRouting.normalize("domain_suffix", "*.example.com"))
        assertEquals("example.com", CustomRouting.normalize("domain_suffix", ".example.com"))
        assertEquals("ru", CustomRouting.normalize("domain_suffix", "ru"))
        assertEquals("xn--p1ai", CustomRouting.normalize("domain_suffix", "рф"))
        assertEquals("xn--e1afmkfd.xn--p1ai", CustomRouting.normalize("domain", "пример.рф"))
        assertEquals("10.0.0.1/32", CustomRouting.normalize("ip_cidr", "10.0.0.1"))
        assertEquals("10.0.0.0/8", CustomRouting.normalize("ip_cidr", "10.0.0.0/8"))
        assertEquals("2001:db8::/32", CustomRouting.normalize("ip_cidr", "2001:DB8::/32"))
        assertEquals("::1/128", CustomRouting.normalize("ip_cidr", "::1"))
        assertEquals("google", CustomRouting.normalize("domain_keyword", "Google"))
        listOf("", "http://example.com", "exa mple.com", "-bad.com", "a..b", "example.com/path", "x".repeat(300)).forEach { assertNull(CustomRouting.normalize("domain", it), it) }
        listOf("256.0.0.1", "10.0.0.0/33", "10.0.0/8", "1.2.3.4/", "1.2.3.4/a", "2001:db8::/129", "fe80::1%eth0", "localhost", "example.com", "1.2.3.4/8/1", "gggg::1")
            .forEach { assertNull(CustomRouting.normalize("ip_cidr", it), it) }
        listOf("", "a b", "a:b", "x".repeat(65)).forEach { assertNull(CustomRouting.normalize("domain_keyword", it), it) }
        assertNull(CustomRouting.normalize("geoip", "ru"))
        assertTrue(CustomRouting.valid(rules))
        assertFalse(CustomRouting.valid(RoutingRule("domain", "Example.com", "direct")), "not normalized")
        assertFalse(CustomRouting.valid(RoutingRule("domain", "example.com", "reject")))
        assertFailsWith<IllegalArgumentException> { ClientSettings(routingRules = listOf(RoutingRule("ip_cidr", "1.2.3.4/40", "direct"))).validate() }
    }

    @Test fun settingsRoundTripDropsInvalidRules() {
        val s = ClientSettings(routingRules = rules)
        assertEquals(s, ClientSettings.from(s.json()))
        val broken = s.json().apply { getJSONArray("routingRules").put(JSONObject().put("type", "domain").put("value", "bad value").put("action", "direct")) }
        assertEquals(rules, ClientSettings.from(broken).routingRules)
        assertEquals(emptyList(), ClientSettings.from(ClientSettings().json().apply { remove("routingRules") }).routingRules)
        assertEquals(listOf("routingRules=0->5"), LogFacts.settingsChanges(ClientSettings(hwid = s.hwid), s))
        assertFalse(LogFacts.settingsChanges(ClientSettings(hwid = s.hwid), s).joinToString().contains("example"))
    }

    @Test fun singBoxRulesComeFirstAndInOrder() {
        val j = JSONObject(TunnelConfig.create(profile, ClientSettings(routingRules = rules, directRu = true, ipv6 = false), mapOf("geoip-ru" to "/x/geoip-ru.srs")))
        val r = j.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", r.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", r.getJSONObject(1).getString("action"))
        assertEquals(6, r.getJSONObject(2).getInt("ip_version"))
        assertEquals("mail.example.com", r.getJSONObject(3).getJSONArray("domain").getString(0)); assertEquals("direct", r.getJSONObject(3).getString("outbound"))
        assertEquals("ads.example", r.getJSONObject(4).getJSONArray("domain_suffix").getString(0)); assertEquals("reject", r.getJSONObject(4).getString("action"))
        assertEquals("tracker", r.getJSONObject(5).getJSONArray("domain_keyword").getString(0)); assertEquals("reject", r.getJSONObject(5).getString("action"))
        assertEquals("192.168.0.0/16", r.getJSONObject(6).getJSONArray("ip_cidr").getString(0)); assertEquals("direct", r.getJSONObject(6).getString("outbound"))
        assertEquals("proxy", r.getJSONObject(7).getString("outbound"))
        // then the built-in "Russian sites directly" rules
        assertEquals(JSONObject().put("x", org.json.JSONArray(DirectRoutes.suffixes)).getJSONArray("x").toString(), r.getJSONObject(8).getJSONArray("domain_suffix").toString())
        assertEquals("geoip-ru", r.getJSONObject(9).getJSONArray("rule_set").getString(0))
        // IP-only rules need no sniffing
        val ipOnly = JSONObject(TunnelConfig.create(profile, ClientSettings(routingRules = listOf(rules[3])))).getJSONObject("route").getJSONArray("rules")
        assertEquals("hijack-dns", ipOnly.getJSONObject(0).getString("action")); assertEquals(2, ipOnly.length())
    }

    @Test fun xrayRulesForSingleAndGroupConfigs() {
        val id = "11111111-1111-4111-8111-111111111111"
        val xhttp = Subscriptions.parseLink("vless://$id@vpn.example:443?type=xhttp&security=tls&sni=example.org&path=%2Fxhttp")
        val plain = JSONObject(LocalProxyConfig.xray(xhttp, 1080, "u", "p"))
        assertFalse(plain.has("routing")); assertEquals(1, plain.getJSONArray("outbounds").length())
        val j = JSONObject(LocalProxyConfig.xray(xhttp, 1080, "u", "p", ClientSettings(routingRules = rules)))
        val outbounds = j.getJSONArray("outbounds")
        assertEquals(listOf("proxy", "direct", "block"), (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") })
        assertEquals("freedom", outbounds.getJSONObject(1).getString("protocol")); assertEquals("blackhole", outbounds.getJSONObject(2).getString("protocol"))
        val r = j.getJSONObject("routing").getJSONArray("rules")
        assertEquals(5, r.length())
        assertEquals("full:mail.example.com", r.getJSONObject(0).getJSONArray("domain").getString(0)); assertEquals("direct", r.getJSONObject(0).getString("outboundTag"))
        assertEquals("domain:ads.example", r.getJSONObject(1).getJSONArray("domain").getString(0)); assertEquals("block", r.getJSONObject(1).getString("outboundTag"))
        assertEquals("tracker", r.getJSONObject(2).getJSONArray("domain").getString(0))
        assertEquals("192.168.0.0/16", r.getJSONObject(3).getJSONArray("ip").getString(0))
        assertEquals("proxy", r.getJSONObject(4).getString("outboundTag"))
        assertTrue(j.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").getBoolean("enabled"))

        val group = Subscriptions.parse(XrayJsonFixtures.balancerDocument()).profiles.first()
        val g = JSONObject(LocalProxyConfig.xray(group, 1080, "u", "p", ClientSettings(routingRules = rules)))
        val gr = g.getJSONObject("routing").getJSONArray("rules")
        assertEquals(6, gr.length())
        assertEquals("balancer", gr.getJSONObject(4).getString("balancerTag")); assertEquals("2001:db8::/32", gr.getJSONObject(4).getJSONArray("ip").getString(0))
        assertEquals("moth-in", gr.getJSONObject(5).getJSONArray("inboundTag").getString(0))
        val gNone = JSONObject(LocalProxyConfig.xray(group, 1080, "u", "p")).getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, gNone.length())
    }

    @Test fun moveRules() {
        assertEquals(listOf(rules[1], rules[0]) + rules.drop(2), CustomRouting.move(rules, 0, 1))
        assertEquals(rules, CustomRouting.move(rules, 9, 0))
        assertEquals(rules.drop(1) + rules[0], CustomRouting.move(rules, 0, 99))
    }
}
