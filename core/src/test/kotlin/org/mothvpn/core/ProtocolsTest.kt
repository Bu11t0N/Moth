package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.*

/** 0.5.4 parsers: SOCKS5 / HTTP(S) / WireGuard / SSH links, Clash, sing-box JSON, full Xray JSON and deep links (synthetic data). */
class ProtocolsTest {
    private fun out(p: Profile) = JSONObject(p.outbound)
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private val wgPriv = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val wgPub = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    @Test fun socksLinkVariants() {
        val plain = Subscriptions.parseLink("socks5://user%40mail:p%3Ass%20w@1.2.3.4:1080#My%20SOCKS")
        assertEquals("socks", plain.protocol); assertEquals("My SOCKS", plain.name)
        out(plain).let { assertEquals("socks", it.getString("type")); assertEquals("user@mail", it.getString("username")); assertEquals("p:ss w", it.getString("password")); assertEquals("5", it.getString("version")); assertEquals(1080, it.getInt("server_port")) }
        // v2rayNG: base64(user:pass) as userinfo, even with '/' and '+' in the base64 text.
        val tricky = "us?r" to "p>w/+?"
        val b = Subscriptions.parseLink("socks://${b64(tricky.first + ":" + tricky.second)}@proxy.example:1081#N")
        out(b).let { assertEquals(tricky.first, it.getString("username")); assertEquals(tricky.second, it.getString("password")); assertEquals("proxy.example", it.getString("server")) }
        // Legacy: the whole user:pass@host:port in base64.
        val legacy = Subscriptions.parseLink("socks://${b64("alice:secret@[2001:db8::1]:9050")}#Old")
        out(legacy).let { assertEquals("alice", it.getString("username")); assertEquals("secret", it.getString("password")); assertEquals("2001:db8::1", it.getString("server")); assertEquals(9050, it.getInt("server_port")) }
        // No credentials at all, and the legacy form without them.
        out(Subscriptions.parseLink("socks://10.0.0.1:1080")).let { assertFalse(it.has("username")); assertFalse(it.has("password")) }
        out(Subscriptions.parseLink("socks://${b64("10.0.0.2:1080")}")).let { assertEquals("10.0.0.2", it.getString("server")); assertFalse(it.has("username")) }
        assertEquals("bob", out(Subscriptions.parseLink("socks5://bob@h.example:1080")).getString("username"))
        assertTrue(ProxyProtocols.unencrypted(plain))
        assertFailsWith<Exception> { Subscriptions.parseLink("socks5://u:p@h.example:70000") }
        assertNotEquals(plain.id, Subscriptions.parseLink("socks5://user%40mail:other@1.2.3.4:1080#My%20SOCKS").id)
        assertEquals(plain.id, Subscriptions.parseLink("socks5://user%40mail:p%3Ass%20w@1.2.3.4:1080#Renamed").id)
    }

    @Test fun httpProxyLinks() {
        val http = Subscriptions.parseLink("http://u:p%40ss@proxy.example:3128#Office")
        assertEquals("http", http.protocol); assertTrue(ProxyProtocols.unencrypted(http))
        out(http).let { assertEquals("http", it.getString("type")); assertEquals("p@ss", it.getString("password")); assertFalse(it.has("tls")) }
        val https = Subscriptions.parseLink("https://u:p@proxy.example:8443?sni=front.example#Secure")
        assertEquals("https", https.protocol); assertFalse(ProxyProtocols.unencrypted(https))
        out(https).getJSONObject("tls").let { assertTrue(it.getBoolean("enabled")); assertEquals("front.example", it.getString("server_name")) }
        assertEquals(443, out(Subscriptions.parseLink("https://u:p@proxy.example")).getInt("server_port"))
        assertFailsWith<Exception> { Subscriptions.parseLink("https://u:p@proxy.example:443?allowInsecure=1") }
        assertFailsWith<Exception> { Subscriptions.parseLink("http://t.me/channel") }
    }

