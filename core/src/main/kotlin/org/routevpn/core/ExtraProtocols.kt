package org.routevpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

object ExtraProtocols {
    private fun decode(v: String) = URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
    private fun query(u: URI) = u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
    private fun id(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun safe(q: Map<String, String>) { require(q["insecure"] !in setOf("1", "true") && q["allowInsecure"] !in setOf("1", "true")) { "Отключение проверки TLS не поддерживается" } }
    fun parse(link: String): Profile {
        val u = URI(link); val q = query(u); safe(q)
        val type = when (u.scheme.lowercase()) { "hy2", "hysteria2" -> "hysteria2"; "hysteria" -> "hysteria"; else -> "tuic" }
        require(u.host != null) { "Нет адреса сервера" }
        val port = if (u.port < 0) 443 else u.port
        require(port in 1..65535)
        require(q["mport"].isNullOrEmpty()) { "Hysteria port hopping пока не поддерживается" }
        val o = JSONObject().put("type", type).put("tag", "proxy").put("server", u.host.removeSurrounding("[", "]")).put("server_port", port)
        val tls = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: q["peer"] ?: u.host.removeSurrounding("[", "]"))
        q["alpn"]?.let { tls.put("alpn", JSONArray(it.split(','))) }
        o.put("tls", tls)
        val credentials = decode(u.rawUserInfo.orEmpty())
        when (type) {
            "hysteria2" -> {
                require(credentials.isNotBlank()) { "Нет пароля Hysteria2" }; o.put("password", credentials)
                q["obfs"]?.takeIf { it.isNotBlank() }?.let {
                    require(it == "salamander" && !q["obfs-password"].isNullOrBlank()) { "Неверные параметры обфускации Hysteria2" }
                    o.put("obfs", JSONObject().put("type", it).put("password", q["obfs-password"]))
                }
            }
            "hysteria" -> {
                require(q["protocol"].isNullOrBlank() || q["protocol"] == "udp") { "Hysteria поддерживает UDP transport" }
                o.put("auth_str", q["auth"] ?: credentials)
                val up = (q["upmbps"] ?: q["up"])?.toIntOrNull() ?: error("Для Hysteria нужны upmbps и downmbps")
                val down = (q["downmbps"] ?: q["down"])?.toIntOrNull() ?: error("Для Hysteria нужны upmbps и downmbps")
                require(up > 0 && down > 0); o.put("up_mbps", up).put("down_mbps", down)
                q["obfs"]?.takeIf { it.isNotBlank() }?.let { o.put("obfs", it) }
            }
            "tuic" -> {
                require(credentials.contains(':')) { "TUIC v5: нужны UUID и пароль" }
                o.put("uuid", UUID.fromString(credentials.substringBefore(':')).toString()).put("password", credentials.substringAfter(':'))
                o.put("congestion_control", q["congestion_control"] ?: "cubic").put("udp_relay_mode", q["udp_relay_mode"] ?: "native")
                require(o.getString("congestion_control") in setOf("cubic", "new_reno", "bbr"))
                require(o.getString("udp_relay_mode") in setOf("native", "quic"))
                q["disable_sni"]?.let { tls.put("disable_sni", it in setOf("1", "true")) }
            }
        }
        return Profile(id(link.substringBefore('#')), u.rawFragment?.let(::decode)?.ifBlank { u.host } ?: u.host, type, o.toString())
    }
    fun xhttp(link: String): Profile {
        val u = URI(link); val q = query(u); safe(q)
        require(u.host != null && u.port in 1..65535)
        val user = decode(u.rawUserInfo ?: error("Нет ключа сервера"))
        val protocol = u.scheme.lowercase()
        val server = JSONObject().put("address", u.host.removeSurrounding("[", "]")).put("port", u.port)
        val settings = if (protocol == "trojan") JSONObject().put("servers", JSONArray().put(server.put("password", user)))
        else {
            require(q["encryption"].isNullOrBlank() || q["encryption"] == "none") { "Неизвестное шифрование VLESS" }
            val account = JSONObject().put("id", UUID.fromString(user).toString()).put("encryption", "none")
            require(q["flow"].isNullOrBlank()) { "XHTTP не поддерживает Vision flow" }
            JSONObject().put("vnext", JSONArray().put(server.put("users", JSONArray().put(account))))
        }
        val security = q["security"] ?: if (protocol == "trojan") "tls" else "none"
        require(security in setOf("tls", "reality", "none"))
        val mode = q["mode"] ?: "auto"
        require(mode in setOf("auto", "packet-up", "stream-up", "stream-one")) { "Неизвестный режим XHTTP" }
        val xhttp = JSONObject().put("host", q["host"].orEmpty()).put("path", q["path"] ?: "/").put("mode", mode)
        q["extra"]?.takeIf { it.isNotBlank() }?.let { require(it.length <= 16384); xhttp.put("extra", JSONObject(it)) }
        val stream = JSONObject().put("network", "xhttp").put("security", security).put("xhttpSettings", xhttp)
        if (security != "none") {
            val tls = JSONObject().put("serverName", q["sni"] ?: q["serverName"] ?: u.host.removeSurrounding("[", "]"))
                .put("fingerprint", q["fp"] ?: "chrome")
            q["alpn"]?.let { tls.put("alpn", JSONArray(it.split(','))) }
            if (security == "reality") {
                require(!q["pbk"].isNullOrBlank()) { "Нет публичного ключа REALITY" }
                tls.put("publicKey", q["pbk"]).put("shortId", q["sid"].orEmpty()).put("spiderX", q["spx"] ?: "/")
            }
            stream.put(if (security == "reality") "realitySettings" else "tlsSettings", tls)
        }
        val o = JSONObject().put("tag", "proxy").put("protocol", protocol).put("settings", settings).put("streamSettings", stream)
        return Profile(id(link.substringBefore('#')), u.rawFragment?.let(::decode)?.ifBlank { u.host } ?: u.host, "$protocol / XHTTP", o.toString(), "xray")
    }
    fun xhttpVmess(v: JSONObject, original: String): Profile {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val host = v.getString("add").let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        val query = mapOf("type" to "xhttp", "security" to if (v.optString("tls") == "tls") "tls" else "none",
            "host" to v.optString("host"), "path" to v.optString("path", "/"), "mode" to v.optString("mode", "auto"),
            "sni" to v.optString("sni", v.getString("add")), "fp" to v.optString("fp", "chrome"))
        val p = xhttp("vless://${v.getString("id")}@$host:${v.getInt("port")}?" + query.entries.joinToString("&") { "${it.key}=${enc(it.value)}" })
        val o = JSONObject(p.outbound).put("protocol", "vmess")
        val user = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        user.remove("encryption"); user.put("security", v.optString("scy", "auto"))
        return p.copy(id = id(original.substringBefore('#')), protocol = "vmess / XHTTP", name = v.optString("ps").ifBlank { v.getString("add") }, outbound = o.toString())
    }
}
