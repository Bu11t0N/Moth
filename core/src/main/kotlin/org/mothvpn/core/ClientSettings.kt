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
    val serverSort: String = ServerOrder.SUBSCRIPTION,
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
    val favorites: List<String> = emptyList(),
    /** Notify 3 days before and on the day the subscription ends (when the provider sends an end date). */
    val expiryWarning: Boolean = true,
    /** User routing rules (domain / suffix / keyword / IP CIDR -> proxy, direct or block), applied in order before the built-in rules. */
    val routingRules: List<RoutingRule> = emptyList(),
    /** Warn when a subscription has used this share of its traffic: 0 (off), 80, 90 or 95 percent. */
    val trafficWarning: Int = TrafficWarning.DEFAULT,
    /** TLS ClientHello fragmentation against DPI: a [DpiTricks.fragmentPresets] id, "off" by default. */
    val fragment: String = "off",
    /** Random UDP noise packets before the first real packet (Xray freedom noises). */
    val noise: Boolean = false,
    /** Connection multiplexing: "off", or the sing-box protocol "smux" / "yamux" / "h2mux" (Xray servers use Mux.Cool instead). */
    val mux: String = "off",
    /** Developer option: "Edit JSON" in the server long-press menu. */
    val editServers: Boolean = false,
    /** Developer option: the experimental monochrome design (0.7). Off keeps the classic look. */
    val newDesign: Boolean = false
) {
    /** The default user agent follows the app version; "Moth", "Moth/0.2" etc. become "Moth/<version>". Custom values are kept. */
    fun withAppVersion(version: String): ClientSettings =
        if (autoUserAgent.matches(userAgent)) copy(userAgent = "Moth/$version") else this
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
        require(trafficWarning in TrafficWarning.thresholds) { "Неверный порог предупреждения о трафике" }
        require(CustomRouting.valid(routingRules)) { "Неверное правило маршрутизации" }
        require(fragment in DpiTricks.fragmentPresets.keys) { "Неверный режим фрагментации" }
        require(mux in DpiTricks.muxModes) { "Неверный режим мультиплексирования" }
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
        .put("killSwitch", killSwitch).put("favorites", org.json.JSONArray(favorites)).put("expiryWarning", expiryWarning)
        .put("routingRules", org.json.JSONArray().apply { routingRules.forEach { put(it.json()) } }).put("trafficWarning", trafficWarning)
        .put("fragment", fragment).put("noise", noise).put("mux", mux).put("editServers", editServers).put("newDesign", newDesign)
    companion object {
        /** "Moth" or a value written by any earlier build, beta suffixes included ("Moth/0.5.0-beta.3"); custom values do not match. */
        private val autoUserAgent = Regex("Moth(/[0-9][0-9A-Za-z.+-]*)?")
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
            favorites = j.optJSONArray("favorites")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { Regex("[0-9a-f]{16,64}").matches(it) }.distinct().take(5000) }.orEmpty(),
            expiryWarning = j.optBoolean("expiryWarning", true),
            // Invalid saved rules are dropped instead of failing the whole settings file.
            trafficWarning = TrafficWarning.normalize(j.optInt("trafficWarning", TrafficWarning.DEFAULT)),
            fragment = j.optString("fragment", "off").takeIf { it in DpiTricks.fragmentPresets.keys } ?: "off",
            noise = j.optBoolean("noise", false),
            mux = j.optString("mux", "off").takeIf { it in DpiTricks.muxModes } ?: "off",
            editServers = j.optBoolean("editServers", false),
            newDesign = j.optBoolean("newDesign", false),
            routingRules = j.optJSONArray("routingRules")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RoutingRule::from) }.filter { CustomRouting.valid(it) }.take(CustomRouting.MAX_RULES) }.orEmpty()).validate()
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

/**
 * Local helper cores. Every loopback listener is authenticated: probe proxies use SOCKS5 with a random user and password and
 * TCP only (no UDP relay, which SOCKS5 cannot authenticate); the tunnel bridge to Xray and the DPI helper use VLESS with a
 * random UUID, carrying UDP inside the TCP stream (XUDP), so no app on the phone can use them and no UDP port is opened.
 */