    @Test fun importInputSeparatesProxiesFromSubscriptions() {
        assertIs<ImportInput.Kind.Subscription>(ImportInput.classify("https://provider.example/sub/abc"))
        assertIs<ImportInput.Kind.Links>(ImportInput.classify("https://user:pass@proxy.example:443"))
        assertIs<ImportInput.Kind.Links>(ImportInput.classify("http://proxy.example:3128"))
        assertIs<ImportInput.Kind.Links>(ImportInput.classify("socks5://a:b@h.example:1\nwg://h.example:1?pk=x"))
        assertIs<ImportInput.Kind.Invalid>(ImportInput.classify("http://example.com/page"))
        val mixed = Subscriptions.parse("socks5://a:b@h.example:1080\nhttp://h.example:8080\nssh://root:pw@h.example:22")
        assertEquals(listOf("socks", "http", "ssh"), mixed.profiles.map { it.protocol })
    }

    @Test fun wireguardLinks() {
        val link = "wireguard://${enc(wgPriv)}@wg.example:51820?publickey=${enc(wgPub)}&presharedkey=${enc(wgPub)}&address=10.0.0.2/32,fd00::2&reserved=1,2,3&mtu=1408#WG"
        val p = Subscriptions.parseLink(link)
        assertEquals("wireguard", p.protocol); assertEquals("WG", p.name); assertFalse(ProxyProtocols.unencrypted(p))
        val o = out(p)
        assertEquals("wireguard", o.getString("type")); assertEquals(wgPriv, o.getString("private_key")); assertEquals(1408, o.getInt("mtu"))
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), o.getJSONArray("address").let { a -> (0 until a.length()).map(a::getString) })
        val peer = o.getJSONArray("peers").getJSONObject(0)
        assertEquals("wg.example", peer.getString("address")); assertEquals(51820, peer.getInt("port")); assertEquals(wgPub, peer.getString("public_key"))
        assertEquals("[1,2,3]", peer.getJSONArray("reserved").toString())
        val h = Subscriptions.parseLink("wg://wg.example:443?pk=${enc(wgPriv)}&peer_pk=${enc(wgPub)}&local_address=172.16.0.2/32&reserved=${enc(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))}")
        assertEquals("[1,2,3]", out(h).getJSONArray("peers").getJSONObject(0).getJSONArray("reserved").toString())
        val config = JSONObject(TunnelConfig.create(p))
        assertEquals("proxy", config.getJSONArray("endpoints").getJSONObject(0).getString("tag"))
        assertTrue((0 until config.getJSONArray("outbounds").length()).none { config.getJSONArray("outbounds").getJSONObject(it).optString("type") == "wireguard" })
        assertEquals("AmneziaWG не поддерживается", assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink(link.replace("#WG", "&jc=4&jmin=40#WG")) }.message)
        assertFailsWith<Exception> { Subscriptions.parseLink("wireguard://short@wg.example:1?publickey=${enc(wgPub)}&address=10.0.0.2") }
        val shared = ShareLinks.of(p.copy(link = ""))!!
        assertTrue(shared.startsWith("wireguard://"))
        assertEquals(out(p).toString(), out(Subscriptions.parseLink(shared)).toString())
    }

    @Test fun sshLinks() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"
        val hk = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUF"
        val p = Subscriptions.parseLink("ssh://root:pw@ssh.example:2222?pk=${enc(b64(key))}&hk=${URLEncoder.encode(hk, "UTF-8")}#SSH")
        val o = out(p)
        assertEquals("ssh", o.getString("type")); assertEquals("root", o.getString("user")); assertEquals(2222, o.getInt("server_port"))
        assertEquals(key, o.getString("private_key")); assertEquals(hk, o.getJSONArray("host_key").getString(0))
        assertEquals(22, out(Subscriptions.parseLink("ssh://u:p@ssh.example")).getInt("server_port"))
        assertFailsWith<Exception> { Subscriptions.parseLink("ssh://u@ssh.example:22") }
        assertFailsWith<Exception> { Subscriptions.parseLink("ssh://u:p@ssh.example:22?hk=not-a-key") }
    }

    @Test fun clashNewTypesAndShadowTls() {
        val yaml = """
proxies:
  - {name: S5, type: socks5, server: s.example, port: 1080, username: u, password: p, udp: true}
  - {name: H, type: http, server: h.example, port: 443, username: u, password: p, tls: true, sni: front.example}
  - {name: W, type: wireguard, server: w.example, port: 51820, ip: 10.0.0.2, ipv6: "fd00::2", private-key: "$wgPriv", public-key: "$wgPub", reserved: [1, 2, 3], mtu: 1400}
  - {name: SSH, type: ssh, server: ssh.example, port: 22, username: root, password: pw}
  - {name: STLS, type: ss, server: st.example, port: 443, cipher: 2022-blake3-aes-128-gcm, password: "AAAAAAAAAAAAAAAAAAAAAA==", plugin: shadow-tls, client-fingerprint: chrome, plugin-opts: {host: cloud.example, password: stpw, version: 3}}
  - {name: S5TLS, type: socks5, server: s.example, port: 1080, tls: true}
  - {name: AWG, type: wireguard, server: w.example, port: 51820, ip: 10.0.0.2, private-key: "$wgPriv", public-key: "$wgPub", amnezia-wg-option: {jc: 4}}
  - {name: A, type: anytls, server: a.example, port: 443, password: x, sni: front.example, client-fingerprint: chrome}
  - {name: OBFS, type: ss, server: o.example, port: 443, cipher: aes-128-gcm, password: x, plugin: obfs}
"""
        val r = Subscriptions.parse(yaml)
        assertEquals(listOf("S5", "H", "W", "SSH", "STLS", "A"), r.profiles.map { it.name })
        assertEquals(3, r.rejected)
        assertTrue(r.reasons.any { it.contains("AmneziaWG") })
        assertEquals(listOf("socks", "https", "wireguard", "ssh", "ss + shadowtls", "anytls"), r.profiles.map { it.protocol })
        out(r.profiles[5]).let { assertEquals("x", it.getString("password")); assertEquals("front.example", it.getJSONObject("tls").getString("server_name")) }
        val stls = out(r.profiles[4])
        assertEquals("proxy-shadowtls", stls.getString("detour"))
        val hop = stls.getJSONArray(SingBoxOutbounds.CHAIN).getJSONObject(0)
        assertEquals(3, hop.getInt("version")); assertEquals("stpw", hop.getString("password")); assertEquals("cloud.example", hop.getJSONObject("tls").getString("server_name"))
        val config = JSONObject(TunnelConfig.create(r.profiles[4]))
        val tags = config.getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tag") } }
        assertEquals(listOf("proxy", "proxy-shadowtls", "direct"), tags)
        assertFalse(config.toString().contains(SingBoxOutbounds.CHAIN))
        assertEquals(r.profiles.map { it.id }, Subscriptions.parse(yaml).profiles.map { it.id })
    }

    @Test fun clashProxyProviders() {
        val yaml = """
proxy-providers:
  first: {type: http, url: "https://p.example/a", interval: 3600}
  local: {type: file, path: ./x.yaml}
  plain: {type: http, url: "http://p.example/b"}
proxies:
  - {name: S5, type: socks5, server: s.example, port: 1080}
"""
        val fetched = mutableListOf<String>()
        val r = Subscriptions.parse(yaml) { url -> fetched += url; "proxies:\n  - {name: P1, type: socks5, server: p.example, port: 1}\n" }
        assertEquals(listOf("https://p.example/a"), fetched)
        assertEquals(listOf("S5", "P1"), r.profiles.map { it.name })
        assertEquals(2, r.rejected)
        val links = Subscriptions.parse("proxy-providers:\n  x: {type: http, url: \"https://p.example/l\"}\n") { b64("socks5://p.example:2#L\n") }
        assertEquals(listOf("L"), links.profiles.map { it.name })
        assertFailsWith<IllegalArgumentException> { Subscriptions.parse("proxy-providers:\n  x: {type: http, url: \"https://p.example/l\"}\n") }
        val partial = Subscriptions.parse(yaml) { error("boom") }
        assertEquals(listOf("S5"), partial.profiles.map { it.name })
    }

    @Test fun singBoxJson() {
        val doc = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("type", "selector").put("tag", "select").put("outbounds", JSONArray(listOf("vless-a"))))
            .put(JSONObject().put("type", "vless").put("tag", "vless-a").put("server", "v.example").put("server_port", 443).put("uuid", XrayJsonFixtures.UUID1)
                .put("flow", "xtls-rprx-vision").put("tls", JSONObject().put("enabled", true).put("server_name", "v.example").put("certificate_path", "/etc/x")
                    .put("reality", JSONObject().put("enabled", true).put("public_key", XrayJsonFixtures.KEY).put("short_id", "ab"))))
            .put(JSONObject().put("type", "socks").put("tag", "socks-b").put("server", "s.example").put("server_port", 1080).put("username", "u").put("password", "p"))
            .put(JSONObject().put("type", "shadowsocks").put("tag", "ss-st").put("server", "st.example").put("server_port", 443).put("method", "2022-blake3-aes-128-gcm")
                .put("password", "AAAAAAAAAAAAAAAAAAAAAA==").put("detour", "st"))
            .put(JSONObject().put("type", "shadowtls").put("tag", "st").put("server", "st.example").put("server_port", 443).put("version", 3).put("password", "x")
                .put("tls", JSONObject().put("enabled", true).put("server_name", "cloud.example")))
            .put(JSONObject().put("type", "trojan").put("tag", "bad").put("server", "t.example").put("server_port", 443).put("password", "p")
                .put("tls", JSONObject().put("enabled", true).put("insecure", true)))
            .put(JSONObject().put("type", "anytls").put("tag", "any").put("server", "a.example").put("server_port", 443))
            .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("endpoints", JSONArray().put(JSONObject().put("type", "wireguard").put("tag", "wg").put("address", JSONArray(listOf("10.0.0.2/32"))).put("private_key", wgPriv)
                .put("peers", JSONArray().put(JSONObject().put("address", "wg.example").put("port", 51820).put("public_key", wgPub).put("allowed_ips", JSONArray(listOf("0.0.0.0/0")))))))
        assertTrue(SingBoxSubscriptions.looksLike(doc.toString()))
        val r = Subscriptions.parse(doc.toString())
        assertEquals(listOf("vless-a", "socks-b", "ss-st", "wg"), r.profiles.map { it.name })
        assertEquals(2, r.rejected)
        out(r.profiles[0]).getJSONObject("tls").let { assertFalse(it.has("certificate_path")); assertTrue(it.has("reality")) }
        assertEquals("ss + shadowtls", r.profiles[2].protocol)
        assertEquals("wireguard", r.profiles[3].protocol)
        assertFalse(SingBoxSubscriptions.looksLike(XrayJsonFixtures.allProtocols()))
    }

    @Test fun xrayJsonAllProtocols() {
        val r = Subscriptions.parse(XrayJsonFixtures.allProtocols())
        assertEquals(0, r.rejected, r.reasons.joinToString())
        assertEquals(listOf("VMess", "Trojan", "SS", "SOCKS", "HTTPS", "WG"), r.profiles.map { it.name })
        assertEquals(listOf("xray", "xray", "xray", "xray", "xray", "sing-box"), r.profiles.map { it.engine })
        assertTrue(r.profiles[0].protocol.startsWith("vmess · ws · tls"), r.profiles[0].protocol)
        assertTrue(r.profiles[3].protocol.startsWith("socks"), r.profiles[3].protocol); assertTrue(ProxyProtocols.unencrypted(r.profiles[3]))
        assertTrue(r.profiles[4].protocol.startsWith("https"), r.profiles[4].protocol); assertFalse(ProxyProtocols.unencrypted(r.profiles[4]))
        out(r.profiles[3]).getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getJSONArray("users").getJSONObject(0).let { assertEquals("u", it.getString("user")) }
        assertEquals("[1,2,3]", out(r.profiles[5]).getJSONArray("peers").getJSONObject(0).getJSONArray("reserved").toString())
    }

    @Test fun deepLinks() {
        val sub = "https://provider.example/s/abc?token=1"
        fun p(s: String?) = DeepLinks.parse(s)
        assertEquals(DeepLinks.Action.Subscription(sub, "Home"), p("moth://import-sub?url=${enc(sub)}&name=Home"))
        assertEquals(DeepLinks.Action.Subscription(sub, "N"), p("v2rayng://install-sub?url=${enc(sub)}&name=N"))
        assertEquals(DeepLinks.Action.Subscription(sub, null), p("happ://add/$sub"))
        assertEquals(DeepLinks.Action.Subscription(sub, "Hid"), p("hiddify://import/$sub#Hid"))
        assertEquals(DeepLinks.Action.Subscription(sub, "SB"), p("sing-box://import-remote-profile?url=${enc(sub)}#SB"))
        val vless = "vless://${XrayJsonFixtures.UUID1}@v.example:443?security=tls#V"
        assertEquals(DeepLinks.Action.Links(vless), p("moth://import?link=${enc(vless)}"))
        assertEquals(DeepLinks.Action.Links(vless), p(vless))
        assertEquals(DeepLinks.Action.Links(vless), p("v2rayng://install-config?url=${enc(vless)}"))
        assertEquals(DeepLinks.Action.Links(vless), p("v2rayng://install-config?url=${enc(b64(vless))}"))
        assertEquals(DeepLinks.Action.Links("socks5://u:p@s.example:1080"), p("socks5://u:p@s.example:1080"))
        assertNull(p("moth://import-sub?url=${enc("http://provider.example/s")}"))
        assertNull(p("moth://import-sub?url=${enc("https://u:p@provider.example/s")}"))
        assertNull(p("moth://delete-all"))
        assertNull(p("happ://crypt3/abcdef"))
        assertNull(p("https://example.com"))
        assertNull(p(null))
        assertNull((p("moth://import-sub?url=${enc(sub)}&name=${enc("a\u0007b")}") as DeepLinks.Action.Subscription).name)
        assertEquals("provider.example", DeepLinks.host(sub))
    }

    @Test fun dpiMuxAndGuardConfigs() {
        val trojan = Subscriptions.parseLink("trojan://pw@t.example:443?security=tls#T")
        val s = ClientSettings(fragment = "tlshello", noise = true, mux = "smux", splitMode = "bypass", splitApps = listOf("com.bank.app"))
        val c = JSONObject(TunnelConfig.create(trojan, s, dpi = LocalHop(4321, XrayJsonFixtures.UUID1), appGuard = "org.mothvpn.android"))
        val outs = c.getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val proxy = outs.first { it.getString("tag") == "proxy" }
        assertEquals(DpiTricks.TAG, proxy.getString("detour"))
        assertEquals("smux", proxy.getJSONObject("multiplex").getString("protocol"))
        outs.first { it.getString("tag") == DpiTricks.TAG }.let { assertEquals("vless", it.getString("type")); assertEquals("127.0.0.1", it.getString("server")); assertEquals("xudp", it.getString("packet_encoding")) }
        val rules = c.getJSONObject("route").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val dns = rules.indexOfFirst { it.optString("action") == "hijack-dns" }
        val guard = rules.indexOfFirst { it.has("package_name") }
        assertTrue(guard > dns)
        assertEquals("[\"com.bank.app\"]", rules[guard].getJSONArray("package_name").toString()); assertEquals("reject", rules[guard].getString("action"))
        val only = JSONObject(TunnelConfig.create(trojan, ClientSettings(splitMode = "only", splitApps = listOf("a.b")), appGuard = "org.mothvpn.android"))
            .getJSONObject("route").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.has("package_name") } }
        assertTrue(only.getBoolean("invert")); assertEquals(listOf("a.b", "org.mothvpn.android"), only.getJSONArray("package_name").let { a -> (0 until a.length()).map(a::getString) })
        assertFalse(TunnelConfig.create(trojan, s).contains("package_name"))
        assertFalse(TunnelConfig.create(trojan, ClientSettings(), appGuard = "org.mothvpn.android").contains("package_name"))
        assertFalse(TunnelConfig.create(trojan).contains("multiplex")); assertFalse(TunnelConfig.create(trojan).contains(DpiTricks.TAG))
        val vision = Subscriptions.parseLink("vless://${XrayJsonFixtures.UUID1}@v.example:443?security=tls&flow=xtls-rprx-vision")
        assertFalse(TunnelConfig.create(vision, s).contains("multiplex"))
        val helper = JSONObject(LocalProxyConfig.dpiHelper(4321, XrayJsonFixtures.UUID1, s))
        helper.getJSONArray("inbounds").getJSONObject(0).let { assertEquals("vless", it.getString("protocol")); assertEquals("127.0.0.1", it.getString("listen")) }
        helper.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").let {
            assertEquals("tlshello", it.getJSONObject("fragment").getString("packets")); assertEquals("rand", it.getJSONArray("noises").getJSONObject(0).getString("type")) }
        assertFalse(LocalProxyConfig.dpiHelper(1, XrayJsonFixtures.UUID1, ClientSettings(noise = true)).contains("fragment"))
    }

    @Test fun localListenersAreAuthenticatedAndTcpOnly() {
        val xhttp = Subscriptions.parseLink("vless://${XrayJsonFixtures.UUID1}@x.example:443?type=xhttp&security=tls&path=%2Fp")
        val session = "22222222-2222-4222-8222-222222222222"
        val bridge = JSONObject(LocalProxyConfig.xray(xhttp, 1234, session, "", bridge = true))
        bridge.getJSONArray("inbounds").getJSONObject(0).let {
            assertEquals("vless", it.getString("protocol")); assertEquals("127.0.0.1", it.getString("listen"))
            assertEquals(session, it.getJSONObject("settings").getJSONArray("clients").getJSONObject(0).getString("id"))
        }
        assertFalse(bridge.toString().contains("\"socks\""))
        val sb = JSONObject(LocalProxyConfig.bridge(xhttp, 1234, session).outbound)
        assertEquals("vless", sb.getString("type")); assertEquals("xudp", sb.getString("packet_encoding"))
        val tunnel = JSONObject(TunnelConfig.create(LocalProxyConfig.bridge(xhttp, 1234, session), ClientSettings(mux = "smux", fragment = "tlshello"), dpi = LocalHop(9, session)))
        assertFalse(tunnel.toString().contains("multiplex")); assertFalse(tunnel.toString().contains(DpiTricks.TAG)); assertFalse(tunnel.toString().contains(SingBoxOutbounds.BRIDGE))
        val probe = JSONObject(LocalProxyConfig.xray(xhttp, 1235, "u", "p"))
        probe.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").let { assertEquals("password", it.getString("auth")); assertFalse(it.getBoolean("udp")) }
        val sbProbe = JSONObject(LocalProxyConfig.singBox(Subscriptions.parseLink("trojan://pw@t.example:443"), 1236, "u", "p"))
        assertEquals("u", sbProbe.getJSONArray("inbounds").getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("username"))
        assertEquals("udp", sbProbe.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("network"))
        val ws = Subscriptions.parse(JSONArray().put(JSONObject().put("outbounds", JSONArray().put(XrayJsonFixtures.ws()))).toString()).profiles.first()
        val x = JSONObject(LocalProxyConfig.xray(ws, 1, session, "", ClientSettings(mux = "h2mux", fragment = "first_packets"), bridge = true))
        val proxy = x.getJSONArray("outbounds").getJSONObject(0)
        assertTrue(proxy.getJSONObject("mux").getBoolean("enabled"))
        assertEquals(DpiTricks.TAG, proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals(DpiTricks.TAG, x.getJSONArray("outbounds").getJSONObject(1).getString("tag"))
        assertFalse(JSONObject(LocalProxyConfig.xray(xhttp, 1, session, "", ClientSettings(mux = "h2mux"), bridge = true)).getJSONArray("outbounds").getJSONObject(0).has("mux"))
    }

    @Test fun settingsRoundTripAndValidation() {
        val s = ClientSettings(fragment = "tlshello_small", noise = true, mux = "yamux")
        assertEquals(s, ClientSettings.from(s.json()))
        val defaults = ClientSettings.from(ClientSettings().json().apply { remove("fragment"); remove("noise"); remove("mux") })
        assertEquals("off", defaults.fragment); assertFalse(defaults.noise); assertEquals("off", defaults.mux)
        assertEquals("off", ClientSettings.from(s.json().put("fragment", "nope").put("mux", "nope")).fragment)
        assertFailsWith<IllegalArgumentException> { ClientSettings(fragment = "x").validate() }
        assertFailsWith<IllegalArgumentException> { ClientSettings(mux = "x").validate() }
        assertTrue(LogFacts.settingsChanges(ClientSettings(), s).containsAll(listOf("fragment=off->tlshello_small", "noise=false->true", "mux=off->yamux")))
    }

    @Test fun shareLinksForNewTypes() {
        listOf("socks5://u%3Ax:p@s.example:1080#S", "http://u:p@h.example:8080#H", "https://u:p@h.example:8443?sni=f.example#HS", "ssh://root:pw@ssh.example:22#SSH").forEach { link ->
            val p = Subscriptions.parseLink(link)
            val again = Subscriptions.parseLink(ShareLinks.of(p.copy(link = ""))!!)
            assertEquals(out(p).toString(), out(again).toString(), link)
        }
    }

    @Test fun e2eSubscriptionLinksParse() {
        val links = """socks5://moth-user:moth%20p%40ss@10.0.2.2:20004#E2E%20SOCKS5
http://moth-user:moth%20p%40ss@10.0.2.2:20005#E2E%20HTTP
wireguard://2E56FQv7AvD1c0XspyCoR294%2BUvV3XsTFgh10swBS2I%3D@10.0.2.2:20006?publickey=GbOmk5x5V9XRtDZQPaBtmxuI99wgeSYorhVHQPq%2FvgM%3D&address=10.66.0.2%2F32&mtu=1280#E2E%20WireGuard"""
        val parsed = Subscriptions.parse(java.util.Base64.getEncoder().encodeToString(links.toByteArray()))
        assertEquals(listOf("E2E SOCKS5", "E2E HTTP", "E2E WireGuard"), parsed.profiles.map { it.name })
        assertTrue(parsed.profiles[0].outbound.contains("moth p@ss"))
        assertTrue(ProxyProtocols.unencrypted(parsed.profiles[0]) && ProxyProtocols.unencrypted(parsed.profiles[1]))
        assertFalse(ProxyProtocols.unencrypted(parsed.profiles[2]))
    }

    @Test fun anytlsLinks() {
        val p = Subscriptions.parseLink("anytls://p%40ss@a.example:8443?sni=front.example&alpn=h2,http%2F1.1&fp=chrome#Any%20TLS")
        assertEquals("Any TLS", p.name); assertEquals("anytls", p.protocol)
        val o = JSONObject(p.outbound)
        assertEquals("anytls", o.getString("type")); assertEquals("p@ss", o.getString("password")); assertEquals(8443, o.getInt("server_port"))
        assertEquals("front.example", o.getJSONObject("tls").getString("server_name"))
        assertEquals("chrome", o.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        assertFailsWith<IllegalArgumentException> { Subscriptions.parseLink("anytls://p@a.example:443?insecure=1") }
        assertFailsWith<IllegalStateException> { Subscriptions.parseLink("anytls://a.example:443") }
        val back = ShareLinks.of(p.copy(link = ""))!!
        assertEquals(JSONObject(p.outbound).toString(), JSONObject(Subscriptions.parseLink(back).outbound).toString())
        assertTrue(DeepLinks.parse("anytls://p@a.example:443#A") is DeepLinks.Action.Links)
        val sb = JSONObject().put("outbounds", JSONArray().put(JSONObject().put("type", "anytls").put("tag", "any").put("server", "a.example").put("server_port", 443)
            .put("password", "x").put("tls", JSONObject().put("enabled", true).put("server_name", "a.example"))))
        assertEquals(listOf("anytls"), Subscriptions.parse(sb.toString()).profiles.map { it.protocol })
    }
}
