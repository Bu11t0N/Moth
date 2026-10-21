package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.mothvpn.core.LabelPart.Kind
import org.mothvpn.core.XrayJsonFixtures as F

class LabelPartsTest {
    private fun sb(o: JSONObject) = Profile("i", "n", "x", o.toString())
    private fun link(l: String) = Subscriptions.parseLink(l)
    private fun kinds(p: Profile, word: String? = "Unencrypted") = ServerLabels.parts(p, unencrypted = word).map { it.text to it.kind }

    @Test fun partsJoinIntoTheLabel() {
        val profiles = listOf(link("vless://${F.UUID1}@a.example.com:443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp#n"),
            link("trojan://pw@a.example.com:443?type=ws&path=%2F#n"), link("socks5://u:p@a.example.com:1080#n"),
            sb(JSONObject().put("type", "shadowsocks").put(SingBoxOutbounds.CHAIN, JSONArray().put(JSONObject().put("type", "shadowtls")))))
        for (p in profiles) assertEquals(ServerLabels.label(p), ServerLabels.parts(p).joinToString(" · ") { it.text })
    }

    @Test fun kindsAndProtocolTones() {
        val reality = link("vless://${F.UUID1}@a.example.com:443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp#n")
        assertEquals(listOf("VLESS" to Kind.PROTOCOL, "TCP" to Kind.TRANSPORT, "Reality" to Kind.REALITY), kinds(reality))
        assertEquals(ProtocolTone.VLESS, ServerLabels.parts(reality).first().protocol)
        val tls = link("trojan://pw@a.example.com:443?type=ws&path=%2F#n")
        assertEquals(listOf("Trojan" to Kind.PROTOCOL, "WS" to Kind.TRANSPORT, "TLS" to Kind.TLS), kinds(tls))
        assertEquals(ProtocolTone.TROJAN, ServerLabels.parts(tls).first().protocol)
        assertEquals(listOf("Shadowsocks" to Kind.PROTOCOL, "ShadowTLS" to Kind.TLS),
            kinds(sb(JSONObject().put("type", "shadowsocks").put(SingBoxOutbounds.CHAIN, JSONArray().put(JSONObject().put("type", "shadowtls"))))))
        mapOf("hysteria2" to ProtocolTone.HYSTERIA, "hy2" to ProtocolTone.HYSTERIA, "hysteria" to ProtocolTone.HYSTERIA, "tuic" to ProtocolTone.HYSTERIA,
            "ss" to ProtocolTone.SHADOWSOCKS, "vmess" to ProtocolTone.VMESS, "anytls" to ProtocolTone.ANYTLS, "wireguard" to ProtocolTone.WIREGUARD,
            "ssh" to ProtocolTone.OTHER, "socks" to ProtocolTone.OTHER).forEach { (k, t) -> assertEquals(t, ServerLabels.tone(k), k) }
    }

    @Test fun balancerSourceAndUnencryptedParts() {
        val group = Subscriptions.parse(JSONArray().put(F.config("Fastest", F.ws(), F.xhttp(), balancer = true)).toString()).profiles.single()
        assertEquals(listOf("VLESS" to Kind.PROTOCOL, "Балансировщик" to Kind.BALANCER, "JSON" to Kind.SOURCE),
            ServerLabels.parts(group, "Балансировщик", "Без шифрования").map { it.text to it.kind })
        assertEquals(listOf("SOCKS5" to Kind.PROTOCOL, "Без шифрования" to Kind.UNENCRYPTED), kinds(link("socks5://u:p@a.example.com:1080#n"), "Без шифрования"))
        // Without the word the unencrypted part is left out, so label() is unchanged.
        assertEquals(listOf("SOCKS5" to Kind.PROTOCOL), kinds(link("socks5://u:p@a.example.com:1080#n"), null))
        assertEquals(listOf("VLESS" to Kind.PROTOCOL, "TCP" to Kind.TRANSPORT, "Unencrypted" to Kind.UNENCRYPTED), kinds(link("vless://${F.UUID1}@a.example.com:443?type=tcp#n")))
    }

    @Test fun unencryptedOnlyForPlainTransports() {
        fun u(o: JSONObject) = ProxyProtocols.unencrypted(sb(o))
        val on = JSONObject().put("enabled", true)
        // Plain: VLESS/Trojan without TLS or Reality, SOCKS5, HTTP.
        assertTrue(ProxyProtocols.unencrypted(link("vless://${F.UUID1}@a.example.com:443?type=tcp#n")))
        assertTrue(u(JSONObject().put("type", "vless")))
        assertTrue(u(JSONObject().put("type", "trojan")))
        assertTrue(u(JSONObject().put("type", "socks")))
        assertTrue(u(JSONObject().put("type", "http")))
        assertTrue(ProxyProtocols.unencrypted(link("socks5://u:p@a.example.com:1080#n")))
        // Encrypted.
        assertFalse(ProxyProtocols.unencrypted(link("vless://${F.UUID1}@a.example.com:443?security=tls&type=ws&path=%2F#n")))
        assertFalse(ProxyProtocols.unencrypted(link("vless://${F.UUID1}@a.example.com:443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp#n")))
        assertFalse(ProxyProtocols.unencrypted(link("trojan://pw@a.example.com:443#n")))
        assertFalse(u(JSONObject().put("type", "http").put("tls", on)))
        for (t in listOf("vmess", "shadowsocks", "hysteria2", "tuic", "anytls", "wireguard", "ssh")) assertFalse(u(JSONObject().put("type", t)), t)
        // Xray JSON: by streamSettings.security.
        fun x(protocol: String, security: String?) = ProxyProtocols.unencrypted(Profile("i", "n", protocol,
            JSONObject().put("protocol", protocol).put("streamSettings", JSONObject().put("network", "tcp").apply { if (security != null) put("security", security) }).toString()))
        assertTrue(x("vless", null)); assertTrue(x("vless", "none")); assertTrue(x("trojan", "none"))
        assertFalse(x("vless", "reality")); assertFalse(x("trojan", "tls")); assertFalse(x("vmess", null)); assertFalse(x("shadowsocks", null))
        // Balancer groups and xray JSON servers from fixtures.
        assertFalse(ProxyProtocols.unencrypted(Subscriptions.parse(JSONArray().put(F.config("A", F.tcpReality())).toString()).profiles.single()))
        assertFalse(ProxyProtocols.unencrypted(Profile("i", "n", "group", JSONObject().put("group", true).toString())))
    }
}
