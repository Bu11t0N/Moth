package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Synthetic Xray JSON documents: fake ids, keys and example.com hosts only. */
object XrayJsonFixtures {
    const val UUID1 = "11111111-1111-4111-8111-111111111111"
    const val KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun vless(tag: String, host: String, stream: JSONObject, flow: String = "", simplified: Boolean = false): JSONObject {
        val settings = if (simplified) JSONObject().put("address", host).put("port", 443).put("id", UUID1).put("encryption", "none").put("flow", flow)
        else JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", 443)
            .put("users", JSONArray().put(JSONObject().put("id", UUID1).put("encryption", "none").put("flow", flow)))))
        return JSONObject().put("tag", tag).put("protocol", "vless").put("settings", settings).put("streamSettings", stream)
    }
    fun reality(network: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("network", network).put("security", "reality")
        .put("realitySettings", JSONObject().put("serverName", "front.example.com").put("publicKey", KEY).put("shortId", "ab12").put("spiderX", "/x").put("fingerprint", "chrome")).apply(extra)
    fun tls(network: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("network", network).put("security", "tls")
        .put("tlsSettings", JSONObject().put("serverName", "tls.example.com").put("fingerprint", "chrome").put("alpn", JSONArray(listOf("h2", "http/1.1")))).apply(extra)
    fun tcpReality(tag: String = "proxy", host: String = "a.example.com") = vless(tag, host, reality("tcp") { put("tcpSettings", JSONObject()) }, "xtls-rprx-vision")
    fun grpcReality(tag: String = "proxy", host: String = "g.example.com") = vless(tag, host, reality("grpc") { put("grpcSettings", JSONObject().put("serviceName", "svc").put("authority", "auth.example.com").put("mode", true)) }, simplified = true)
    fun ws(tag: String = "proxy", header: String = "X-Whitelist-Header", value: String = "abc") = vless(tag, "w.example.com", tls("ws") {
        put("wsSettings", JSONObject().put("path", "/ws").put("host", "ws.example.com").put("headers", JSONObject().put(header, value)).put("heartbeatPeriod", 20)) })
    fun xhttp(tag: String = "proxy") = vless(tag, "x.example.com", tls("xhttp") { put("xhttpSettings", JSONObject().put("mode", "auto").put("host", "x.example.com").put("path", "/p")) })
    fun hysteria(tag: String = "proxy", insecure: Boolean = false) = JSONObject().put("tag", tag).put("protocol", "hysteria")
        .put("settings", JSONObject().put("version", 2).put("address", "h.example.com").put("port", 443))
        .put("streamSettings", JSONObject().put("network", "hysteria").put("security", "tls")
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", "test-auth"))
            .put("tlsSettings", JSONObject().put("serverName", "h.example.com").put("alpn", JSONArray(listOf("h3"))).put("allowInsecure", insecure)))
    private fun infra() = listOf(JSONObject().put("tag", "direct").put("protocol", "freedom"), JSONObject().put("tag", "block").put("protocol", "blackhole"))
    fun config(remarks: String, vararg proxies: JSONObject, balancer: Boolean = false): JSONObject {
        val outbounds = JSONArray(); proxies.forEach { outbounds.put(it) }; infra().forEach { outbounds.put(it) }
        val doc = JSONObject().put("remarks", remarks).put("outbounds", outbounds)
            .put("inbounds", JSONArray().put(JSONObject().put("port", 10808).put("protocol", "socks")))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1")))
            .put("routing", JSONObject().put("rules", JSONArray().put(JSONObject().put("domain", JSONArray().put("domain:bank.example.com")).put("outboundTag", "direct"))).apply {
                if (balancer) put("balancers", JSONArray().put(JSONObject().put("tag", "balancer").put("selector", JSONArray().put("proxy")).put("fallbackTag", "direct")
                    .put("strategy", JSONObject().put("type", "leastLoad").put("settings", JSONObject().put("expected", 2).put("maxRTT", "1s").put("evil", "x")))))
            })
        if (balancer) doc.put("burstObservatory", JSONObject().put("subjectSelector", JSONArray().put("proxy")).put("pingConfig", JSONObject().put("destination", "https://www.gstatic.com/generate_204").put("interval", "30m").put("sampling", 3).put("timeout", "5s").put("connectivity", "http://evil.example.com")))
        return doc
    }
    fun balancerDocument(): String = config(" ⚡ Fastest ", tcpReality("proxy", "a.example.com"), grpcReality("proxy-2", "g.example.com"), ws("proxy-3"), xhttp("proxy-4"), hysteria("proxy-5"), balancer = true).toString()
    /** One document per protocol: VMess (ws+tls), Trojan (grpc+tls), Shadowsocks, SOCKS with auth, HTTP with TLS, WireGuard. */
    fun allProtocols(): String {
        fun doc(remarks: String, o: JSONObject) = JSONObject().put("remarks", remarks).put("outbounds", JSONArray().put(o))
        fun server(extra: JSONObject.() -> Unit) = JSONObject().put("address", "s.example.com").put("port", 443).apply(extra)
        val vmess = JSONObject().put("tag", "proxy").put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(server {
            put("users", JSONArray().put(JSONObject().put("id", UUID1).put("security", "auto").put("alterId", 0))) })))
            .put("streamSettings", tls("ws") { put("wsSettings", JSONObject().put("path", "/vm")) })
        val trojan = JSONObject().put("tag", "proxy").put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(server { put("password", "pw") })))
            .put("streamSettings", tls("grpc") { put("grpcSettings", JSONObject().put("serviceName", "tr")) })
        val ss = JSONObject().put("tag", "proxy").put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(server {
            put("method", "chacha20-ietf-poly1305").put("password", "pw") })))
        val socks = JSONObject().put("tag", "proxy").put("protocol", "socks").put("settings", JSONObject().put("servers", JSONArray().put(server {
            put("users", JSONArray().put(JSONObject().put("user", "u").put("pass", "p"))) })))
        val http = JSONObject().put("tag", "proxy").put("protocol", "http").put("settings", JSONObject().put("servers", JSONArray().put(server {
            put("users", JSONArray().put(JSONObject().put("user", "u").put("pass", "p"))) }))).put("streamSettings", tls("tcp"))
        val wg = JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", JSONObject()
            .put("secretKey", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })).put("address", JSONArray(listOf("10.8.0.2/32")))
            .put("peers", JSONArray().put(JSONObject().put("publicKey", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })).put("endpoint", "wg.example.com:51820")))
            .put("mtu", 1420).put("reserved", JSONArray(listOf(1, 2, 3))))
        return JSONArray().put(doc("VMess", vmess)).put(doc("Trojan", trojan)).put(doc("SS", ss)).put(doc("SOCKS", socks)).put(doc("HTTPS", http)).put(doc("WG", wg)).toString()
    }
}
