package org.mothvpn.core

import org.json.JSONObject

/**
 * Uniform server subtitle: protocol · transport · security · source format, e.g. "VLESS · TCP · Reality",
 * "VLESS · XHTTP · TLS · JSON", "Hysteria2", "Trojan · WS · TLS". Derived from the stored outbound, so servers saved by
 * older versions get it too. Never includes names, addresses or credentials.
 */
/** One piece of a server subtitle; the app colours it by [kind] (and [protocol] for the protocol name). */
data class LabelPart(val text: String, val kind: Kind, val protocol: ProtocolTone? = null) {
    enum class Kind { PROTOCOL, TRANSPORT, TLS, REALITY, BALANCER, SOURCE, UNENCRYPTED }
}

/** Colour family of a protocol name in the subtitle. */
enum class ProtocolTone { VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA, ANYTLS, WIREGUARD, OTHER }

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
    fun tone(protocol: String): ProtocolTone = when (protocol.lowercase()) {
        "vless" -> ProtocolTone.VLESS; "vmess" -> ProtocolTone.VMESS; "trojan" -> ProtocolTone.TROJAN
        "shadowsocks", "ss" -> ProtocolTone.SHADOWSOCKS; "hysteria2", "hy2", "hysteria", "tuic" -> ProtocolTone.HYSTERIA
        "anytls" -> ProtocolTone.ANYTLS; "wireguard" -> ProtocolTone.WIREGUARD; else -> ProtocolTone.OTHER
    }
    private fun protocolPart(p: String) = LabelPart(protocolName(p), LabelPart.Kind.PROTOCOL, tone(p))
    private fun sourceName(s: String) = when (s) { SOURCE_JSON -> "JSON"; SOURCE_CLASH -> "Clash"; SOURCE_SINGBOX -> "sing-box"; else -> null }

    /** Source format for [p]; servers saved before it was recorded fall back to what the stored data implies. */
    fun source(p: Profile): String = p.source.ifEmpty {
        // Xray JSON servers carried "<protocol> · <network> · <security>" and no share link.
        if (p.engine == "xray" && p.link.isEmpty() && p.protocol.contains(" · ")) SOURCE_JSON else ""
    }

    /** [balancer]: localized word for balancer groups. */
    fun label(p: Profile, balancer: String = "Balancer"): String = parts(p, balancer).joinToString(" · ") { it.text }

    /**
     * The subtitle as coloured parts (joined by " · " they give [label]). [unencrypted]: localized word appended for servers whose
     * traffic is not encrypted ([ProxyProtocols.unencrypted]); null leaves it out.
     */
    fun parts(p: Profile, balancer: String = "Balancer", unencrypted: String? = null): List<LabelPart> {
        val parts = runCatching { parts(JSONObject(p.outbound), balancer) }.getOrNull() ?: listOf(fallback(p.protocol))
        return parts + listOfNotNull(sourceName(source(p))?.let { LabelPart(it, LabelPart.Kind.SOURCE) },
            unencrypted?.takeIf { ProxyProtocols.unencrypted(p) }?.let { LabelPart(it, LabelPart.Kind.UNENCRYPTED) })
    }

    private fun fallback(protocol: String) = protocolPart(protocol.lowercase().split(' ', '/', '+', '·').first { it.isNotBlank() })

    private fun parts(o: JSONObject, balancer: String): List<LabelPart> = when {
        o.optBoolean("group") -> group(o, balancer)
        o.has("protocol") -> xray(o)
        else -> singBox(o)
    }

    private fun group(o: JSONObject, balancer: String): List<LabelPart> {
        val members = o.optJSONArray("outbounds")
        val kinds = (0 until (members?.length() ?: 0)).mapNotNull { members?.optJSONObject(it)?.optString("protocol")?.lowercase() }.distinct()
        return listOfNotNull(kinds.singleOrNull()?.let(::protocolPart), LabelPart(balancer, LabelPart.Kind.BALANCER))
    }

    private fun transportPart(network: String) = LabelPart(transportNames[network] ?: network.uppercase(), LabelPart.Kind.TRANSPORT)

    private fun xray(o: JSONObject): List<LabelPart> {
        var protocol = o.optString("protocol").lowercase()
        val s = o.optJSONObject("streamSettings")
        val security = s?.optString("security")?.lowercase().orEmpty()
        if (protocol == "http" && security == "tls") protocol = "https"
        if (protocol !in layered) return listOf(protocolPart(protocol))
        val network = s?.optString("network")?.lowercase()?.ifBlank { null } ?: "tcp"
        return listOfNotNull(protocolPart(protocol), transportPart(network), securityPart(security))
    }

    private fun singBox(o: JSONObject): List<LabelPart> {
        val type = o.optString("type").lowercase()
        val tls = o.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") }
        val protocol = if (type == "http" && tls != null) "https" else type
        val chain = o.optJSONArray(SingBoxOutbounds.CHAIN)?.optJSONObject(0)?.optString("type")
        if (protocol !in layered) return listOfNotNull(protocolPart(protocol), if (chain == "shadowtls") LabelPart("ShadowTLS", LabelPart.Kind.TLS) else null)
        val transport = o.optJSONObject("transport")?.optString("type")?.lowercase()?.ifBlank { null } ?: "tcp"
        val security = when { tls == null -> ""; tls.optJSONObject("reality")?.optBoolean("enabled") == true -> "reality"; else -> "tls" }
        return listOfNotNull(protocolPart(protocol), transportPart(transport), securityPart(security))
    }

    private fun securityPart(s: String) = when (s) {
        "tls" -> LabelPart("TLS", LabelPart.Kind.TLS); "reality" -> LabelPart("Reality", LabelPart.Kind.REALITY); else -> null
    }
}
