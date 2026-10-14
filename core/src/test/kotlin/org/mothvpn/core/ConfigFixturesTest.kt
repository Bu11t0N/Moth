package org.mothvpn.core

import java.io.File
import java.util.Base64
import org.json.JSONObject
import kotlin.test.Test

class ConfigFixturesTest {
    @Test fun writeConfigsForNativeValidation() {
        val id = "11111111-1111-4111-8111-111111111111"
        val vmess = JSONObject().put("id", id).put("add", "vpn.example").put("port", "443").put("net", "ws").put("tls", "tls").put("path", "/vpn")
        val links = listOf(
            "vless://$id@vpn.example:443?security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sni=example.org&flow=xtls-rprx-vision&type=tcp",
            "trojan://test-password@vpn.example:443?security=tls&type=grpc&serviceName=vpn",
            "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@vpn.example:8388",
            "vmess://" + Base64.getEncoder().encodeToString(vmess.toString().toByteArray()),
            "hy2://test-password@vpn.example:443?obfs=salamander&obfs-password=test-obfs",
            "hysteria://vpn.example:443?auth=test-password&upmbps=20&downmbps=100",
            "tuic://$id:test-password@vpn.example:443?congestion_control=bbr&alpn=h3"
        )
        val directory = File("build/config-fixtures").apply { mkdirs() }
        links.forEachIndexed { index, uri -> File(directory, "$index.json").writeText(TunnelConfig.create(Subscriptions.parseLink(uri))) }
        val xhttp = Subscriptions.parseLink("vless://$id@vpn.example:443?type=xhttp&security=tls&sni=example.org&path=%2Fxhttp")
        File(directory, "xray.json").writeText(LocalProxyConfig.xray(xhttp, 18081, "test", "test"))
        File(directory, "bridge.json").writeText(TunnelConfig.create(LocalProxyConfig.bridge(xhttp, 18081, id), ClientSettings(ipv6 = false, dns = "https://dns.google/dns-query")))
        File(directory, "probe.json").writeText(LocalProxyConfig.singBox(Subscriptions.parseLink(links[4]), 18082, "test", "test"))
        val custom = ClientSettings(bootstrapDns = "https://8.8.8.8/dns-query")
        File(directory, "custom-bootstrap.json").writeText(TunnelConfig.create(Subscriptions.parseLink(links[4]), custom))
        File(directory, "custom-probe.json").writeText(LocalProxyConfig.singBox(Subscriptions.parseLink(links[4]), 18083, "test", "test", custom))
        val group = Subscriptions.parse(XrayJsonFixtures.balancerDocument()).profiles.first()
        File(directory, "xray-group.json").writeText(LocalProxyConfig.xray(group, 18085, "test", "test"))
        val rules = ClientSettings(routingRules = listOf(RoutingRule("domain_suffix", "example.com", "direct"), RoutingRule("ip_cidr", "10.0.0.0/8", "block"),
            RoutingRule("domain_keyword", "ads", "block"), RoutingRule("domain", "vpn.example.org", "proxy")), directRu = true)
        File(directory, "rules.json").writeText(TunnelConfig.create(Subscriptions.parseLink(links[1]), rules))
        File(directory, "xray-rules.json").writeText(LocalProxyConfig.xray(xhttp, 18086, "test", "test", rules))
        File(directory, "xray-group-rules.json").writeText(LocalProxyConfig.xray(group, 18087, "test", "test", rules))
        File(directory, "xray-custom.json").writeText(LocalProxyConfig.xray(xhttp, 18084, "test", "test", custom))
        // 0.5.4: proxies, WireGuard, SSH, ShadowTLS, DPI helper, multiplexing, split-tunnel guard and the VLESS bridge.
        val wgKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }); val wgPeer = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val extra = listOf(
            "socks5://user%40x:p%3Ass@vpn.example:1080#S", "http://u:p@vpn.example:8080", "https://u:p@vpn.example:443?sni=example.org",
            "wireguard://${java.net.URLEncoder.encode(wgKey, "UTF-8")}@vpn.example:51820?publickey=${java.net.URLEncoder.encode(wgPeer, "UTF-8")}&address=10.7.0.2/32,fd00::2/128&reserved=1,2,3&mtu=1408",
            "ssh://root:secret@vpn.example:22?hk=${java.net.URLEncoder.encode("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUF", "UTF-8").replace("+", "%20")}")
        extra.forEachIndexed { index, uri -> File(directory, "extra-$index.json").writeText(TunnelConfig.create(Subscriptions.parseLink(uri))) }
        val stls = Subscriptions.parse("proxies:\n  - {name: t, type: ss, server: vpn.example, port: 443, cipher: 2022-blake3-aes-128-gcm, password: AAAAAAAAAAAAAAAAAAAAAA==, plugin: shadow-tls, plugin-opts: {host: example.org, password: pw, version: 3}}\n").profiles.first()
        File(directory, "shadowtls.json").writeText(TunnelConfig.create(stls))
        val dpiSettings = ClientSettings(fragment = "tlshello", noise = true, mux = "h2mux", splitMode = "bypass", splitApps = listOf("com.example.app"))
        val hop = LocalHop(18090, id)
        File(directory, "dpi-tunnel.json").writeText(TunnelConfig.create(Subscriptions.parseLink(links[1]), dpiSettings, dpi = hop, appGuard = "org.mothvpn.android"))
        File(directory, "dpi-wireguard.json").writeText(TunnelConfig.create(Subscriptions.parseLink(extra[3]), dpiSettings, dpi = hop, appGuard = "org.mothvpn.android"))
        File(directory, "dpi-probe.json").writeText(LocalProxyConfig.singBox(Subscriptions.parseLink(links[1]), 18091, "test", "test", dpiSettings, hop))
        File(directory, "only-guard.json").writeText(TunnelConfig.create(Subscriptions.parseLink(links[2]), ClientSettings(splitMode = "only", splitApps = listOf("com.example.app")), appGuard = "org.mothvpn.android"))
        val any = Subscriptions.parseLink("anytls://pw@a.example:443?sni=front.example&fp=chrome#Any")
        File(directory, "anytls.json").writeText(TunnelConfig.create(any, ClientSettings(fragment = "tlshello_small", mux = "smux")))
        File(directory, "native-fragment.json").writeText(TunnelConfig.create(Subscriptions.parseLink(links[1]), ClientSettings(fragment = "tlshello", dns = "tls://dns.example", bootstrapDns = "https://dns.google/dns-query")))
        File(directory, "native-fragment-probe.json").writeText(LocalProxyConfig.singBox(any, 18094, "u", "p", ClientSettings(fragment = "tlshello")))
        File(directory, "xray-dpi-helper.json").writeText(LocalProxyConfig.dpiHelper(18090, id, dpiSettings))
        File(directory, "xray-bridge.json").writeText(LocalProxyConfig.xray(xhttp, 18092, id, "", dpiSettings, bridge = true))
        File(directory, "xray-group-bridge.json").writeText(LocalProxyConfig.xray(group, 18093, id, "", dpiSettings.copy(routingRules = rules.routingRules), bridge = true))
        val xrayAll = Subscriptions.parse(XrayJsonFixtures.allProtocols()).profiles
        xrayAll.forEachIndexed { index, p ->
            File(directory, "xray-all-$index.json").writeText(if (p.engine == "xray") LocalProxyConfig.xray(p, 18100 + index, id, "", dpiSettings, bridge = true) else TunnelConfig.create(p))
        }
    }
}
