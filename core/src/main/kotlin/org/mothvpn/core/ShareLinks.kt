package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Share links for servers imported without one (Clash/Xray JSON subscriptions, older saves):
 * rebuilt from the stored outbound. Returns null when the server cannot be expressed as a single link
 * (balancer groups, unknown transports). Never logged: links contain server keys.
 */
object ShareLinks {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    private fun host(h: String) = if (h.contains(':') && !h.startsWith('[')) "[$h]" else h
    private fun query(q: Map<String, String>) = q.filterValues { it.isNotEmpty() }.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    private fun build(scheme: String, user: String, server: String, port: Int, q: Map<String, String>, name: String) =
        "$scheme://$user@${host(server)}:$port" + query(q).let { if (it.isEmpty()) "" else "?$it" } + "#${enc(name)}"
    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter(String::isNotBlank) }.orEmpty()

    fun of(profile: Profile): String? {
        if (profile.link.isNotBlank()) return profile.link
        val o = runCatching { JSONObject(profile.outbound) }.getOrNull() ?: return null
        return runCatching { if (profile.engine == "xray") xray(o, profile.name) else singBox(o, profile.name) }.getOrNull()
    }

    /** sing-box outbound (links, Clash and the Hysteria 2 entries of Xray JSON). */
    fun singBox(o: JSONObject, name: String): String? {
        val type = o.optString("type"); val server = o.optString("server"); val port = o.optInt("server_port")
        if (server.isBlank() || port !in 1..65535) return null
        val q = linkedMapOf<String, String>()
        val tls = o.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") }
        fun tlsParams() {
            if (tls == null) return
            tls.optString("server_name").let { q["sni"] = it }
            strings(tls.optJSONArray("alpn")).takeIf { it.isNotEmpty() }?.let { q["alpn"] = it.joinToString(",") }
            tls.optJSONObject("utls")?.optString("fingerprint")?.let { q["fp"] = it }
        }
        fun transport(): Boolean {
            val t = o.optJSONObject("transport")
            when (t?.optString("type").orEmpty()) {
                "" -> q["type"] = "tcp"
                "ws" -> { q["type"] = "ws"; q["path"] = t!!.optString("path", "/"); q["host"] = t.optJSONObject("headers")?.optString("Host").orEmpty() }
                "grpc" -> { q["type"] = "grpc"; q["serviceName"] = t!!.optString("service_name") }
                "httpupgrade" -> { q["type"] = "httpupgrade"; q["path"] = t!!.optString("path", "/"); q["host"] = t.optString("host") }
                else -> return false
            }
            return true
        }
        return when (type) {
            "vless", "trojan" -> {
                if (!transport()) return null
                val reality = tls?.optJSONObject("reality")?.takeIf { it.optBoolean("enabled") }
                q["security"] = if (reality != null) "reality" else if (tls != null) "tls" else "none"
                tlsParams()
                reality?.let { q["pbk"] = it.optString("public_key"); q["sid"] = it.optString("short_id") }
                if (type == "vless") { q["encryption"] = "none"; o.optString("flow").takeIf { it.isNotBlank() }?.let { q["flow"] = it } }
                build(type, enc(if (type == "vless") o.getString("uuid") else o.getString("password")), server, port, q, name)
            }
            "shadowsocks" -> {
                val user = Base64.getUrlEncoder().withoutPadding().encodeToString("${o.getString("method")}:${o.getString("password")}".toByteArray())
                "ss://$user@${host(server)}:$port#${enc(name)}"
            }
            "hysteria2" -> {
                tlsParams(); q.remove("fp")
                o.optJSONObject("obfs")?.let { q["obfs"] = it.optString("type"); q["obfs-password"] = it.optString("password") }
                build("hy2", enc(o.getString("password")), server, port, q, name)
            }
            "tuic" -> {
                tlsParams(); q.remove("fp")
                q["congestion_control"] = o.optString("congestion_control", "cubic"); q["udp_relay_mode"] = o.optString("udp_relay_mode", "native")
                build("tuic", enc(o.getString("uuid")) + ":" + enc(o.getString("password")), server, port, q, name)
            }
            "vmess" -> {
                if (!transport()) return null
                val v = JSONObject().put("v", "2").put("ps", name).put("add", server).put("port", port.toString()).put("id", o.getString("uuid"))
                    .put("aid", "0").put("scy", o.optString("security", "auto")).put("net", q["type"] ?: "tcp")
                    .put("host", q["host"].orEmpty()).put("path", if (q["type"] == "grpc") q["serviceName"].orEmpty() else q["path"].orEmpty())
                    .put("tls", if (tls != null) "tls" else "")
                if (tls != null) { tlsParams(); v.put("sni", q["sni"].orEmpty()).put("alpn", q["alpn"].orEmpty()).put("fp", q["fp"].orEmpty()) }
                "vmess://" + Base64.getEncoder().encodeToString(v.toString().toByteArray())
            }
            else -> null
        }
    }

    /** Xray VLESS outbound produced by the Xray JSON importer (single server, not a balancer group). */
    fun xray(o: JSONObject, name: String): String? {
        if (o.optBoolean("group") || o.optString("protocol") != "vless") return null
        val node = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val user = node.getJSONArray("users").getJSONObject(0)
        val s = o.optJSONObject("streamSettings") ?: JSONObject()
        val network = s.optString("network", "tcp"); val security = s.optString("security", "none")
        val q = linkedMapOf("type" to network, "security" to security, "encryption" to "none")
        user.optString("flow").takeIf { it.isNotBlank() }?.let { q["flow"] = it }
        when (network) {
            "tcp" -> Unit
            "ws" -> s.optJSONObject("wsSettings")?.let { q["path"] = it.optString("path", "/"); q["host"] = it.optString("host").ifBlank { it.optJSONObject("headers")?.optString("Host").orEmpty() } }
            "grpc" -> s.optJSONObject("grpcSettings")?.let { q["serviceName"] = it.optString("serviceName"); q["authority"] = it.optString("authority"); if (it.optBoolean("multiMode")) q["mode"] = "multi" }
            "xhttp" -> s.optJSONObject("xhttpSettings")?.let { q["path"] = it.optString("path", "/"); q["host"] = it.optString("host"); q["mode"] = it.optString("mode", "auto"); it.optJSONObject("extra")?.let { e -> q["extra"] = e.toString() } }
            "httpupgrade" -> s.optJSONObject("httpupgradeSettings")?.let { q["path"] = it.optString("path", "/"); q["host"] = it.optString("host") }
            else -> return null
        }
        when (security) {
            "tls" -> s.optJSONObject("tlsSettings")?.let { t -> q["sni"] = t.optString("serverName"); q["fp"] = t.optString("fingerprint"); strings(t.optJSONArray("alpn")).takeIf { it.isNotEmpty() }?.let { q["alpn"] = it.joinToString(",") } }
            "reality" -> s.optJSONObject("realitySettings")?.let { r -> q["sni"] = r.optString("serverName"); q["fp"] = r.optString("fingerprint"); q["pbk"] = r.optString("publicKey"); q["sid"] = r.optString("shortId"); q["spx"] = r.optString("spiderX") }
        }
        return build("vless", enc(user.getString("id")), node.getString("address"), node.getInt("port"), q, name)
    }
}
