package org.routevpn.core

import org.json.JSONObject

/** TCP reachability must never be presented as a successful VPN tunnel check. */
object ProbePolicy {
    fun urls(settings: ClientSettings): List<String> = if (settings.probeMode == "auto")
        listOf(settings.probeUrl, "https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204").distinct()
    else listOf(settings.probeUrl)

    fun tcpEndpoint(profile: Profile): Pair<String, Int>? {
        if (profile.protocol in setOf("hysteria", "hysteria2", "tuic")) return null
        val json = JSONObject(profile.outbound)
        if (profile.engine == "xray") {
            val settings = json.getJSONObject("settings")
            val server = (settings.optJSONArray("vnext") ?: settings.getJSONArray("servers")).getJSONObject(0)
            return server.getString("address") to server.getInt("port")
        }
        return json.getString("server") to json.getInt("server_port")
    }
}
