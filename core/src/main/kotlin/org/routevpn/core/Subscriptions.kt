package org.routevpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class Profile(val id: String, val name: String, val protocol: String, val outbound: String, val engine: String = "sing-box")
data class ImportResult(val profiles: List<Profile>, val rejected: Int)

object Subscriptions {
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    fun unbase64(s: String): String {
        val compact = s.filterNot(Char::isWhitespace)
        val bytes = runCatching { Base64.getDecoder().decode(compact) }
            .getOrElse { Base64.getUrlDecoder().decode(compact) }
        return String(bytes, StandardCharsets.UTF_8)
    }
    fun parse(body: String): ImportResult {
        require(body.length <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
        val text = body.trim().removePrefix("\uFEFF")
        if (text.startsWith("proxies:") || text.contains("\nproxies:") || text.startsWith("{")) return ClashSubscriptions.parse(text)
        val decoded = if (text.contains("://")) text else runCatching { unbase64(text) }.getOrDefault(text)
        if (decoded.startsWith("proxies:") || decoded.contains("\nproxies:")) return ClashSubscriptions.parse(decoded)
        val lines = decoded.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(lines.size <= 2000) { "Слишком много серверов" }
        var rejected = 0
        val profiles = lines.mapNotNull { line -> runCatching { parseLink(line) }.getOrElse { rejected++; null } }.distinctBy { it.id }
        require(profiles.isNotEmpty()) { "Нет поддерживаемых серверов. Нужен список ссылок VLESS, VMess, Trojan или Shadowsocks." }
        return ImportResult(profiles, rejected)
    }
    fun parseLink(link: String): Profile {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme in setOf("hy2", "hysteria2", "hysteria", "tuic")) return ExtraProtocols.parse(link)
        if (scheme in setOf("vless", "trojan") && URI(link).rawQuery.orEmpty().split('&').any { it in setOf("type=xhttp", "type=splithttp") }) return ExtraProtocols.xhttp(link)
        val o: JSONObject
        val name: String
        if (scheme == "vmess") {
            val v = JSONObject(unbase64(link.substringAfter("://")))
            require(v.optInt("aid", 0) == 0) { "VMess alterId не поддерживается" }
            if (v.optString("net") in setOf("xhttp", "splithttp")) return ExtraProtocols.xhttpVmess(v, link)
            o = JSONObject().put("type", "vmess").put("server", v.getString("add")).put("server_port", v.getString("port").toInt())
                .put("uuid", UUID.fromString(v.getString("id")).toString()).put("security", v.optString("scy", "auto"))
            name = v.optString("ps").ifBlank { v.getString("add") }
            val net = v.optString("net", "tcp")
            transport(o, net, v.optString("host"), v.optString("path"), v.optString("path"))
            if (v.optString("tls") == "tls") o.put("tls", JSONObject().put("enabled", true).put("server_name", v.optString("sni").ifBlank { v.optString("host").ifBlank { v.getString("add") } }).apply {
                v.optString("alpn").takeIf { it.isNotBlank() }?.let { put("alpn", JSONArray(it.split(','))) }
                v.optString("fp").takeIf { it.isNotBlank() }?.let { put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            })
        } else {
            require(scheme in setOf("vless", "trojan", "ss")) { "Протокол не поддерживается" }
            var normalized = link
            if (scheme == "ss" && !link.substringBefore('#').contains('@')) {
                val payload = link.substringAfter("://").substringBefore('#')
                normalized = "ss://${unbase64(payload)}" + if (link.contains('#')) "#${link.substringAfter('#')}" else ""
            }
            val u = URI(normalized)
            require(u.host != null && u.port in 1..65535) { "Неверный адрес сервера" }
            val q = u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            require(q["allowInsecure"] !in setOf("1", "true") && q["insecure"] !in setOf("1", "true")) { "Небезопасная проверка TLS не поддерживается" }
            require(q["plugin"].isNullOrBlank()) { "Shadowsocks plugin не поддерживается" }
            o = JSONObject().put("type", if (scheme == "ss") "shadowsocks" else scheme).put("server", u.host.removeSurrounding("[", "]")).put("server_port", u.port)
            val user = decode(u.rawUserInfo ?: error("Нет ключа сервера"))
            when (scheme) {
                "vless" -> { o.put("uuid", UUID.fromString(user).toString()); q["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }; require(q["encryption"].isNullOrEmpty() || q["encryption"] == "none") }
                "trojan" -> o.put("password", user)
                "ss" -> { val creds = if (user.contains(':')) user else unbase64(user); require(creds.contains(':')); o.put("method", creds.substringBefore(':')).put("password", creds.substringAfter(':')) }
            }
            name = u.rawFragment?.let(::decode)?.ifBlank { u.host } ?: u.host
            if (scheme != "ss") {
                transport(o, q["type"] ?: "tcp", q["host"].orEmpty(), q["path"].orEmpty(), q["serviceName"].orEmpty())
                val security = q["security"] ?: if (scheme == "trojan") "tls" else "none"
                require(security in setOf("none", "tls", "reality"))
                if (security != "none") {
                    val tls = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: q["serverName"] ?: u.host.removeSurrounding("[", "]"))
                    q["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
                    q["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
                    if (security == "reality") {
                        require(!q["pbk"].isNullOrBlank()) { "Нет публичного ключа REALITY" }
                        tls.put("reality", JSONObject().put("enabled", true).put("public_key", q["pbk"]).put("short_id", q["sid"].orEmpty()))
                        if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                    }
                    o.put("tls", tls)
                }
            }
        }
        require(o.getInt("server_port") in 1..65535)
        require(o.getString("server").isNotBlank())
        o.put("tag", "proxy")
        val id = MessageDigest.getInstance("SHA-256").digest(link.substringBefore('#').toByteArray()).joinToString("") { "%02x".format(it) }
        return Profile(id, name.take(160), scheme, o.toString())
    }
    private fun transport(o: JSONObject, type: String, host: String, path: String, service: String) {
        when (type) {
            "tcp", "raw", "" -> Unit
            "ws" -> o.put("transport", JSONObject().put("type", "ws").put("path", path.ifBlank { "/" }).apply { if (host.isNotBlank()) put("headers", JSONObject().put("Host", host)) })
            "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", service))
            "httpupgrade" -> o.put("transport", JSONObject().put("type", "httpupgrade").put("host", host).put("path", path.ifBlank { "/" }))
            else -> error("Транспорт $type не поддерживается")
        }
    }
}

object TunnelConfig {
    fun create(profile: Profile, settings: ClientSettings = ClientSettings()): String = JSONObject()
        .put("log", JSONObject().put("disabled", true))
        .put("dns", JSONObject().put("servers", JSONArray()
            .put(JSONObject().put("tag", "remote").put("address", "https://1.1.1.1/dns-query").put("detour", "proxy"))
            .put(JSONObject().put("tag", "bootstrap").put("address", "https://1.1.1.1/dns-query").put("detour", "direct")))
            .put("rules", JSONArray().put(JSONObject().put("outbound", "any").put("server", "bootstrap")))
            .put("final", "remote").put("strategy", if (settings.ipv6) "prefer_ipv4" else "ipv4_only").apply {
                getJSONArray("servers").getJSONObject(0).put("address", settings.dns).put("address_resolver", "bootstrap")
            })
        .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun-in").put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
            .put("mtu", settings.mtu).put("auto_route", true).put("strict_route", true).put("stack", "gvisor")))
        .put("outbounds", JSONArray().put(JSONObject(profile.outbound)).put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy").put("rules", JSONArray().put(JSONObject().put("port", 53).put("action", "hijack-dns")).apply {
            if (!settings.ipv6) put(JSONObject().put("ip_version", 6).put("action", "reject"))
        }))
        .toString()
}
