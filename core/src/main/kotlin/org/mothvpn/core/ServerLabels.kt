package org.mothvpn.core

import org.json.JSONObject

/**
 * Uniform server subtitle: protocol · transport · security · source format, e.g. "VLESS · TCP · Reality",
 * "VLESS · XHTTP · TLS · JSON", "Hysteria2", "Trojan · WS · TLS". Derived from the stored outbound, so servers saved by
 * older versions get it too. Never includes names, addresses or credentials.
 */
object ServerLabels {
    const val SOURCE_JSON = "json"
    const val SOURCE_CLASH = "clash"
    const val SOURCE_SINGBOX = "sing-box"

    private val protocolNames = mapOf(
        "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "shadowsocks" to "Shadowsocks", "ss" to "Shadowsocks",
        "hysteria2" to "Hysteria2", "hy2" to "Hysteria2", "hysteria" to "Hysteria", "tuic" to "TUIC", "anytls" to "AnyTLS",
        "wireguard" to "WireGuard", "ssh" to "SSH", "socks" to "SOCKS5", "http" to "HTTP", "https" to "HTTPS", "xray" to "Xray")
    private val transportNames = mapOf("tcp" to "TCP", "raw" to "TCP", "ws" to "WS", "grpc" to "gRPC", "xhttp" to "XHTTP",
        "splithttp" to "XHTTP", "httpupgrade" to "HTTPUpgrade", "http" to "HTTP/2", "h2" to "HTTP/2", "quic" to "QUIC", "kcp" to "mKCP")
    /** Protocols whose subtitle names a transport and security layer. */
    private val layered = setOf("vless", "vmess", "trojan")

    private fun protocolName(p: String) = protocolNames[p.lowercase()] ?: p.uppercase()
    private fun sourceName(s: String) = when (s) { SOURCE_JSON -> "JSON"; SOURCE_CLASH -> "Clash"; SOURCE_SINGBOX -> "sing-box"; else -> null }

    /** Source format for [p]; servers saved before it was recorded fall back to what the stored data implies. */
    fun source(p: Profile): String = p.source.ifEmpty {
        // Xray JSON servers carried "<protocol> · <network> · <security>" and no share link.
        if (p.engine == "xray" && p.link.isEmpty() && p.protocol.contains(" · ")) SOURCE_JSON else ""
    }

    /** [balancer]: localized word for balancer groups. */
    fun label(p: Profile, balancer: String = "Balancer"): String {
        val parts = runCatching { parts(JSONObject(p.outbound), balancer) }.getOrNull() ?: listOf(fallback(p.protocol))
        return (parts + listOfNotNull(sourceName(source(p)))).joinToString(" · ")
    }

    private fun fallback(protocol: String) = protocolName(protocol.lowercase().split(' ', '/', '+', '·').first { it.isNotBlank() })

    private fun parts(o: JSONObject, balancer: String): List<String> = when {
        o.optBoolean("group") -> group(o, balancer)
        o.has("protocol") -> xray(o)
        else -> singBox(o)
    }

    private fun group(o: JSONObject, balancer: String): List<String> {
        val members = o.optJSONArray("outbounds")
        val kinds = (0 until (members?.length() ?: 0)).mapNotNull { members?.optJSONObject(it)?.optString("protocol")?.lowercase() }.distinct()
        return listOfNotNull(kinds.singleOrNull()?.let(::protocolName), balancer)
    }

    private fun xray(o: JSONObject): List<String> {
        var protocol = o.optString("protocol").lowercase()
        val s = o.optJSONObject("streamSettings")
        val security = s?.optString("security")?.lowercase().orEmpty()
        if (protocol == "http" && security == "tls") protocol = "https"
        if (protocol !in layered) return listOf(protocolName(protocol))
        val network = s?.optString("network")?.lowercase()?.ifBlank { null } ?: "tcp"
        return listOfNotNull(protocolName(protocol), transportNames[network] ?: network.uppercase(), securityName(security))
    }

    private fun singBox(o: JSONObject): List<String> {
        val type = o.optString("type").lowercase()
        val tls = o.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") }
        val protocol = if (type == "http" && tls != null) "https" else type
        val chain = o.optJSONArray(SingBoxOutbounds.CHAIN)?.optJSONObject(0)?.optString("type")
        if (protocol !in layered) return listOfNotNull(protocolName(protocol), if (chain == "shadowtls") "ShadowTLS" else null)
        val transport = o.optJSONObject("transport")?.optString("type")?.lowercase()?.ifBlank { null } ?: "tcp"
        val security = when { tls == null -> ""; tls.optJSONObject("reality")?.optBoolean("enabled") == true -> "reality"; else -> "tls" }
        return listOfNotNull(protocolName(protocol), transportNames[transport] ?: transport.uppercase(), securityName(security))
    }

    private fun securityName(s: String) = when (s) { "tls" -> "TLS"; "reality" -> "Reality"; else -> null }
}
