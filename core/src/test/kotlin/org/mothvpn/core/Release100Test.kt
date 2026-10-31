package org.mothvpn.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.*

/** 1.0.0: AmneziaWG on the Xray engine, device data per subscription, split "all apps", "Russian sites directly" off by default. */
class Release100Test {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private val priv = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val pub = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private fun noise(p: Profile) = JSONObject(p.outbound).getJSONObject("streamSettings").getJSONObject("finalmask")
        .getJSONArray("udp").getJSONObject(0).also { assertEquals("noise", it.getString("type")) }.getJSONObject("settings").getJSONArray("noise")

    @Test fun amneziaLinkBecomesXrayWireguardWithJunk() {
        val link = "wireguard://${enc(priv)}@awg.example:51820?publickey=${enc(pub)}&address=10.8.0.2/32&jc=3&jmin=40&jmax=70&s1=0&s2=0&h1=1&h2=2&h3=3&h4=4&i1=${enc("<b 0xc0ffee><r 16> <t>")}#AWG"
        val p = Subscriptions.parseLink(link)
        assertEquals("xray", p.engine); assertEquals(AmneziaWg.PROTOCOL, p.protocol); assertEquals("AWG", p.name)
        val o = JSONObject(p.outbound)
        assertEquals("wireguard", o.getString("protocol")); assertEquals("proxy", o.getString("tag"))
        val s = o.getJSONObject("settings")
        assertEquals(priv, s.getString("secretKey")); assertTrue(s.getBoolean("noKernelTun"))
        assertEquals("awg.example:51820", s.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals(pub, s.getJSONArray("peers").getJSONObject(0).getString("publicKey"))
        val n = noise(p)
        assertEquals(4, n.length())
        assertEquals("exp", n.getJSONObject(0).getString("type")); assertEquals("<b 0xc0ffee><r 16><t>", n.getJSONObject(0).getString("packet"))
        (1..3).forEach { assertEquals("40-70", n.getJSONObject(it).getString("rand")) }
        assertEquals(listOf(ServerLabels.parts(p).first().text), listOf("AmneziaWG"))
        assertNull(ProbePolicy.tcpEndpoint(p))
        // The running Xray config keeps the outbound and its finalmask.
        val cfg = JSONObject(LocalProxyConfig.xray(p, 10808, "00000000-0000-0000-0000-000000000001", "", ClientSettings(), bridge = true))
        val out = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", out.getString("protocol")); assertTrue(out.getJSONObject("streamSettings").has("finalmask"))
        assertFalse(out.has("mux"))
    }

    @Test fun unsupportedAmneziaFeaturesAreNamed() {
        val base = "wireguard://${enc(priv)}@awg.example:51820?publickey=${enc(pub)}&address=10.8.0.2&jc=3&jmin=40&jmax=70"
        assertTrue(assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink("$base&s1=20") }.message!!.contains("S1–S4"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink("$base&h1=123456") }.message!!.contains("H1–H4"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink("$base&i1=${enc("<x 1>")}") }.message!!.contains("I1–I5"))
        assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink(base.replace("jmax=70", "jmax=20")) }
    }

    private val conf = """
        [Interface]
        PrivateKey = $priv
        Address = 10.8.0.2/32
        DNS = 1.1.1.1
        MTU = 1280
        Jc = 4
        Jmin = 10
        Jmax = 50
        S1 = 0
        S2 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = $pub
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.5:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun confFilesImport() {
        assertTrue(ImportInput.classify(conf) is ImportInput.Kind.Links)
        val p = Subscriptions.parse(conf).profiles.single()
        assertEquals(AmneziaWg.PROTOCOL, p.protocol); assertEquals(4, noise(p).length())
        assertEquals(1280, JSONObject(p.outbound).getJSONObject("settings").getInt("mtu"))
        val plain = Subscriptions.parse(conf.lines().filterNot { Regex("^(Jc|Jmin|Jmax|S1|S2|H\\d) =").containsMatchIn(it) }.joinToString("\n")).profiles.single()
        assertEquals("wireguard", plain.protocol); assertEquals("sing-box", plain.engine)
    }

    @Test fun amneziaVpnKeyImports() {
        val last = JSONObject().put("config", conf).toString()
        val json = JSONObject().put("description", "Home").put("containers", org.json.JSONArray().put(JSONObject().put("container", "amnezia-awg")
            .put("awg", JSONObject().put("last_config", last)))).toString().toByteArray()
        val d = Deflater(); d.setInput(json); d.finish()
        val z = ByteArrayOutputStream(); val buf = ByteArray(4096); while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        val key = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(4).putInt(json.size).array() + z.toByteArray())
        assertTrue(ImportInput.classify(key) is ImportInput.Kind.Links)
        val p = Subscriptions.parse(key).profiles.single()
        assertEquals(AmneziaWg.PROTOCOL, p.protocol); assertEquals("Home", p.name)
    }

    @Test fun clashAmneziaOption() {
        val yaml = """
proxies:
  - {name: AWG, type: wireguard, server: w.example, port: 51820, ip: 10.0.0.2, private-key: "$priv", public-key: "$pub", amnezia-wg-option: {jc: 2, jmin: 30, jmax: 60}}
"""
        val p = Subscriptions.parse(yaml).profiles.single()
        assertEquals(AmneziaWg.PROTOCOL, p.protocol); assertEquals(2, noise(p).length())
    }

    @Test fun deviceDataGoesOnlyToChosenSubscriptions() {
        val all = ClientSettings(hwidEnabled = true)
        assertFalse(all.deviceDataSelected); assertTrue(all.sendsDeviceData("abc")); assertTrue(all.sendsDeviceData(null))
        val some = all.copy(deviceDataSelected = true, deviceDataSubs = listOf("abc"))
        assertTrue(some.sendsDeviceData("abc")); assertFalse(some.sendsDeviceData("other")); assertFalse(some.sendsDeviceData(null))
        val back = ClientSettings.from(some.json())
        assertTrue(back.deviceDataSelected); assertEquals(listOf("abc"), back.deviceDataSubs)
        val bad = ClientSettings.from(some.json().put("deviceDataSubs", org.json.JSONArray(listOf("ok_1", "bad id!", "ok_1"))))
        assertEquals(listOf("ok_1"), bad.deviceDataSubs)
        val u = java.net.URI("https://sub.example/s")
        assertTrue(SubscriptionHeaders.create(some, u, u, "16", "Pixel", "abc").containsKey("x-hwid"))
        assertFalse(SubscriptionHeaders.create(some, u, u, "16", "Pixel", "other").containsKey("x-hwid"))
        assertTrue(SubscriptionHeaders.create(all, u, u, "16", "Pixel", "other").containsKey("x-hwid"))
    }

    @Test fun splitAllAppsRoundTrip() {
        assertFalse(ClientSettings().splitAllApps)
        assertTrue(ClientSettings.from(ClientSettings(splitAllApps = true).json()).splitAllApps)
    }

    @Test fun russianSitesDirectlyIsOffByDefault() {
        assertFalse(ClientSettings().directRu)
        assertFalse(ClientSettings.from(ClientSettings().json().apply { remove("directRu") }).directRu)
        assertTrue(ClientSettings.from(ClientSettings(directRu = true).json()).directRu)
    }
}
