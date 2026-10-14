package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * sing-box JSON profiles (a full config or {"outbounds": [...]}, "endpoints" for WireGuard). Each supported proxy outbound becomes one
 * server; only whitelisted fields are kept (no file paths, no insecure TLS, no foreign detours except a ShadowTLS hop).
 */
object SingBoxSubscriptions {
    private val proxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "wireguard", "ssh")
    private val skipped = setOf("direct", "block", "dns", "selector", "urltest", "shadowtls", "tor", "naive")
    private val common = setOf("server", "server_port", "uuid", "password", "method", "username", "flow", "security", "packet_encoding", "network",
        "version", "user", "private_key", "private_key_passphrase", "host_key", "host_key_algorithms", "client_version", "obfs", "up_mbps", "down_mbps",
        "auth_str", "auth", "congestion_control", "udp_relay_mode", "udp_over_stream", "zero_rtt_handshake", "heartbeat", "global_padding",
        "authenticated_length", "path", "headers", "recv_window_conn", "recv_window", "disable_mtu_discovery", "domain_strategy")
    private const val MAX = 2000

    private fun root(text: String): JSONObject? = runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
    fun looksLike(text: String): Boolean {
        if (!text.trimStart().startsWith("{")) return false
        val r = root(text) ?: return false
        val outs = r.optJSONArray("outbounds")
        val list = outs?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        if (list.any { it.has("protocol") }) return false
        return list.any { it.has("type") } || r.optJSONArray("endpoints")?.optJSONObject(0)?.has("type") == true
    }

    fun parse(text: String): ImportResult {
        require(text.length <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
        val r = root(text) ?: error("Нет конфигурации sing-box")
        val all = listOf("outbounds", "endpoints").flatMap { key -> r.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
        require(all.size <= MAX) { "Слишком много серверов" }
        val byTag = all.associateBy { it.optString("tag") }
        var rejected = 0
        val reasons = mutableListOf<String>()
        val profiles = mutableListOf<Profile>()
        all.forEachIndexed { i, o ->
            val type = o.optString("type").lowercase()
            if (type in skipped) return@forEachIndexed
            try { profiles += convert(o, byTag) }
            catch (e: Exception) { rejected++; if (reasons.size < 100) reasons += "outbound ${i + 1} (${type.take(12).filter { it.isLetterOrDigit() }}): ${rejectReason(e)}" }
        }
        val (unique, duplicates) = dedupe(profiles, "outbound")
        require(unique.isNotEmpty()) { "Нет поддерживаемых серверов в конфигурации sing-box" }
        return ImportResult(unique, rejected, reasons, duplicates, 0, profiles.size)
    }

    private fun tls(t: JSONObject?): JSONObject? {
        if (t == null || !t.optBoolean("enabled")) return null
        require(!t.optBoolean("insecure")) { "Небезопасная проверка TLS не поддерживается" }
        val out = JSONObject().put("enabled", true)
        for (k in listOf("server_name", "disable_sni", "min_version", "max_version")) if (t.has(k)) out.put(k, t.get(k))
        t.optJSONArray("alpn")?.let { out.put("alpn", it) }
        t.optJSONObject("utls")?.let { u -> out.put("utls", JSONObject().put("enabled", u.optBoolean("enabled")).put("fingerprint", u.optString("fingerprint", "chrome"))) }
        t.optJSONObject("reality")?.takeIf { it.optBoolean("enabled") }?.let { rl ->
            require(rl.optString("public_key").isNotBlank()) { "Нет публичного ключа REALITY" }
            out.put("reality", JSONObject().put("enabled", true).put("public_key", rl.optString("public_key")).put("short_id", rl.optString("short_id")))
        }
        t.optString("certificate").takeIf { it.isNotBlank() }?.let { out.put("certificate", it) }
        return out
    }
    private fun transport(t: JSONObject?): JSONObject? {
        if (t == null) return null
        val type = t.optString("type")
        require(type in setOf("ws", "grpc", "httpupgrade", "http", "quic")) { "Транспорт не поддерживается" }
        val out = JSONObject().put("type", type)
        for (k in listOf("path", "host", "service_name", "max_early_data", "early_data_header_name", "method", "idle_timeout", "ping_timeout")) if (t.has(k)) out.put(k, t.get(k))
        t.optJSONObject("headers")?.let { out.put("headers", it) }
        return out
    }
    private fun copy(o: JSONObject): JSONObject {
        val out = JSONObject().put("type", o.getString("type")).put("tag", "proxy")
        for (k in common) if (o.has(k)) out.put(k, o.get(k))
        tls(o.optJSONObject("tls"))?.let { out.put("tls", it) }
        transport(o.optJSONObject("transport"))?.let { out.put("transport", it) }
        o.optJSONObject("multiplex")?.takeIf { it.optBoolean("enabled") }?.let { out.put("multiplex", it) }
        return out
    }
    private fun convert(o: JSONObject, byTag: Map<String, JSONObject>): Profile {
        val type = o.optString("type").lowercase()
        if (type == "anytls") error("AnyTLS пока не поддерживается")
        require(type in proxyTypes) { "Протокол не поддерживается" }
        val name = o.optString("tag").ifBlank { o.optString("server") }.take(160)
        if (type == "wireguard") return wireguard(o, name)
        require(o.optString("server").isNotBlank() && o.optInt("server_port") in 1..65535) { "Неверный адрес сервера" }
        if (type == "vmess") require(o.optInt("alter_id", 0) == 0) { "VMess alterId не поддерживается" }
        if (type in setOf("hysteria2", "hysteria", "tuic")) require(o.optJSONObject("tls")?.optBoolean("enabled") == true) { "Нужен TLS" }
        val out = copy(o)
        val protocol = when (type) { "shadowsocks" -> "ss"; "http" -> if (out.has("tls")) "https" else "http"; else -> type }
        val detour = o.optString("detour")
        if (detour.isNotBlank()) {
            val hop = byTag[detour]?.takeIf { it.optString("type") == "shadowtls" } ?: error("Цепочка outbounds не поддерживается")
            require(!hop.has("detour")) { "Цепочка outbounds не поддерживается" }
            val h = JSONObject().put("type", "shadowtls").put("tag", "proxy-shadowtls").put("server", hop.optString("server")).put("server_port", hop.optInt("server_port"))
                .put("version", hop.optInt("version", 1))
            require(h.getString("server").isNotBlank() && h.getInt("server_port") in 1..65535) { "Неверный адрес сервера" }
            hop.optString("password").takeIf { it.isNotEmpty() }?.let { h.put("password", it) }
            h.put("tls", tls(hop.optJSONObject("tls")) ?: error("ShadowTLS: нужен TLS"))
            out.put("detour", "proxy-shadowtls").put(SingBoxOutbounds.CHAIN, JSONArray().put(h))
            return Profile(ProxyProtocols.sha("sing-box|$out"), name, "$protocol + shadowtls", out.toString())
        }
        return Profile(ProxyProtocols.sha("sing-box|$out"), name, protocol, out.toString())
    }
    /** WireGuard endpoint (1.11) or the legacy outbound (server, local_address, peer_public_key). */
    private fun wireguard(o: JSONObject, name: String): Profile {
        fun strings(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
        val peer = o.optJSONArray("peers")?.optJSONObject(0)
        val reserved = (peer?.optJSONArray("reserved") ?: o.optJSONArray("reserved"))?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        val p = if (peer != null) ProxyProtocols.wireguard(peer.optString("address", o.optString("server")), peer.optInt("port", o.optInt("server_port")),
            o.optString("private_key"), peer.optString("public_key"), peer.optString("pre_shared_key").ifBlank { null },
            strings(o.optJSONArray("address") ?: o.optJSONArray("local_address")), reserved, o.optInt("mtu").takeIf { it > 0 }, name, "")
        else ProxyProtocols.wireguard(o.optString("server"), o.optInt("server_port"), o.optString("private_key"), o.optString("peer_public_key"),
            o.optString("pre_shared_key").ifBlank { null }, strings(o.optJSONArray("local_address")), reserved, o.optInt("mtu").takeIf { it > 0 }, name, "")
        return p.copy(id = ProxyProtocols.sha("sing-box|" + p.outbound))
    }
}
