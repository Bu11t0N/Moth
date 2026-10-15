package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.mothvpn.core.XrayJsonFixtures as F

class ServerLabelsTest {
    private val uuid = F.UUID1
    private fun link(l: String) = ServerLabels.label(Subscriptions.parseLink(l))
    private fun xray(vararg docs: JSONObject) = Subscriptions.parse(JSONArray().also { a -> docs.forEach { a.put(it) } }.toString()).profiles

    @Test fun shareLinks() {
        assertEquals("VLESS · TCP · Reality", link("vless://$uuid@a.example.com:443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp#n"))
        assertEquals("VLESS · WS · TLS", link("vless://$uuid@a.example.com:443?security=tls&type=ws&path=%2F#n"))
        assertEquals("VLESS · gRPC · TLS", link("vless://$uuid@a.example.com:443?security=tls&type=grpc&serviceName=s#n"))
        assertEquals("VLESS · TCP", link("vless://$uuid@a.example.com:443?type=tcp#n"))
        assertEquals("Trojan · WS · TLS", link("trojan://pw@a.example.com:443?type=ws&path=%2F#n"))
        assertEquals("Trojan · TCP · TLS", link("trojan://pw@a.example.com:443#n"))
        assertEquals("Hysteria2", link("hy2://pw@a.example.com:443?sni=a.example.com#n"))
        assertEquals("Shadowsocks", link("ss://" + java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray()) + "@a.example.com:8388#n"))
        assertEquals("SOCKS5", link("socks5://u:p@a.example.com:1080#n"))
        assertEquals("VLESS · XHTTP · TLS", link("vless://$uuid@a.example.com:443?security=tls&type=xhttp&path=%2F#n"))
    }

    @Test fun vmessLink() {
        val v = JSONObject().put("v", "2").put("ps", "n").put("add", "a.example.com").put("port", "443").put("id", uuid).put("aid", "0").put("net", "ws").put("path", "/").put("tls", "tls")
        assertEquals("VMess · WS · TLS", link("vmess://" + java.util.Base64.getEncoder().encodeToString(v.toString().toByteArray())))
    }

    @Test fun xrayJsonGetsFormatSuffix() {
        val p = xray(F.config("A", F.tcpReality()), F.config("B", F.xhttp()), F.config("C", F.grpcReality()))
        assertEquals(listOf("VLESS · TCP · Reality · JSON", "VLESS · XHTTP · TLS · JSON", "VLESS · gRPC · Reality · JSON"), p.map(ServerLabels::label))
    }

    @Test fun balancerGroupKeepsGroupLabel() {
        val p = xray(F.config("Fastest", F.ws(), F.xhttp(), balancer = true)).single()
        assertEquals("VLESS · Balancer · JSON", ServerLabels.label(p))
        assertEquals("VLESS · Балансировщик · JSON", ServerLabels.label(p, "Балансировщик"))
    }

    @Test fun legacySavedXrayJsonInferred() {
        val p = xray(F.config("A", F.tcpReality())).single().copy(source = "")
        val restored = SubscriptionRecord.from(SubscriptionRecord("https://e.example.com/s", listOf(p), p.id).json()).profiles.single()
        assertEquals("VLESS · TCP · Reality · JSON", ServerLabels.label(restored))
    }

    @Test fun sourcePersisted() {
        val p = Profile("i", "n", "vless", JSONObject().put("type", "vless").put("tls", JSONObject().put("enabled", true)).toString(), source = ServerLabels.SOURCE_CLASH)
        val restored = SubscriptionRecord.from(SubscriptionRecord("https://e.example.com/s", listOf(p), "i").json()).profiles.single()
        assertEquals("VLESS · TCP · TLS · Clash", ServerLabels.label(restored))
    }

    @Test fun singBoxAndClashSubscriptions() {
        val sb = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("type", "trojan").put("tag", "t").put("server", "a.example.com").put("server_port", 443).put("password", "p")
                .put("tls", JSONObject().put("enabled", true).put("server_name", "a.example.com")).put("transport", JSONObject().put("type", "grpc").put("service_name", "s")))
            .put(JSONObject().put("type", "hysteria2").put("tag", "h").put("server", "a.example.com").put("server_port", 443).put("password", "p")
                .put("tls", JSONObject().put("enabled", true).put("server_name", "a.example.com"))))
        assertEquals(listOf("Trojan · gRPC · TLS · sing-box", "Hysteria2 · sing-box"), Subscriptions.parse(sb.toString()).profiles.map(ServerLabels::label))
        val clash = "proxies:\n  - {name: a, type: ss, server: a.example.com, port: 8388, cipher: aes-256-gcm, password: p}\n  - {name: b, type: trojan, server: a.example.com, port: 443, password: p, sni: a.example.com}\n"
        assertEquals(listOf("Shadowsocks · Clash", "Trojan · TCP · TLS · Clash"), Subscriptions.parse(clash).profiles.map(ServerLabels::label))
    }

    @Test fun otherProtocolsAndGarbage() {
        fun sb(o: JSONObject) = ServerLabels.label(Profile("i", "n", "x", o.toString()))
        assertEquals("WireGuard", sb(JSONObject().put("type", "wireguard")))
        assertEquals("TUIC", sb(JSONObject().put("type", "tuic").put("tls", JSONObject().put("enabled", true))))
        assertEquals("SSH", sb(JSONObject().put("type", "ssh")))
        assertEquals("HTTPS", sb(JSONObject().put("type", "http").put("tls", JSONObject().put("enabled", true))))
        assertEquals("Shadowsocks · ShadowTLS", sb(JSONObject().put("type", "shadowsocks").put(SingBoxOutbounds.CHAIN, JSONArray().put(JSONObject().put("type", "shadowtls")))))
        assertEquals("VLESS", ServerLabels.label(Profile("i", "n", "vless / xhttp", "not json")))
    }
}
