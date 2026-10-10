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
            "vmess://" + Base64.getEncoder().encodeToString(vmess.toString().toByteArray())
        )
        val directory = File("build/config-fixtures").apply { mkdirs() }
        links.forEachIndexed { index, uri -> File(directory, "$index.json").writeText(TunnelConfig.create(Subscriptions.parseLink(uri))) }
    }
}
