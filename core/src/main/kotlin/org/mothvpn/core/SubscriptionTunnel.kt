package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Per-session authenticated loopback proxy, forced through the selected VPN server before any bypass rules. */
data class SubscriptionTunnel(val port: Int, val password: String) {
    fun inbound(): JSONObject = JSONObject().put("type", "socks").put("tag", TAG).put("listen", "127.0.0.1").put("listen_port", port)
        .put("users", JSONArray().put(JSONObject().put("username", USER).put("password", password)))
    fun rule(): JSONObject = JSONObject().put("inbound", JSONArray().put(TAG)).put("outbound", "proxy")
    companion object { const val TAG = "subscription-in"; const val USER = "moth" }
}
