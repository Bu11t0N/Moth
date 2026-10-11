package org.routevpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.routevpn.core.XrayJsonFixtures as F

class XrayJsonSubscriptionsTest {
    private fun parse(vararg docs: JSONObject) = Subscriptions.parse(JSONArray().also { a -> docs.forEach { a.put(it) } }.toString())
    private fun out(p: Profile) = JSONObject(p.outbound)

    @Test fun arrayOfConfigsOneProfileEach() {
        val r = parse(F.config("Germany", F.tcpReality()), F.config("France", F.grpcReality()), F.config("Game", F.hysteria()), F.config("Fastest", F.ws(), F.xhttp(), balancer = true))
        assertEquals(listOf("Germany", "France", "Game", "Fastest"), r.profiles.map { it.name })
        assertEquals(0, r.rejected)
        assertEquals(listOf("xray", "xray", "sing-box", "xray"), r.profiles.map { it.engine })
    }
    @Test fun singleConfigObjectAndOutboundsOnly() {
        val one = Subscriptions.parse(F.config("Solo", F.tcpReality()).toString())
        assertEquals(1, one.profiles.size); assertEquals("Solo", one.profiles[0].name)
        val bare = Subscriptions.parse(JSONObject().put("proxies", JSONArray().put(F.tcpReality("t1")).put(F.xhttp("t2"))).put("remarks", "List").toString())
        assertEquals(listOf("List · t1", "List · t2"), bare.profiles.map { it.name })
        val arr = Subscriptions.parse(JSONArray().put(F.grpcReality("only")).toString())
        assertEquals("only", arr.profiles[0].name)
    }
    @Test fun tcpRealityVision() {
        val p = parse(F.config("T", F.tcpReality())).profiles[0]
        val o = out(p)
        val user = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow")); assertEquals("proxy", o.getString("tag"))
        assertEquals("reality", o.getJSONObject("streamSettings").getString("security"))
        assertEquals("/x", o.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("spiderX"))
        assertNotNull(ProbePolicy.tcpEndpoint(p))
    }
    @Test fun grpcRealitySimplifiedSettingsAndModeBool() {
        val o = out(parse(F.config("G", F.grpcReality())).profiles[0])
        val grpc = o.getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("svc", grpc.getString("serviceName")); assertEquals("auth.example.com", grpc.getString("authority")); assertTrue(grpc.getBoolean("multiMode"))
        assertEquals("g.example.com", o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
    }
    @Test fun wsCustomHeaderAllowedButSanitized() {
        val o = out(parse(F.config("W", F.ws())).profiles[0])
        assertEquals("abc", o.getJSONObject("streamSettings").getJSONObject("wsSettings").getJSONObject("headers").getString("X-Whitelist-Header"))
        assertFailsWith<IllegalArgumentException> { parse(F.config("W", F.ws(value = "a\r\nHost: evil"))) }
        assertFailsWith<IllegalArgumentException> { parse(F.config("W", F.ws(header = "Bad Header"))) }
    }
    @Test fun xhttp() {
        val o = out(parse(F.config("X", F.xhttp())).profiles[0])
        assertEquals("xhttp", o.getJSONObject("streamSettings").getString("network"))
        assertEquals("/p", o.getJSONObject("streamSettings").getJSONObject("xhttpSettings").getString("path"))
    }
    @Test fun balancerGroup() {
        val r = Subscriptions.parse(F.balancerDocument())
        assertEquals(1, r.profiles.size)
        val p = r.profiles[0]
        assertEquals("⚡ Fastest", p.name); assertEquals("vless · balancer", p.protocol); assertEquals("xray", p.engine)
        val g = out(p)
        assertEquals(4, g.getJSONArray("outbounds").length()) // hysteria member skipped
        val b = g.getJSONObject("balancer")
        assertEquals("proxy-1", b.getString("fallbackTag")) // original fallback was "direct": never direct
        assertEquals("leastLoad", b.getJSONObject("strategy").getString("type"))
        assertFalse(b.getJSONObject("strategy").getJSONObject("settings").has("evil"))
        assertFalse(g.getJSONObject("burstObservatory").getJSONObject("pingConfig").has("connectivity"))
        assertTrue(r.reasons.any { it.contains("Hysteria") })
        assertNull(ProbePolicy.tcpEndpoint(p))
    }
    @Test fun groupXrayConfigIsSafe() {
        val p = Subscriptions.parse(F.balancerDocument()).profiles[0]
        val c = JSONObject(LocalProxyConfig.xray(p, 18085, "u", "pw"))
        assertEquals(1, c.getJSONArray("inbounds").length())
        val inbound = c.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen")); assertEquals(18085, inbound.getInt("port"))
        val outs = (0 until c.getJSONArray("outbounds").length()).map { c.getJSONArray("outbounds").getJSONObject(it) }
        assertTrue(outs.none { it.getString("protocol") in setOf("freedom", "blackhole") })
        assertTrue(outs.all { it.getJSONObject("streamSettings").has("sockopt") })
        val rules = c.getJSONObject("routing").getJSONArray("rules")
        assertEquals(1, rules.length()); assertEquals("balancer", rules.getJSONObject(0).getString("balancerTag"))
        assertFalse(c.toString().contains("bank.example.com")); assertFalse(c.toString().contains("10808"))
    }
    @Test fun hysteriaMapsToSingBoxHysteria2() {
        val p = parse(F.config("Game", F.hysteria())).profiles[0]
        assertEquals("hysteria2", p.protocol); assertEquals("sing-box", p.engine)
        val o = out(p); assertEquals("h.example.com", o.getString("server")); assertEquals("test-auth", o.getString("password"))
        assertNull(ProbePolicy.tcpEndpoint(p))
    }
    @Test fun insecureTlsRejected() {
        val insecure = F.xhttp().apply { getJSONObject("streamSettings").getJSONObject("tlsSettings").put("allowInsecure", true) }
        val r = parse(F.config("Bad", insecure), F.config("Good", F.tcpReality()))
        assertEquals(listOf("Good"), r.profiles.map { it.name }); assertEquals(1, r.rejected)
        assertTrue(r.reasons.any { it.contains("allowInsecure") })
        assertFailsWith<IllegalArgumentException> { parse(F.config("H", F.hysteria(insecure = true))) }
    }
    @Test fun unsupportedPartsRejectedWithReason() {
        val kcp = F.tcpReality().apply { getJSONObject("streamSettings").put("network", "kcp") }
        val vmess = JSONObject().put("tag", "p").put("protocol", "vmess").put("settings", JSONObject())
        val r = parse(F.config("K", kcp), F.config("V", vmess), F.config("Ok", F.ws()))
        assertEquals(1, r.profiles.size); assertEquals(2, r.rejected); assertEquals(2, r.reasons.size)
    }
    @Test fun malformedAndLimits() {
        assertFailsWith<Exception> { Subscriptions.parse("{\"outbounds\": [") }
        assertFailsWith<IllegalArgumentException> { Subscriptions.parse("{\"outbounds\":[]}") }
        assertFailsWith<IllegalArgumentException> { Subscriptions.parse("[" + "{\"outbounds\":[]},".repeat(600) + "{\"outbounds\":[]}]") }
        assertFailsWith<IllegalArgumentException> { Subscriptions.parse("[{\"outbounds\":[]}," + " ".repeat(2 * 1024 * 1024) + "]") }
    }
    @Test fun clashJsonStillGoesToClashPath() {
        assertFalse(XrayJsonSubscriptions.looksLike("{\"proxies\":[{\"name\":\"a\",\"type\":\"vless\"}]}"))
        assertTrue(XrayJsonSubscriptions.looksLike("[{\"outbounds\":[]}]"))
    }
}