object LocalProxyConfig {
    /** Probe proxy for one server: authenticated SOCKS5 (TCP only) on 127.0.0.1. [dpi]: Xray helper for fragments/noise. */
    fun singBox(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings(), dpi: LocalHop? = null): String {
        val root = JSONObject()
            .put("log", JSONObject().put("disabled", true))
            .put("dns", JSONObject().put("servers", org.json.JSONArray().put(SingDns.server("bootstrap", settings.bootstrapDns, null, "system")).put(SingDns.system()))
                .put("final", "bootstrap").put("strategy", "ipv4_only"))
            .put("inbounds", org.json.JSONArray().put(JSONObject().put("type", "socks").put("listen", "127.0.0.1").put("listen_port", port)
                .put("users", org.json.JSONArray().put(JSONObject().put("username", username).put("password", password)))))
            .put("outbounds", org.json.JSONArray())
        SingBoxOutbounds.install(root, profile.outbound, settings, dpi)
        root.getJSONArray("outbounds").put(JSONObject().put("type", "direct").put("tag", "direct"))
        return root.put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy").put("default_domain_resolver", JSONObject().put("server", "bootstrap"))
            .put("rules", org.json.JSONArray().put(JSONObject().put("network", "udp").put("action", "reject")))).toString()
    }
    /** sing-box side of the tunnel bridge to the Xray engine: VLESS to 127.0.0.1 with the session UUID, UDP as XUDP. */
    fun bridge(profile: Profile, port: Int, uuid: String): Profile = profile.copy(engine = "sing-box", outbound = LocalHop(port, uuid).outbound("proxy")
        .put(SingBoxOutbounds.BRIDGE, true).toString())
    private fun vlessInbound(port: Int, uuid: String) = JSONObject().put("tag", "moth-in").put("listen", "127.0.0.1").put("port", port).put("protocol", "vless")
        .put("settings", JSONObject().put("clients", org.json.JSONArray().put(JSONObject().put("id", uuid))).put("decryption", "none"))
    private fun xrayBase(settings: ClientSettings) = JSONObject().put("log", JSONObject().put("loglevel", "none"))
        .put("dns", JSONObject().put("servers", org.json.JSONArray().put(settings.bootstrapDns.replaceFirst("https://", "https+local://"))).put("queryStrategy", "UseIPv4"))
    /** Xray helper for sing-box profiles: VLESS in, freedom with fragment/noise out (the sing-box outbound detours through it). */
    fun dpiHelper(port: Int, uuid: String, settings: ClientSettings): String = xrayBase(settings)
        .put("inbounds", org.json.JSONArray().put(vlessInbound(port, uuid)))
        .put("outbounds", org.json.JSONArray().put(DpiTricks.freedom(settings))).toString()
    /**
     * Xray engine config. [bridge] = true: the tunnel bridge (VLESS inbound, [username] is the session UUID, UDP via XUDP);
     * false: a probe proxy (SOCKS5 with [username]/[password], TCP only).
     */
    fun xray(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings(), bridge: Boolean = false): String {
        val source = JSONObject(profile.outbound)
        val group = source.optBoolean("group")
        val dpi = DpiTricks.active(settings)
        fun protectedOutbound(o: JSONObject) = o.apply {
            val stream = optJSONObject("streamSettings") ?: JSONObject().also { put("streamSettings", it) }
            stream.put("sockopt", JSONObject().put("domainStrategy", "ForceIPv4"))
        }
        fun proxyOutbound(o: JSONObject) = protectedOutbound(o).apply {
            DpiTricks.xrayMux(this, settings)
            if (dpi) getJSONObject("streamSettings").getJSONObject("sockopt").put("dialerProxy", DpiTricks.TAG)
        }
        val inbound = if (bridge) vlessInbound(port, username) else JSONObject().put("tag", "moth-in").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "password").put("udp", false).put("accounts", org.json.JSONArray().put(JSONObject().put("user", username).put("pass", password))))
        val config = xrayBase(settings).put("inbounds", org.json.JSONArray().put(inbound))
        val rules = settings.routingRules
        if (rules.isNotEmpty() && CustomRouting.needsSniff(rules)) inbound
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", org.json.JSONArray(listOf("http", "tls", "quic"))).put("routeOnly", true))
        // User rules: "direct" and "block" outbounds are added only when there are rules, so configs without rules stay as before.
        fun extra(outbounds: org.json.JSONArray) = outbounds.apply {
            if (rules.isNotEmpty()) {
                put(protectedOutbound(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject().put("finalRules", DpiTricks.allowAll())).put("streamSettings", JSONObject())))
                put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            }
            if (dpi) put(DpiTricks.freedom(settings))
        }
        if (!group) {
            val proxyTag = source.optString("tag").ifBlank { "proxy" }.also { source.put("tag", it) }
            config.put("outbounds", extra(org.json.JSONArray().put(proxyOutbound(source))))
            if (rules.isNotEmpty()) config.put("routing", JSONObject().put("domainStrategy", "AsIs")
                .put("rules", org.json.JSONArray(CustomRouting.xray(rules) { it.put("outboundTag", proxyTag) })))
            return config.toString()
        }
        // Balancer group: every connection goes through the balancer; there is no direct outbound or direct fallback.
        val outbounds = org.json.JSONArray()
        val members = source.getJSONArray("outbounds")
        for (i in 0 until members.length()) outbounds.put(proxyOutbound(members.getJSONObject(i)))
        val routing = org.json.JSONArray(CustomRouting.xray(rules) { it.put("balancerTag", "balancer") })
            .put(JSONObject().put("type", "field").put("inboundTag", org.json.JSONArray().put("moth-in")).put("balancerTag", "balancer"))
        return config.put("outbounds", extra(outbounds))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("balancers", org.json.JSONArray().put(source.getJSONObject("balancer")))
                .put("rules", routing))
            .put("burstObservatory", source.getJSONObject("burstObservatory")).toString()
    }
}
