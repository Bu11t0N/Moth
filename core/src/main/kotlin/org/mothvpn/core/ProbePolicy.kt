package org.mothvpn.core

import org.json.JSONObject

/** TCP reachability must never be presented as a successful VPN tunnel check. */
object ProbePolicy {
    /**
     * Default: a warm-up HTTPS request through the server (it must succeed), then the time of a second request on the same
     * kept-alive TLS connection. This is what v2rayNG (two requests, the shorter time) and mihomo's unified delay show: the round trip
     * through the server without the one-time TCP/proxy/TLS handshakes.
     */
    const val LATENCY = "latency"
    /** The first request: local proxy, server handshake, remote TCP + TLS and the first response (Moth up to 0.7.0 beta 5). */
    const val FULL = "full"
    const val TCP = "tcp"
    val modes = setOf(LATENCY, FULL, TCP)
    /** Saved values of older versions: "auto" (several URLs) and "https" (custom URL) both become [LATENCY]; a saved "only this URL" choice is ignored (there is one URL). */
    fun migrateMode(saved: String) = when (saved) { in modes -> saved; else -> LATENCY }

    /** The HTTPS check address: only the one set in settings (no built-in fallbacks), for server checks and the health check. */
    fun urls(settings: ClientSettings): List<String> = listOf(settings.probeUrl)

    fun tcpEndpoint(profile: Profile): Pair<String, Int>? {
        if (JSONObject(profile.outbound).optBoolean("group")) return null // balancer group: use the HTTPS probe
        if (profile.protocol in setOf("hysteria", "hysteria2", "tuic", "wireguard")) return null
        val json = JSONObject(profile.outbound)
        if (profile.engine == "xray") {
            val settings = json.getJSONObject("settings")
            val server = (settings.optJSONArray("vnext") ?: settings.getJSONArray("servers")).getJSONObject(0)
            return server.getString("address") to server.getInt("port")
        }
        return json.getString("server") to json.getInt("server_port")
    }
}
