package org.mothvpn.core

import kotlin.test.*
import org.json.JSONObject
import java.util.Base64

class SubscriptionsTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val vless = "vless://$uuid@vpn.example:443?security=reality&sni=example.org&pbk=public-key&sid=abcd&fp=chrome&type=tcp&flow=xtls-rprx-vision#%D0%A1%D0%B5%D1%80%D0%B2%D0%B5%D1%80"
    @Test fun realityParametersAndUnicodeSurvive() {
        val p = Subscriptions.parseLink(vless)
        assertEquals("Сервер", p.name)
        val o = JSONObject(p.outbound)
        assertEquals("xtls-rprx-vision", o.getString("flow"))
        assertEquals("public-key", o.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
    }
    @Test fun base64SubscriptionAndDuplicates() {
        val input = Base64.getEncoder().encodeToString("$vless\n$vless\nunsupported://server".toByteArray())
        val result = Subscriptions.parse(input)
        assertEquals(1, result.profiles.size); assertEquals(1, result.rejected)
    }
    @Test fun importSummaryCountsDuplicatesAndComments() {
        val result = Subscriptions.parse("# comment\n$vless\n\n# another\n$vless\nunsupported://server")
        assertEquals("parsed 2, duplicates 1, rejected 1", result.summary())
        assertEquals(2, result.comments)
        assertEquals(1, result.duplicates.size)
        val note = result.duplicates[0]
        assertTrue(note.startsWith("link 2 (vless): duplicate of link 1, id ") && !note.contains("://") && !note.contains("vpn.example"), note)
        assertEquals(result.profiles[0].id.take(8), note.takeLast(8))
    }
    @Test fun rejectionReasonsAreReportedWithoutLinks() {
        val bad = "trojan://password@vpn.example:443?allowInsecure=1"
        val result = Subscriptions.parse("$vless\n$bad\nunsupported://server.example")
        assertEquals(2, result.rejected); assertEquals(2, result.reasons.size)
        assertTrue(result.reasons.all { it.startsWith("link ") && !it.contains("://") && !it.contains("password") }, result.reasons.toString())
        val clash = ClashSubscriptions.parse("proxies:\n  - {name: a, type: trojan, server: s.example, port: 443, password: pw, skip-cert-verify: true}\n  - {name: b, type: ss, server: t.example, port: 8388, cipher: aes-128-gcm, password: pw}\n")
        assertEquals(1, clash.rejected); assertEquals(1, clash.reasons.size)
        assertTrue(clash.reasons[0].startsWith("proxy 1 (trojan): "), clash.reasons[0])
    }
    @Test fun unsupportedTransportIsRejectedRatherThanChanged() {
        assertFails { Subscriptions.parseLink(vless.replace("type=tcp", "type=xhttp")) }
        assertFails { Subscriptions.parse("<html>service error</html>") }
    }
    @Test fun insecureTlsIsRejected() {
        assertFails { Subscriptions.parseLink("trojan://password@vpn.example:443?allowInsecure=1") }
    }
    @Test fun passwordsWithPlusAndEncodedDelimiters() {
        val o = JSONObject(Subscriptions.parseLink("trojan://a+b%3Ac@vpn.example:443?security=tls").outbound)
        assertEquals("a+b:c", o.getString("password"))
    }
    @Test fun ipv6AndWebsocket() {
        val p = Subscriptions.parseLink("vless://$uuid@[2001:db8::1]:443?type=ws&security=tls&host=example.org&path=%2Fvpn#Test")
        val o = JSONObject(p.outbound)
        assertEquals("2001:db8::1", o.getString("server"))
        assertEquals("/vpn", o.getJSONObject("transport").getString("path"))
    }
    @Test fun shadowsocksSip002() {
        val auth = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-128-gcm:p+a:ss".toByteArray())
        val o = JSONObject(Subscriptions.parseLink("ss://$auth@vpn.example:8388#SS").outbound)
        assertEquals("aes-128-gcm", o.getString("method")); assertEquals("p+a:ss", o.getString("password"))
    }
    @Test fun vmessAndDnsRouting() {
        val v = JSONObject().put("add", "vpn.example").put("port", "443").put("id", uuid).put("net", "ws").put("path", "/vpn").put("tls", "tls")
        val p = Subscriptions.parseLink("vmess://" + Base64.getEncoder().encodeToString(v.toString().toByteArray()))
        val config = JSONObject(TunnelConfig.create(p))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals("bootstrap", config.getJSONObject("route").getJSONObject("default_domain_resolver").getString("server"))
        assertEquals("hijack-dns", config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        assertTrue(config.getJSONArray("inbounds").getJSONObject(0).getBoolean("strict_route"))
    }
}
