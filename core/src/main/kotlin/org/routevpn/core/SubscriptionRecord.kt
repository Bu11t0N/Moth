package org.routevpn.core

import org.json.JSONArray
import org.json.JSONObject

data class SubscriptionRecord(val url: String, val profiles: List<Profile>, val selected: String,
    val wifiSelected: String = selected, val mobileSelected: String = selected,
    val providerName: String = "", val expires: Long = 0, val usedBytes: Long = 0, val totalBytes: Long = 0,
    val updatedAt: Long = 0) {
    fun selection(network: String) = if (network == "mobile") mobileSelected else wifiSelected
    fun json(): JSONObject = JSONObject().put("url", url).put("selected", selected)
        .put("wifiSelected", wifiSelected).put("mobileSelected", mobileSelected)
        .put("providerName", providerName).put("expires", expires).put("usedBytes", usedBytes)
        .put("totalBytes", totalBytes).put("updatedAt", updatedAt)
        .put("profiles", JSONArray().apply { profiles.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name)
            .put("protocol", p.protocol).put("outbound", p.outbound).put("engine", p.engine)) } })
    companion object {
        fun from(json: JSONObject): SubscriptionRecord {
            val list = json.getJSONArray("profiles")
            val profiles = (0 until list.length()).map { i -> list.getJSONObject(i).let {
                Profile(it.getString("id"), it.getString("name"), it.getString("protocol"), it.getString("outbound"), it.optString("engine", "sing-box"))
            } }
            val selected = json.getString("selected")
            fun validSelection(key: String) = json.optString(key, selected).takeIf { id -> profiles.any { it.id == id } }
                ?: selected.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id.orEmpty()
            return SubscriptionRecord(json.getString("url"), profiles, selected, validSelection("wifiSelected"), validSelection("mobileSelected"),
                json.optString("providerName", ""), json.optLong("expires"), json.optLong("usedBytes"), json.optLong("totalBytes"), json.optLong("updatedAt"))
        }
    }
}
