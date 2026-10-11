package org.routevpn.core

import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class ClientSettings(
    val theme: String = "system", val mtu: Int = 1400, val ipv6: Boolean = true,
    val dns: String = "https://1.1.1.1/dns-query",
    val probeUrl: String = "https://www.gstatic.com/generate_204", val timeoutSeconds: Int = 10,
    val healthEnabled: Boolean = true, val healthIntervalSeconds: Int = 60,
    val hwidEnabled: Boolean = true, val hwid: String = UUID.randomUUID().toString(),
    val deviceInfo: Boolean = false, val userAgent: String = "Moth",
    val sortByLatency: Boolean = false,
    val compactServers: Boolean = true, val hideUnavailable: Boolean = false,
    val probeMode: String = "auto", val bootstrapDns: String = "https://1.1.1.1/dns-query",
    val probeParallel: Int = 5, val probeTimeoutMs: Int = 5000
) {
    /** The default user agent follows the app version; "Moth", "Moth/0.2" etc. become "Moth/<version>". Custom values are kept. */
    fun withAppVersion(version: String): ClientSettings =
        if (Regex("Moth(/[0-9][0-9.]*)?").matches(userAgent)) copy(userAgent = "Moth/$version") else this
    fun validate(): ClientSettings {
        require(theme in setOf("system", "dark", "light")) { "Неверная тема" }
        require(probeMode in setOf("auto", "https", "tcp")) { "Неверный способ проверки" }
        require(mtu in 1280..9000) { "MTU: от 1280 до 9000" }
        require(probeParallel in 1..16) { "Параллельных проверок: от 1 до 16" }
        require(probeTimeoutMs in 500..30000) { "Таймаут пинга: от 500 до 30000 мс" }
        require(timeoutSeconds in 3..30) { "Тайм-аут: от 3 до 30 секунд" }
        require(healthIntervalSeconds in 30..600) { "Интервал проверки: от 30 до 600 секунд" }
        require(hwid.matches(Regex("[a-zA-Z0-9=-]{10,64}"))) { "HWID: 10–64 символа, буквы, цифры, = или -" }
        require(userAgent.isNotBlank() && userAgent.length <= 160 && userAgent.all { it.code in 32..126 }) { "User-Agent должен содержать печатные латинские символы" }
        listOf(dns, probeUrl, bootstrapDns).forEach { value ->
            val u = URI(value)
            require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.fragment == null) { "Нужен корректный HTTPS-адрес" }
        }
        return this
    }
    fun json(): JSONObject = JSONObject().put("theme", theme).put("mtu", mtu).put("ipv6", ipv6).put("dns", dns)
        .put("probeUrl", probeUrl).put("timeoutSeconds", timeoutSeconds).put("healthEnabled", healthEnabled)
        .put("healthIntervalSeconds", healthIntervalSeconds).put("hwidEnabled", hwidEnabled).put("hwid", hwid)
        .put("deviceInfo", deviceInfo).put("userAgent", userAgent).put("sortByLatency", sortByLatency)
        .put("compactServers", compactServers).put("hideUnavailable", hideUnavailable)
        .put("probeMode", probeMode).put("bootstrapDns", bootstrapDns)
        .put("probeParallel", probeParallel).put("probeTimeoutMs", probeTimeoutMs)
    companion object {
        fun from(j: JSONObject) = ClientSettings(j.optString("theme", "system"), j.optInt("mtu", 1400), j.optBoolean("ipv6", true),
            j.optString("dns", "https://1.1.1.1/dns-query"), j.optString("probeUrl", "https://www.gstatic.com/generate_204"),
            j.optInt("timeoutSeconds", 10), j.optBoolean("healthEnabled", true), j.optInt("healthIntervalSeconds", 60),
            j.optBoolean("hwidEnabled", true), j.getString("hwid"), j.optBoolean("deviceInfo", false),
            j.optString("userAgent", "Moth"), j.optBoolean("sortByLatency", false),
            j.optBoolean("compactServers", true), j.optBoolean("hideUnavailable", false),
            j.optString("probeMode", "auto"), j.optString("bootstrapDns", "https://1.1.1.1/dns-query"),
            j.optInt("probeParallel", 5).coerceIn(1, 16),
            (if (j.has("probeTimeoutMs")) j.getInt("probeTimeoutMs") else if (j.has("probeTimeoutSeconds")) j.getInt("probeTimeoutSeconds") * 1000
                else j.optInt("timeoutSeconds", 10) * 1000).coerceIn(500, 30000)).validate()
    }
}

object SubscriptionHeaders {
    fun create(settings: ClientSettings, original: URI, destination: URI, osVersion: String, model: String): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to settings.userAgent, "Accept" to "text/plain, application/yaml, application/json, application/octet-stream")
        val sameOrigin = original.scheme == destination.scheme && original.host.equals(destination.host, true) &&
            (if (original.port == -1) 443 else original.port) == (if (destination.port == -1) 443 else destination.port)
        if (settings.hwidEnabled && sameOrigin) {
            headers["x-hwid"] = settings.hwid
            if (settings.deviceInfo) {
                headers["x-device-os"] = "Android"
                headers["x-ver-os"] = osVersion.filter { it.code in 32..126 }.take(64)
                headers["x-device-model"] = model.filter { it.code in 32..126 }.take(120)
            }
        }
        return headers
    }
}

object LocalProxyConfig {
    fun singBox(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings()): String = JSONObject()
        .put("log", JSONObject().put("disabled", true))
        .put("dns", JSONObject().put("servers", org.json.JSONArray().put(JSONObject().put("tag", "bootstrap").put("address", settings.bootstrapDns).put("detour", "direct")))
            .put("final", "bootstrap").put("strategy", "ipv4_only"))
        .put("inbounds", org.json.JSONArray().put(JSONObject().put("type", "socks").put("listen", "127.0.0.1").put("listen_port", port)
            .put("users", org.json.JSONArray().put(JSONObject().put("username", username).put("password", password)))))
        .put("outbounds", org.json.JSONArray().put(JSONObject(profile.outbound)).put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy")).toString()
    fun bridge(profile: Profile, port: Int, username: String, password: String): Profile = profile.copy(engine = "sing-box", outbound = JSONObject()
        .put("type", "socks").put("tag", "proxy").put("server", "127.0.0.1").put("server_port", port).put("version", "5")
        .put("username", username).put("password", password).toString())
    fun xray(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings()): String = JSONObject().put("log", JSONObject().put("loglevel", "none"))
        .put("dns", JSONObject().put("servers", org.json.JSONArray().put(settings.bootstrapDns.replaceFirst("https://", "https+local://"))).put("queryStrategy", "UseIPv4"))
        .put("inbounds", org.json.JSONArray().put(JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "password").put("udp", true).put("accounts", org.json.JSONArray().put(JSONObject().put("user", username).put("pass", password))))))
        .put("outbounds", org.json.JSONArray().put(JSONObject(profile.outbound).apply {
            getJSONObject("streamSettings").put("sockopt", JSONObject().put("domainStrategy", "ForceIPv4"))
        })).toString()
}
