package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anti-DPI options and multiplexing. sing-box 1.11 has no TLS fragmentation (added in 1.12), so fragments and noise are made by
 * an Xray "freedom" outbound: Xray profiles dial through it with sockopt.dialerProxy, sing-box profiles reach it through a local
 * VLESS hop (TCP only, random UUID, UDP carried inside the stream with XUDP), so no UDP port is opened on the device.
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
        if (dpi != null && !bridge) all.filter { !it.has("detour") }.forEach { it.put("detour", DpiTricks.TAG) }
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        all.forEach { o ->
            if (o.optString("type") == "wireguard") (root.optJSONArray("endpoints") ?: JSONArray().also { root.put("endpoints", it) }).put(o)
            else outbounds.put(o)
        }
        if (dpi != null && !bridge) outbounds.put(dpi.outbound(DpiTricks.TAG))
    }
}
