package org.routevpn.core

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
        File(directory, "bridge.json").writeText(TunnelConfig.create(LocalProxyConfig.bridge(xhttp, 18081, "test", "test"), ClientSettings(ipv6 = false, dns = "https://dns.google/dns-query")))
        File(directory, "probe.json").writeText(LocalProxyConfig.singBox(Subscriptions.parseLink(links[4]), 18082, "test", "test"))
    }
}
