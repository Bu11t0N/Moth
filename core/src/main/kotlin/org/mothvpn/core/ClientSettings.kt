package org.mothvpn.core

import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class ClientSettings(
    val theme: String = "system", val mtu: Int = 1400, val ipv6: Boolean = true,
    val dns: String = "https://1.1.1.1/dns-query",
    val probeUrl: String = "https://www.gstatic.com/generate_204", val timeoutMs: Int = 10000,
    val healthEnabled: Boolean = true, val healthIntervalSeconds: Int = 60,
    val hwidEnabled: Boolean = true, val hwid: String = UUID.randomUUID().toString(),
    val deviceInfo: Boolean = false, val userAgent: String = "Moth",
    val serverSort: String = "name",
    val compactServers: Boolean = true, val hideUnavailable: Boolean = false,
    val probeMode: String = "auto", val bootstrapDns: String = "https://1.1.1.1/dns-query",
    val probeParallel: Int = 5, val probeTimeoutMs: Int = 5000,
    /** "release", "beta" or "" (not chosen yet: the default follows the installed build, see UpdateChannel.effective). */
    val updateChannel: String = "",
    /** Developer option: small icons with explanations instead of the red dash for failed server checks. */
    val detailedIcons: Boolean = false,
    /** Per-app split tunneling: "off", "only" (only the selected apps use the VPN) or "bypass" (selected apps bypass the VPN). */
    val splitMode: String = "off", val splitApps: List<String> = emptyList(),
    /** Russian sites and IP addresses go directly, not through the server. */
    val directRu: Boolean = false,
    /** Automatic subscription update interval in hours: 0 (off), 6, 12 or 24. */
    val subUpdateHours: Int = 0,
    /** Pick the fastest server on connect and switch to the next one when health checks keep failing. */
    val autoSelect: Boolean = false,
    /** Connect to the last server when the phone starts. */
    val bootConnect: Boolean = false,
    /** Strict routing: DNS and traffic cannot bypass the tunnel. */
    val dnsLeakProtection: Boolean = true,
    /** Kill switch: if the connection fails, keep the VPN interface up and block all traffic until it reconnects or the user disconnects. */
    val killSwitch: Boolean = false,
    /** Favorite server ids (stable: sha256 of the link without the name). */
    val favorites: List<String> = emptyList()
) {
    /** The default user agent follows the app version; "Moth", "Moth/0.2" etc. become "Moth/<version>". Custom values are kept. */
    fun withAppVersion(version: String): ClientSettings =
        if (Regex("Moth(/[0-9][0-9.]*)?").matches(userAgent)) copy(userAgent = "Moth/$version") else this
    fun validate(): ClientSettings {
        require(theme in setOf("system", "dark", "light")) { "Неверная тема" }
        require(updateChannel in setOf("", "release", "beta")) { "Неверный канал обновлений" }
        require(serverSort in ServerOrder.all) { "Неверная сортировка" }
        require(probeMode in setOf("auto", "https", "tcp")) { "Неверный способ проверки" }
        require(mtu in 1280..9000) { "MTU: от 1280 до 9000" }
        require(probeParallel in 1..16) { "Параллельных проверок: от 1 до 16" }
        require(probeTimeoutMs in 500..30000) { "Таймаут пинга: от 500 до 30000 мс" }
        require(timeoutMs in 1000..60000) { "Тайм-аут: от 1000 до 60000 мс" }
        require(healthIntervalSeconds in 30..600) { "Интервал проверки: от 30 до 600 секунд" }
        require(hwid.matches(Regex("[a-zA-Z0-9=-]{10,64}"))) { "HWID: 10–64 символа, буквы, цифры, = или -" }
        require(userAgent.isNotBlank() && userAgent.length <= 160 && userAgent.all { it.code in 32..126 }) { "User-Agent должен содержать печатные латинские символы" }
        require(splitMode in SplitTunnel.modes) { "Неверный режим раздельного туннеля" }
        require(subUpdateHours in SubscriptionSchedule.intervals) { "Неверный интервал обновления подписки" }
        require(splitApps.size <= 2000 && splitApps.all { SplitTunnel.validPackage(it) }) { "Неверный список приложений" }
        require(DnsServers.valid(dns)) { "Неверный DNS-сервер" }
        require(Favorites.valid(favorites)) { "Неверный список избранного" }
        listOf(probeUrl, bootstrapDns).forEach { value ->
            val u = URI(value)
            require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.fragment == null) { "Нужен корректный HTTPS-адрес" }
        }
        return this
    }
    fun json(): JSONObject = JSONObject().put("theme", theme).put("mtu", mtu).put("ipv6", ipv6).put("dns", dns)
        .put("probeUrl", probeUrl).put("timeoutMs", timeoutMs).put("healthEnabled", healthEnabled)
        .put("healthIntervalSeconds", healthIntervalSeconds).put("hwidEnabled", hwidEnabled).put("hwid", hwid)
        .put("deviceInfo", deviceInfo).put("userAgent", userAgent).put("serverSort", serverSort)
        .put("compactServers", compactServers).put("hideUnavailable", hideUnavailable)
        .put("probeMode", probeMode).put("bootstrapDns", bootstrapDns)
        .put("probeParallel", probeParallel).put("probeTimeoutMs", probeTimeoutMs).put("updateChannel", updateChannel).put("detailedIcons", detailedIcons)
        .put("splitMode", splitMode).put("splitApps", org.json.JSONArray(splitApps)).put("directRu", directRu).put("subUpdateHours", subUpdateHours)
        .put("autoSelect", autoSelect).put("bootConnect", bootConnect).put("dnsLeakProtection", dnsLeakProtection)
        .put("killSwitch", killSwitch).put("favorites", org.json.JSONArray(favorites))
    companion object {
        fun from(j: JSONObject) = ClientSettings(j.optString("theme", "system"), j.optInt("mtu", 1400), j.optBoolean("ipv6", true),
            DnsServers.migrate(j.optString("dns", "https://1.1.1.1/dns-query")), j.optString("probeUrl", "https://www.gstatic.com/generate_204"),
            (if (j.has("timeoutMs")) j.getInt("timeoutMs") else j.optInt("timeoutSeconds", 10) * 1000).coerceIn(1000, 60000), j.optBoolean("healthEnabled", true), j.optInt("healthIntervalSeconds", 60),
            j.optBoolean("hwidEnabled", true), j.getString("hwid"), j.optBoolean("deviceInfo", false),
            j.optString("userAgent", "Moth"), j.optString("serverSort", if (j.optBoolean("sortByLatency", false)) "latency" else "name").takeIf { it in ServerOrder.all } ?: "name",
            j.optBoolean("compactServers", true), j.optBoolean("hideUnavailable", false),
            j.optString("probeMode", "auto"), j.optString("bootstrapDns", "https://1.1.1.1/dns-query"),
            j.optInt("probeParallel", 5).coerceIn(1, 16),
            (if (j.has("probeTimeoutMs")) j.getInt("probeTimeoutMs") else if (j.has("probeTimeoutSeconds")) j.getInt("probeTimeoutSeconds") * 1000
                else j.optInt("timeoutSeconds", 10) * 1000).coerceIn(500, 30000),
            j.optString("updateChannel", "").takeIf { it in setOf("release", "beta") } ?: "",
            j.optBoolean("detailedIcons", false)).copy(
            splitMode = j.optString("splitMode", "off").takeIf { it in SplitTunnel.modes } ?: "off",
            splitApps = j.optJSONArray("splitApps")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter(SplitTunnel::validPackage).distinct() }.orEmpty(),
            directRu = j.optBoolean("directRu", false),
            subUpdateHours = SubscriptionSchedule.normalize(j.optInt("subUpdateHours", 0)),
            autoSelect = j.optBoolean("autoSelect", false), bootConnect = j.optBoolean("bootConnect", false),
            dnsLeakProtection = j.optBoolean("dnsLeakProtection", true),
            killSwitch = j.optBoolean("killSwitch", false),
            favorites = j.optJSONArray("favorites")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { Regex("[0-9a-f]{16,64}").matches(it) }.distinct().take(5000) }.orEmpty()).validate()
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
    fun xray(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings()): String {
        val source = JSONObject(profile.outbound)
        val group = source.optBoolean("group")
        fun protectedOutbound(o: JSONObject) = o.apply { getJSONObject("streamSettings").put("sockopt", JSONObject().put("domainStrategy", "ForceIPv4")) }
        val config = JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("dns", JSONObject().put("servers", org.json.JSONArray().put(settings.bootstrapDns.replaceFirst("https://", "https+local://"))).put("queryStrategy", "UseIPv4"))
            .put("inbounds", org.json.JSONArray().put(JSONObject().put("tag", "moth-in").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "password").put("udp", true).put("accounts", org.json.JSONArray().put(JSONObject().put("user", username).put("pass", password))))))
        if (!group) return config.put("outbounds", org.json.JSONArray().put(protectedOutbound(source))).toString()
        // Balancer group: every connection goes through the balancer; there is no direct outbound or direct fallback.
        val outbounds = org.json.JSONArray()
        val members = source.getJSONArray("outbounds")
        for (i in 0 until members.length()) outbounds.put(protectedOutbound(members.getJSONObject(i)))
        return config.put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("balancers", org.json.JSONArray().put(source.getJSONObject("balancer")))
                .put("rules", org.json.JSONArray().put(JSONObject().put("type", "field").put("inboundTag", org.json.JSONArray().put("moth-in")).put("balancerTag", "balancer"))))
            .put("burstObservatory", source.getJSONObject("burstObservatory")).toString()
    }
}
