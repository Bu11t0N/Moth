package org.routevpn.core

import kotlin.test.*
import org.json.JSONObject
import java.net.URI

class FeaturesTest {
    private val uuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    @Test fun hysteria2CredentialsAndObfs() {
        val p = Subscriptions.parseLink("hy2://secret%3Apart@example.com:443?sni=cdn.example.com&obfs=salamander&obfs-password=obfs#Fast")
        val o = JSONObject(p.outbound)
        assertEquals("hysteria2", p.protocol); assertEquals("secret:part", o.getString("password"))
        assertEquals("obfs", o.getJSONObject("obfs").getString("password"))
        assertEquals("cdn.example.com", o.getJSONObject("tls").getString("server_name"))
    }
    @Test fun tuicV5() {
        val p = Subscriptions.parseLink("tuic://$uuid:pa%3Ass@example.com:443?congestion_control=bbr&alpn=h3")
        val o = JSONObject(p.outbound)
        assertEquals(uuid, o.getString("uuid")); assertEquals("pa:ss", o.getString("password")); assertEquals("bbr", o.getString("congestion_control"))
    }
    @Test fun hysteriaV1NeedsBandwidth() {
        assertFails { Subscriptions.parseLink("hysteria://secret@example.com:443") }
        assertEquals(20, JSONObject(Subscriptions.parseLink("hysteria://example.com:443?auth=secret&upmbps=20&downmbps=100").outbound).getInt("up_mbps"))
    }
    @Test fun xhttpGoesToXrayWithReality() {
        val p = Subscriptions.parseLink("vless://$uuid@example.com:443?type=xhttp&security=reality&pbk=public&sid=abcd&mode=packet-up&path=%2Ftest#XHTTP")
        assertEquals("xray", p.engine)
        val stream = JSONObject(p.outbound).getJSONObject("streamSettings")
        assertEquals("/test", stream.getJSONObject("xhttpSettings").getString("path"))
        assertEquals("public", stream.getJSONObject("realitySettings").getString("publicKey"))
    }
    @Test fun unsafeTlsAndUnknownModesRejected() {
        assertFails { Subscriptions.parseLink("hy2://secret@example.com?insecure=1") }
        assertFails { Subscriptions.parseLink("vless://$uuid@example.com:443?type=xhttp&mode=unknown") }
        assertFails { Subscriptions.parseLink("tuic://$uuid:p@example.com?congestion_control=unknown") }
        val extra = java.net.URLEncoder.encode("{\"downloadSettings\":{\"tlsSettings\":{\"allowInsecure\":true}}}", "UTF-8")
        assertFails { Subscriptions.parseLink("vless://$uuid@example.com:443?type=xhttp&extra=$extra") }
    }
    @Test fun vmessXhttpAndBootstrapDns() {
        val v = JSONObject().put("id", uuid).put("add", "example.com").put("port", 443).put("net", "xhttp").put("tls", "tls").put("scy", "auto")
        val p = Subscriptions.parseLink("vmess://" + java.util.Base64.getEncoder().encodeToString(v.toString().toByteArray()))
        assertEquals("xray", p.engine)
        val config = JSONObject(LocalProxyConfig.xray(p, 18080, "u", "p"))
        assertEquals("https+local://1.1.1.1/dns-query", config.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("ForceIPv4", config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy"))
    }
    @Test fun clashMixedSubscription() {
        val result = Subscriptions.parse("""
            proxies:
              - name: Hy2
                type: hysteria2
                server: example.com
                port: 443
                password: secret
              - name: TUIC
                type: tuic
                server: example.com
                port: 443
                uuid: $uuid
                password: secret
              - name: WS
                type: vless
                server: example.com
                port: 443
                uuid: $uuid
                tls: true
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example.com
              - name: Unsupported
                type: wireguard
        """.trimIndent())
        assertEquals(3, result.profiles.size); assertEquals(1, result.rejected)
        assertEquals("cdn.example.com", JSONObject(result.profiles[2].outbound).getJSONObject("transport").getJSONObject("headers").getString("Host"))
    }
    @Test fun clashRejectsJavaTagsDuplicateKeysAndProvidersOnly() {
        assertFails { Subscriptions.parse("proxies: !!java.net.URL [https://example.com]") }
        assertFails { Subscriptions.parse("proxies: []\nproxies: []") }
        assertFails { ClashSubscriptions.parse("proxy-providers: {}") }
    }
    @Test fun hwidOnlyGoesToOriginalOrigin() {
        val s = ClientSettings(deviceInfo = true)
        val original = URI("https://sub.example.com/token")
        val same = SubscriptionHeaders.create(s, original, URI("https://sub.example.com:443/new"), "16", "Samsung")
        assertEquals(s.hwid, same["x-hwid"]); assertEquals("Android", same["x-device-os"])
        for (target in listOf("https://other.example.com/token", "https://sub.example.com:8443/token", "http://sub.example.com/token")) {
            val headers = SubscriptionHeaders.create(s, original, URI(target), "16", "Samsung")
            assertFalse(headers.containsKey("x-hwid")); assertFalse(headers.containsKey("x-device-model"))
        }
        assertFalse(SubscriptionHeaders.create(s.copy(hwidEnabled = false), original, original, "16", "Samsung").containsKey("x-hwid"))
    }
    @Test fun settingsRoundTripAndValidation() {
        val s = ClientSettings(mtu = 1280, ipv6 = false, theme = "light")
        assertEquals(s, ClientSettings.from(s.json()))
        assertFails { s.copy(mtu = 100).validate() }; assertFails { s.copy(probeUrl = "http://example.com").validate() }
        assertFails { s.copy(userAgent = "Moth\r\nx-hwid: injected").validate() }
        assertFails { s.copy(hwid = "short").validate() }
    }
    @Test fun settingsFromPreviousReleaseKeepHwidAndConnectionOptions() {
        val previous = ClientSettings(mtu = 1280, ipv6 = false, theme = "dark", sortByLatency = true, deviceInfo = true)
        val json = previous.json().apply { remove("compactServers"); remove("hideUnavailable"); remove("probeMode"); remove("bootstrapDns") }
        val migrated = ClientSettings.from(json)
        assertEquals(previous, migrated)
        assertEquals(previous.hwid, migrated.hwid)
        assertTrue(migrated.compactServers)
        assertFalse(migrated.hideUnavailable)
        assertEquals(migrated.copy(compactServers = false, hideUnavailable = true),
            ClientSettings.from(migrated.copy(compactServers = false, hideUnavailable = true).json()))
    }
    @Test fun probeVariantsAndUdpAreNotConfusedWithTcp() {
        val s = ClientSettings()
        assertEquals(3, ProbePolicy.urls(s).size)
        assertEquals(2, ProbePolicy.urls(s.copy(probeUrl = "https://cp.cloudflare.com/generate_204")).size)
        assertEquals(listOf(s.probeUrl), ProbePolicy.urls(s.copy(probeMode = "https")))
        val tcp = Subscriptions.parseLink("vless://$uuid@example.com:443?security=tls")
        assertEquals("example.com" to 443, ProbePolicy.tcpEndpoint(tcp))
        val xhttp = Subscriptions.parseLink("vless://$uuid@example.com:443?type=xhttp&security=tls")
        assertEquals("example.com" to 443, ProbePolicy.tcpEndpoint(xhttp))
        assertNull(ProbePolicy.tcpEndpoint(Subscriptions.parseLink("hy2://secret@example.com:443")))
        assertNull(ProbePolicy.tcpEndpoint(Subscriptions.parseLink("tuic://$uuid:secret@example.com:443")))
        assertFails { s.copy(probeMode = "icmp").validate() }
    }
    @Test fun customBootstrapDnsIsUsedByAllCoreConfigs() {
        val s = ClientSettings(bootstrapDns = "https://8.8.8.8/dns-query", probeMode = "https")
        val p = Subscriptions.parseLink("hy2://secret@example.com:443")
        assertEquals(s.bootstrapDns, JSONObject(TunnelConfig.create(p, s)).getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("address"))
        assertEquals(s.bootstrapDns, JSONObject(LocalProxyConfig.singBox(p, 18080, "u", "p", s)).getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("address"))
        val xp = Subscriptions.parseLink("vless://$uuid@example.com:443?type=xhttp&security=tls")
        assertEquals("https+local://8.8.8.8/dns-query", JSONObject(LocalProxyConfig.xray(xp, 18080, "u", "p", s)).getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals(s, ClientSettings.from(s.json()))
        assertFails { s.copy(bootstrapDns = "http://example.com").validate() }
    }
    @Test fun tunnelRespectsSettingsAndCapturesIpv6WhenDisabled() {
        val p = Subscriptions.parseLink("hy2://secret@example.com")
        val config = JSONObject(TunnelConfig.create(p, ClientSettings(mtu = 1280, ipv6 = false, dns = "https://dns.google/dns-query")))
        assertEquals(1280, config.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertEquals(2, config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").length())
        assertEquals("reject", config.getJSONObject("route").getJSONArray("rules").getJSONObject(1).getString("action"))
        assertEquals("bootstrap", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("address_resolver"))
    }
}
