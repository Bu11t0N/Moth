package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** [link]: the original share link (empty for Clash / Xray JSON servers); used only to export the server as a QR code. */
data class Profile(val id: String, val name: String, val protocol: String, val outbound: String, val engine: String = "sing-box", val link: String = "",
    /** Config format the server came from ([ServerLabels] SOURCE_*); empty for share links. */ val source: String = "",
    /** Outbound changed by hand in the JSON editor; a subscription update replaces it. */ val edited: Boolean = false)
/**
 * [duplicates]: one safe note per dropped duplicate ("link 5 (vless): same id as kept 3f2a9c01"); [comments]: skipped '#' / empty lines;
 * [parsed]: entries that converted successfully before duplicates were removed.
 */
data class ImportResult(val profiles: List<Profile>, val rejected: Int, val reasons: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(), val comments: Int = 0, val parsed: Int = profiles.size + duplicates.size) {
    /** "parsed N, duplicates D, rejected M" for the developer log. */
    fun summary() = "parsed $parsed, duplicates ${duplicates.size}, rejected $rejected"
}

/** Keeps the first profile per id; each dropped one is described by position, protocol and the kept id prefix only. */
internal fun dedupe(all: List<Profile>, label: String): Pair<List<Profile>, List<String>> {
    val kept = LinkedHashMap<String, Int>()
    val notes = mutableListOf<String>()
    val unique = mutableListOf<Profile>()
    all.forEachIndexed { index, p ->
        val first = kept[p.id]
        if (first == null) { kept[p.id] = index + 1; unique += p }
        else if (notes.size < 100) notes += "$label ${index + 1} (${p.protocol.take(24)}): duplicate of $label $first, id ${p.id.take(8)}"
    }
    return unique to notes
}

/** Short, link-free reason for a rejected server, safe to write to the log. */
internal fun rejectReason(e: Throwable): String {
    val message = e.message
    val ours = (e is IllegalArgumentException || e is IllegalStateException) && e !is NumberFormatException &&
        !message.isNullOrBlank() && !message.contains("://") && !message.startsWith("Invalid UUID") && message.length <= 160
    return if (ours) message!! else e.javaClass.simpleName
}

