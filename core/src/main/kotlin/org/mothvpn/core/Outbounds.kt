package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anti-DPI options and multiplexing. sing-box servers use sing-box's own TLS fragmentation (tls.fragment / tls.record_fragment).
 * Xray servers dial through an Xray "freedom" outbound with fragments and noise (sockopt.dialerProxy). UDP noise for sing-box
 * servers comes from a local Xray helper reached through a VLESS hop (TCP only, random UUID, UDP inside the stream with XUDP),
 * so no UDP port is opened on the device.
 */
object DpiTricks {
    /** id -> Xray freedom "fragment" settings; "off" adds nothing. */
    val fragmentPresets: Map<String, JSONObject?> = linkedMapOf(
        "off" to null,
        "tlshello" to JSONObject().put("packets", "tlshello").put("length", "100-200").put("interval", "10-20"),
        "tlshello_small" to JSONObject().put("packets", "tlshello").put("length", "10-30").put("interval", "5-10"),
        "first_packets" to JSONObject().put("packets", "1-3").put("length", "1-5").put("interval", "1-2"),
    )
    val muxModes = listOf("off", "smux", "yamux", "h2mux")
    const val TAG = "moth-dpi"
    fun active(s: ClientSettings) = s.fragment != "off" || s.noise
    /** sing-box servers need the Xray helper only for UDP noise; fragmentation is native. */
    fun singBoxHelper(s: ClientSettings) = s.noise
    /** Native sing-box TLS fragmentation on an outbound with TLS ("tlshello_small" also splits TLS records). */
    fun nativeFragment(o: JSONObject, s: ClientSettings) {
        if (s.fragment == "off") return
        val tls = o.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") } ?: return
        tls.put("fragment", true)
        if (s.fragment == "tlshello_small") tls.put("record_fragment", true)
    }
    /** Xray freedom outbound that fragments the TLS ClientHello and/or sends UDP noise first. */
    fun freedom(s: ClientSettings): JSONObject {
        val settings = JSONObject()
        fragmentPresets[s.fragment]?.let { settings.put("fragment", JSONObject(it.toString())) }
        if (s.noise) settings.put("noises", JSONArray().put(JSONObject().put("type", "rand").put("packet", "10-20").put("delay", "10-16")))
        return JSONObject().put("tag", TAG).put("protocol", "freedom").put("settings", settings)
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("domainStrategy", "ForceIPv4")))
    }
    /** Xray Mux.Cool for Xray-engine outbounds (any "on" choice); never with Vision flow or XHTTP, which has its own XMUX. */
    fun xrayMux(o: JSONObject, s: ClientSettings) {
        if (s.mux == "off" || o.has("mux") || o.optString("protocol") !in setOf("vless", "vmess", "trojan", "shadowsocks")) return
        val stream = o.optJSONObject("streamSettings")
        if (stream?.optString("network") in setOf("xhttp", "splithttp")) return
        val users = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)
        val flow = users?.optString("flow").orEmpty().ifEmpty { o.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)?.optString("flow").orEmpty() }
        if (flow.isNotEmpty()) return
        o.put("mux", JSONObject().put("enabled", true).put("concurrency", 8).put("xudpConcurrency", 16).put("xudpProxyUDP443", "reject"))
    }
}

/** Local VLESS hop to an Xray helper on 127.0.0.1 (authenticated by a random UUID, UDP inside the TCP stream). */
data class LocalHop(val port: Int, val uuid: String) {
    fun outbound(tag: String) = JSONObject().put("type", "vless").put("tag", tag).put("server", "127.0.0.1").put("server_port", port)
        .put("uuid", uuid).put("packet_encoding", "xudp")
}

object SingBoxOutbounds {
    /** Internal marker on the bridge outbound to the Xray engine: no multiplexing or DPI hop there. */
    const val BRIDGE = "moth_bridge"
    /** Extra outbounds the main one dials through (ShadowTLS); stored inside the profile outbound. */
    const val CHAIN = "moth_chain"
    private val muxTypes = setOf("vless", "vmess", "trojan", "shadowsocks")

    /** Adds the profile outbound (tag "proxy") with its chain to [root]: WireGuard goes to "endpoints", the rest to "outbounds". */
    fun install(root: JSONObject, outbound: String, settings: ClientSettings, dpi: LocalHop?) {
        val main = JSONObject(outbound)
        val bridge = main.optBoolean(BRIDGE); main.remove(BRIDGE)
        val chain = main.optJSONArray(CHAIN)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty(); main.remove(CHAIN)
        if (!bridge && settings.mux != "off" && main.optString("type") in muxTypes && !main.has("multiplex") && main.optString("flow").isEmpty())
            main.put("multiplex", JSONObject().put("enabled", true).put("protocol", settings.mux).put("max_connections", 4).put("min_streams", 4))
        val all = listOf(main) + chain
        if (!bridge) all.forEach { DpiTricks.nativeFragment(it, settings) }
        if (dpi != null && !bridge) all.filter { !it.has("detour") }.forEach { it.put("detour", DpiTricks.TAG) }
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        all.forEach { o ->
            if (o.optString("type") == "wireguard") (root.optJSONArray("endpoints") ?: JSONArray().also { root.put("endpoints", it) }).put(o)
            else outbounds.put(o)
        }
        if (dpi != null && !bridge) outbounds.put(dpi.outbound(DpiTricks.TAG))
    }
}

/** sing-box 1.12 DNS servers (typed format) built from the addresses users enter: DoH URL, tls://host, quic://, h3:// or a plain IP. */
object SingDns {
    private fun isIp(host: String) = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
    /** [detour]: outbound for the queries (null = direct). Host names are resolved with [resolver]. */
    fun server(tag: String, address: String, detour: String? = null, resolver: String? = null): org.json.JSONObject {
        val a = address.trim()
        val o = org.json.JSONObject().put("tag", tag)
        if (a == "local") return o.put("type", "local")
        val scheme = a.substringBefore("://", "").lowercase()
        val type = when (scheme) { "" , "udp" -> "udp"; "tcp" -> "tcp"; "tls" -> "tls"; "quic" -> "quic"; "https" -> "https"; "h3" -> "h3"
            else -> throw IllegalArgumentException("Неверный DNS-сервер") }
        val rest = a.substringAfter("://")
        val authority = rest.substringBefore('/')
        val host: String
        var port: Int? = null
        if (authority.startsWith("[")) { host = authority.substringAfter('[').substringBefore(']'); authority.substringAfter("]:", "").toIntOrNull()?.let { port = it } }
        else if (authority.count { it == ':' } == 1) { host = authority.substringBefore(':'); port = authority.substringAfter(':').toIntOrNull() }
        else host = authority
        require(host.isNotEmpty()) { "Неверный DNS-сервер" }
        o.put("type", type).put("server", host)
        port?.let { o.put("server_port", it) }
        if (type == "https" || type == "h3") rest.substringAfter('/', "").let { if (it.isNotEmpty() && it != "dns-query") o.put("path", "/$it") }
        detour?.let { o.put("detour", it) }
        if (!isIp(host) && resolver != null) o.put("domain_resolver", resolver)
        return o
    }
    /** Local system resolver, used only to find a bootstrap DNS server given by name. */
    fun system(tag: String = "system") = org.json.JSONObject().put("type", "local").put("tag", tag)
}