object Subscriptions {
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    fun unbase64(s: String): String {
        val compact = s.filterNot(Char::isWhitespace)
        val bytes = runCatching { Base64.getDecoder().decode(compact) }
            .getOrElse { Base64.getUrlDecoder().decode(compact) }
        return String(bytes, StandardCharsets.UTF_8)
    }
    /** [fetch]: downloads a Clash proxy-provider (HTTPS only); null skips providers. */
    private fun ImportResult.tagged(source: String) = copy(profiles = profiles.map { if (it.source.isEmpty()) it.copy(source = source) else it })
    fun parse(body: String, fetch: ((String) -> String)? = null): ImportResult {
        require(body.length <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
        val text = body.trim().removePrefix("\uFEFF")
        if (SingBoxSubscriptions.looksLike(text)) return SingBoxSubscriptions.parse(text).tagged(ServerLabels.SOURCE_SINGBOX)
        if (XrayJsonSubscriptions.looksLike(text)) return XrayJsonSubscriptions.parse(text).tagged(ServerLabels.SOURCE_JSON)
        if (ClashSubscriptions.looksLike(text) || text.startsWith("{")) return ClashSubscriptions.parse(text, fetch)
        val decoded = if (text.contains("://")) text else runCatching { unbase64(text) }.getOrDefault(text)
        if (ClashSubscriptions.looksLike(decoded)) return ClashSubscriptions.parse(decoded, fetch)
        val allLines = decoded.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        val lines = allLines.filter { !it.startsWith("#") }
        val comments = allLines.size - lines.size
        require(lines.size <= 2000) { "Слишком много серверов" }
        var rejected = 0
        val reasons = mutableListOf<String>()
        val converted = lines.mapIndexedNotNull { index, line ->
            runCatching { parseLink(line) }.getOrElse { e ->
                rejected++
                if (reasons.size < 100) reasons += "link ${index + 1} (${line.substringBefore("://", "?").take(12).lowercase()}): ${rejectReason(e)}"
                null
            }
        }
        val (profiles, duplicates) = dedupe(converted, "link")
        require(profiles.isNotEmpty()) { "Нет поддерживаемых серверов. Нужны ссылки VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, SOCKS, HTTP, WireGuard или SSH, либо конфигурация Clash, Xray или sing-box." }
        return ImportResult(profiles, rejected, reasons, duplicates, comments, converted.size)
    }
    fun parseLink(link: String): Profile = parseLinkOnly(link).copy(link = link.trim())
    private fun parseLinkOnly(link: String): Profile {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme in setOf("hy2", "hysteria2", "hysteria", "tuic")) return ExtraProtocols.parse(link)
        if (scheme in setOf("http", "https")) require(ImportInput.proxyLink(link)) { "Ссылка не похожа на прокси-сервер" }
        if (scheme in ProxyProtocols.schemes) return ProxyProtocols.parse(link)
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
    /**
     * [ruleSets]: rule-set tag -> local .srs file path, used only when [ClientSettings.directRu] is on (missing files are skipped;
     * the built-in .ru/.su/.рф domain rule still applies).
     */
    /**
     * [dpi]: local Xray helper for fragments/noise (sing-box profiles only). [appGuard]: Moth's package name when connection owners
     * can be resolved (Android 10+): connections from apps that split tunneling keeps out of the VPN but that still reach the tunnel
     * interface directly (binding to tun0) are rejected, so they cannot use the server.
     */
    fun create(profile: Profile, settings: ClientSettings = ClientSettings(), ruleSets: Map<String, String> = emptyMap(), dpi: LocalHop? = null, appGuard: String? = null): String = JSONObject()
        .put("log", JSONObject().put("disabled", true))
        .put("dns", JSONObject().put("servers", JSONArray()
            .put(SingDns.server("remote", settings.dns, "proxy", "bootstrap"))
            .put(SingDns.server("bootstrap", settings.bootstrapDns, null, "system"))
            .put(SingDns.system()))
            .put("final", "remote").put("strategy", if (settings.ipv6) "prefer_ipv4" else "ipv4_only"))
        .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun-in").put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
            .put("mtu", settings.mtu).put("auto_route", true).put("strict_route", settings.dnsLeakProtection).put("stack", "gvisor")))
        .put("outbounds", JSONArray())
        .also { SingBoxOutbounds.install(it, profile.outbound, settings, dpi); it.getJSONArray("outbounds").put(JSONObject().put("type", "direct").put("tag", "direct")) }
        .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy")
            .put("default_domain_resolver", JSONObject().put("server", "bootstrap")).put("rules", JSONArray().apply {
            if (settings.directRu || CustomRouting.needsSniff(settings.routingRules)) put(JSONObject().put("action", "sniff"))
            put(JSONObject().put("port", 53).put("action", "hijack-dns"))
            appGuard?.let { self -> appGuardRules(settings, self).forEach { put(it) } }
            if (!settings.ipv6) put(JSONObject().put("ip_version", 6).put("action", "reject"))
            // User rules come before the built-in "Russian sites directly" rules.
            CustomRouting.singBox(settings.routingRules).forEach { put(it) }
            if (settings.directRu) {
                put(JSONObject().put("domain_suffix", JSONArray(DirectRoutes.suffixes)).put("outbound", "direct"))
                if (ruleSets.isNotEmpty()) put(JSONObject().put("rule_set", JSONArray(ruleSets.keys.sorted())).put("outbound", "direct"))
            }
        }).apply {
            if (settings.directRu && ruleSets.isNotEmpty()) put("rule_set", JSONArray().apply {
                ruleSets.toSortedMap().forEach { (tag, path) -> put(JSONObject().put("type", "local").put("tag", tag).put("format", "binary").put("path", path)) }
            })
        })
        .toString()
    /** Reject rules for apps outside the VPN (by package; DNS is hijacked before, so system lookups keep working). */
    fun appGuardRules(settings: ClientSettings, self: String): List<JSONObject> {
        val plan = SplitTunnel.plan(settings.splitMode, settings.splitApps, self)
        return listOfNotNull(
            plan.disallowed.takeIf { it.isNotEmpty() }?.let { JSONObject().put("package_name", JSONArray(it)).put("action", "reject") },
            plan.allowed.takeIf { it.isNotEmpty() }?.let { JSONObject().put("package_name", JSONArray(it)).put("invert", true).put("action", "reject") })
    }
}
