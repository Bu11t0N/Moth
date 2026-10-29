package org.mothvpn.core

import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class ClientSettings(
    val theme: String = "system", val mtu: Int = 1400, val ipv6: Boolean = true,
    val dns: String = "https://1.1.1.1/dns-query",
    val probeUrl: String = "https://www.gstatic.com/generate_204", val timeoutMs: Int = 10000,
    /** Tunnel health check every [healthIntervalSeconds] (one of [HealthSchedule.minutes]; 30 s only in tests). */
    val healthEnabled: Boolean = true, val healthIntervalSeconds: Int = HealthSchedule.DEFAULT_SECONDS,
    val hwidEnabled: Boolean = true, val hwid: String = UUID.randomUUID().toString(),
    /** Send x-device-os / x-ver-os / x-device-model with the HWID. On by default (see [deviceInfoSet] for the migration). */
    val deviceInfo: Boolean = true, val userAgent: String = "Moth",
    val serverSort: String = ServerOrder.SUBSCRIPTION,
    val compactServers: Boolean = true, val hideUnavailable: Boolean = false,
    /** Server check: [ProbePolicy.LATENCY] (second request on the warm connection), [ProbePolicy.FULL] (first request with every handshake) or "tcp". */
    val probeMode: String = ProbePolicy.LATENCY, val bootstrapDns: String = "https://1.1.1.1/dns-query",
    val probeParallel: Int = 5, val probeTimeoutMs: Int = PROBE_TIMEOUT_DEFAULT,
    /** "release", "beta" or "" (not chosen yet: the default follows the installed build, see UpdateChannel.effective). */
    val updateChannel: String = "",
    /** Small icons with explanations instead of the plain dash for failed server checks. */
    val detailedIcons: Boolean = false,
    /** Per-app split tunneling: "bypass" (selected apps bypass the VPN, the default) or "only" (only the selected apps use the VPN); an empty list puts every app in the VPN. */
    val splitMode: String = "bypass", val splitApps: List<String> = emptyList(),
    /** The default routing rule "Russian sites and IPs directly" (shown and switched on the routing rules page since 0.8.0 beta 5):
     *  Russian sites and IP addresses go directly, not through the server. Same key as before, so the saved choice is kept. */
    val directRu: Boolean = false,
    /** Automatic subscription update interval in hours: 0 (off), 6, 12 or 24. */
    val subUpdateHours: Int = 0,
    /** Pick the fastest server on connect and switch to the next one when health checks keep failing. */
    /** Auto-select and failover, separately for Wi-Fi (and any non-mobile network) and mobile data. */
    val autoSelectWifi: Boolean = false,
    val autoSelectMobile: Boolean = false,
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
    /** Dark theme: pure black (#000000) background and surfaces for AMOLED screens. */
    val amoled: Boolean = false,
    /** Keyword server filter of the Wi-Fi page (keywords + mode), matched against server names at display time (see [ServerFilter]). */
    val wifiFilter: ServerFilter.Rule = ServerFilter.Rule(),
    /** The same for the mobile data page: each network has its own keywords and mode. */
    val mobileFilter: ServerFilter.Rule = ServerFilter.Rule(),
    /** HTTPS checks use only [probeUrl], without the fallback addresses (the former "HTTPS · custom URL" method). */
    /** True once the user changed the device-info switch; files without it never had a real choice and get the new default (on). */
    val deviceInfoSet: Boolean = false,
    /** Custom device info sent instead of the system values; empty = the system value ("Android", Build.VERSION.RELEASE, the model). */
    val deviceOs: String = "", val deviceOsVersion: String = "", val deviceModel: String = "",
    /** Lines of the connected notification: speed, total traffic, connection time. With none, the 1-second update does not run for it. */
    val notifySpeed: Boolean = true, val notifyTotal: Boolean = true, val notifyUptime: Boolean = false,
    /** Auto-select after a Wi-Fi <-> mobile switch reuses check results newer than this (minutes, [AutoSelectReuse.minutes]) instead of measuring again. */
    val autoSelectReuseMinutes: Int = AutoSelectReuse.DEFAULT,
    /** Interface: extra height of the expanded large title, in percent of the screen height ([LargeTitleSize.percents]). */
    val largeTitlePercent: Int = LargeTitleSize.DEFAULT,
    /** Interface: large collapsing titles (Settings, Servers); off = the compact title bar only. */
    val largeTitles: Boolean = true
) {
    val notifyAny get() = notifySpeed || notifyTotal || notifyUptime
    /** The custom device info cleared (system values are sent again). */
    fun withDefaultDeviceInfo() = copy(deviceOs = "", deviceOsVersion = "", deviceModel = "")
    /** The filter of a network kind ("wifi" or "mobile"; anything else is Wi-Fi). */
    fun filterFor(kind: String) = if (kind == "mobile") mobileFilter else wifiFilter
    /** Auto-select for the network kind in use ("mobile", anything else counts as Wi-Fi, as for [filterFor]). */
    fun autoSelectFor(kind: String) = if (kind == "mobile") autoSelectMobile else autoSelectWifi
    val autoSelectAny get() = autoSelectWifi || autoSelectMobile
    fun withFilter(kind: String, rule: ServerFilter.Rule) = if (kind == "mobile") copy(mobileFilter = rule) else copy(wifiFilter = rule)
    /** The default user agent follows the app version; "Moth", "Moth/0.2" etc. become "Moth/<version>". Custom values are kept. */
    fun withAppVersion(version: String): ClientSettings =
        if (autoUserAgent.matches(userAgent)) copy(userAgent = "Moth/$version") else this
    /**
     * True when going from this to [next] changes something fixed when the tunnel is built (core config, TUN interface, split tunneling,
     * DPI helper, the health check scheduled at connect), so a running VPN must reconnect once to apply it. Everything else applies live:
     * server checks (read per run), auto-select, kill switch (sent to the service), subscription headers (used by downloads), the UI.
     */
    fun needsReconnect(next: ClientSettings): Boolean =
        mtu != next.mtu || ipv6 != next.ipv6 || dns != next.dns || bootstrapDns != next.bootstrapDns || dnsLeakProtection != next.dnsLeakProtection ||
            directRu != next.directRu || routingRules != next.routingRules || splitMode != next.splitMode || splitApps != next.splitApps ||
            fragment != next.fragment || noise != next.noise || mux != next.mux ||
            healthEnabled != next.healthEnabled || healthIntervalSeconds != next.healthIntervalSeconds || timeoutMs != next.timeoutMs ||
            probeUrl != next.probeUrl || (probeMode == ProbePolicy.TCP) != (next.probeMode == ProbePolicy.TCP)

    fun validate(): ClientSettings {
        require(theme in setOf("system", "dark", "light")) { "Неверная тема" }
        require(updateChannel in setOf("", "release", "beta")) { "Неверный канал обновлений" }
        require(serverSort in ServerOrder.all) { "Неверная сортировка" }
        require(probeMode in ProbePolicy.modes) { "Неверный способ проверки" }
        require(mtu in 1280..9000) { "MTU: от 1280 до 9000" }
        require(probeParallel in 1..16) { "Параллельных проверок: от 1 до 16" }
        require(probeTimeoutMs in 500..30000) { "Таймаут пинга: от 500 до 30000 мс" }
        require(timeoutMs in 1000..60000) { "Тайм-аут: от 1000 до 60000 мс" }
        require(healthIntervalSeconds in 30..900) { "Интервал проверки: от 30 до 900 секунд" }
        require(autoSelectReuseMinutes in AutoSelectReuse.minutes) { "Неверный срок повторного замера" }
        require(largeTitlePercent in LargeTitleSize.percents) { "Неверная высота заголовка" }
        require(hwid.matches(Regex("[a-zA-Z0-9=-]{10,64}"))) { "HWID: 10–64 символа, буквы, цифры, = или -" }
        require(userAgent.isNotBlank() && userAgent.length <= 160 && userAgent.all { it.code in 32..126 }) { "User-Agent должен содержать печатные латинские символы" }
        require(DeviceInfo.valid(deviceOs, 64) && DeviceInfo.valid(deviceOsVersion, 64) && DeviceInfo.valid(deviceModel, 120)) { "Данные устройства: печатные латинские символы" }
        require(splitMode in SplitTunnel.modes) { "Неверный режим раздельного туннеля" }
        require(subUpdateHours in SubscriptionSchedule.intervals) { "Неверный интервал обновления подписки" }
        require(splitApps.size <= 2000 && splitApps.all { SplitTunnel.validPackage(it) }) { "Неверный список приложений" }
        require(DnsServers.valid(dns)) { "Неверный DNS-сервер" }
        require(Favorites.valid(favorites)) { "Неверный список избранного" }
        require(trafficWarning in TrafficWarning.thresholds) { "Неверный порог предупреждения о трафике" }
        require(CustomRouting.valid(routingRules)) { "Неверное правило маршрутизации" }
        require(fragment in DpiTricks.fragmentPresets.keys) { "Неверный режим фрагментации" }
        require(mux in DpiTricks.muxModes) { "Неверный режим мультиплексирования" }
        require(wifiFilter.valid() && mobileFilter.valid()) { "Неверный фильтр серверов" }
        listOf(probeUrl, bootstrapDns).forEach { value ->
            val u = URI(value)
            require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.fragment == null) { "Нужен корректный HTTPS-адрес" }
        }
        return this
    }
    fun json(): JSONObject = JSONObject().put("theme", theme).put("mtu", mtu).put("ipv6", ipv6).put("dns", dns)
        .put("probeUrl", probeUrl).put("timeoutMs", timeoutMs).put("healthEnabled", healthEnabled)
        .put("hwidEnabled", hwidEnabled).put("hwid", hwid)
        .put("deviceInfo", deviceInfo).put("userAgent", userAgent).put("serverSort", serverSort)
        .put("compactServers", compactServers).put("hideUnavailable", hideUnavailable)
        .put("probeMode", probeMode).put("bootstrapDns", bootstrapDns)
        .put("probeParallel", probeParallel).put("probeTimeoutMs", probeTimeoutMs).put("updateChannel", updateChannel).put("detailedIcons", detailedIcons)
        .put("splitMode", splitMode).put("splitApps", org.json.JSONArray(splitApps)).put("directRu", directRu).put("subUpdateHours", subUpdateHours)
        .put("autoSelectWifi", autoSelectWifi).put("autoSelectMobile", autoSelectMobile).put("autoSelect", autoSelectAny).put("bootConnect", bootConnect).put("dnsLeakProtection", dnsLeakProtection)
        .put("killSwitch", killSwitch).put("favorites", org.json.JSONArray(favorites)).put("expiryWarning", expiryWarning)
        .put("routingRules", org.json.JSONArray().apply { routingRules.forEach { put(it.json()) } }).put("trafficWarning", trafficWarning)
        .put("fragment", fragment).put("noise", noise).put("mux", mux).put("editServers", editServers).put("amoled", amoled)
        .put("serverFilterWifi", wifiFilter.json()).put("serverFilterMobile", mobileFilter.json())
        .put("deviceInfoSet", deviceInfoSet).put("deviceOs", deviceOs).put("deviceOsVersion", deviceOsVersion).put("deviceModel", deviceModel)
        // 0.7.6: "healthCheckSeconds" holds the interval (up to 15 min); "healthIntervalSeconds" stays within 30–600 for older builds.
        .put("settingsVersion", SETTINGS_VERSION).put("healthCheckSeconds", healthIntervalSeconds).put("healthIntervalSeconds", healthIntervalSeconds.coerceAtMost(600))
        .put("notifySpeed", notifySpeed).put("notifyTotal", notifyTotal).put("notifyUptime", notifyUptime).put("autoSelectReuseMinutes", autoSelectReuseMinutes)
        .put("largeTitlePercent", largeTitlePercent).put("largeTitles", largeTitles)
    companion object {
        /** 2 (0.7.6): files without it were written by older builds and still carry their defaults (60 s check, 5000 ms ping timeout). */
        const val SETTINGS_VERSION = 2
        const val PROBE_TIMEOUT_DEFAULT = 3000
        /** "Moth" or a value written by any earlier build, beta suffixes included ("Moth/0.5.0-beta.3"); custom values do not match. */
        private val autoUserAgent = Regex("Moth(/[0-9][0-9A-Za-z.+-]*)?")
        fun from(j: JSONObject) = ClientSettings(j.optString("theme", "system"), j.optInt("mtu", 1400), j.optBoolean("ipv6", true),
            DnsServers.migrate(j.optString("dns", "https://1.1.1.1/dns-query")), j.optString("probeUrl", "https://www.gstatic.com/generate_204"),
            (if (j.has("timeoutMs")) j.getInt("timeoutMs") else j.optInt("timeoutSeconds", 10) * 1000).coerceIn(1000, 60000), j.optBoolean("healthEnabled", true),
            if (j.has("healthCheckSeconds")) j.getInt("healthCheckSeconds").coerceIn(30, 900) else HealthSchedule.migrate(j.optInt("healthIntervalSeconds", 60)),
            j.optBoolean("hwidEnabled", true), j.getString("hwid"), j.optBoolean("deviceInfo", false),
            j.optString("userAgent", "Moth"), j.optString("serverSort", if (j.optBoolean("sortByLatency", false)) "latency" else "name").takeIf { it in ServerOrder.all } ?: "name",
            j.optBoolean("compactServers", true), j.optBoolean("hideUnavailable", false),
            ProbePolicy.migrateMode(j.optString("probeMode", ProbePolicy.LATENCY)), j.optString("bootstrapDns", "https://1.1.1.1/dns-query"),
            j.optInt("probeParallel", 5).coerceIn(1, 16),
            (if (j.has("probeTimeoutMs")) j.getInt("probeTimeoutMs") else if (j.has("probeTimeoutSeconds")) j.getInt("probeTimeoutSeconds") * 1000
                else j.optInt("timeoutSeconds", 10) * 1000).coerceIn(500, 30000)
                // The old default (5000 ms) becomes the new one; a value the user chose is kept.
                .let { if (j.optInt("settingsVersion", 1) < 2 && it == 5000) PROBE_TIMEOUT_DEFAULT else it },
            j.optString("updateChannel", "").takeIf { it in setOf("release", "beta") } ?: "",
            j.optBoolean("detailedIcons", false)).copy(
            splitMode = SplitTunnel.migrateMode(j.optString("splitMode", "bypass")),
            // 0.7.3 and earlier: apps ticked under "off" (or an unknown mode) were not applied; they must not start bypassing the VPN now.
            splitApps = if (j.optString("splitMode", "bypass") !in SplitTunnel.modes) emptyList()
                else j.optJSONArray("splitApps")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter(SplitTunnel::validPackage).distinct() }.orEmpty(),
            directRu = j.optBoolean("directRu", false),
            subUpdateHours = SubscriptionSchedule.normalize(j.optInt("subUpdateHours", 0)),
            // 0.7.2 and earlier: one "autoSelect" for both networks; it seeds both.
            autoSelectWifi = j.optBoolean("autoSelectWifi", j.optBoolean("autoSelect", false)), autoSelectMobile = j.optBoolean("autoSelectMobile", j.optBoolean("autoSelect", false)), bootConnect = j.optBoolean("bootConnect", false),
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
            amoled = j.optBoolean("amoled", false),
            // beta 4 had one filter ("serverFilter" + "serverFilterMode"); it becomes the filter of both networks.
            wifiFilter = j.optJSONObject("serverFilterWifi")?.let(ServerFilter.Rule::from) ?: ServerFilter.Rule.legacy(j),
            mobileFilter = j.optJSONObject("serverFilterMobile")?.let(ServerFilter.Rule::from) ?: ServerFilter.Rule.legacy(j),
            // Up to beta 6 "deviceInfo" was always written (default off), so a stored false is not a real choice: only files with
            // "deviceInfoSet" keep their value; everyone else gets the new default (on).
            deviceInfoSet = j.optBoolean("deviceInfoSet", false),
            deviceInfo = if (j.optBoolean("deviceInfoSet", false)) j.optBoolean("deviceInfo", true) else true,
            deviceOs = DeviceInfo.clean(j.optString("deviceOs", ""), 64), deviceOsVersion = DeviceInfo.clean(j.optString("deviceOsVersion", ""), 64),
            deviceModel = DeviceInfo.clean(j.optString("deviceModel", ""), 120),
            notifySpeed = j.optBoolean("notifySpeed", true), notifyTotal = j.optBoolean("notifyTotal", true), notifyUptime = j.optBoolean("notifyUptime", false),
            autoSelectReuseMinutes = AutoSelectReuse.normalize(j.optInt("autoSelectReuseMinutes", AutoSelectReuse.DEFAULT)),
            largeTitlePercent = LargeTitleSize.normalize(j.optInt("largeTitlePercent", LargeTitleSize.DEFAULT)), largeTitles = j.optBoolean("largeTitles", true),
            routingRules = j.optJSONArray("routingRules")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RoutingRule::from) }.filter { CustomRouting.valid(it) }.take(CustomRouting.MAX_RULES) }.orEmpty()).validate()
    }
}

/** Device info values sent in subscription headers: printable ASCII, length-capped; surrounding spaces are trimmed when sent. */
object DeviceInfo {
    fun clean(value: String, max: Int) = value.filter { it.code in 32..126 }.trim().take(max)
    fun valid(value: String, max: Int) = value.length <= max && value.all { it.code in 32..126 }
}

object SubscriptionHeaders {
    fun create(settings: ClientSettings, original: URI, destination: URI, osVersion: String, model: String): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to settings.userAgent, "Accept" to "text/plain, application/yaml, application/json, application/octet-stream")
        val sameOrigin = original.scheme == destination.scheme && original.host.equals(destination.host, true) &&
            (if (original.port == -1) 443 else original.port) == (if (destination.port == -1) 443 else destination.port)
        if (settings.hwidEnabled && sameOrigin) {
            headers["x-hwid"] = settings.hwid
            if (settings.deviceInfo) {
                // Custom values (Settings → Subscription) replace the system ones; empty fields keep the system value.
                headers["x-device-os"] = settings.deviceOs.trim().ifEmpty { "Android" }
                headers["x-ver-os"] = settings.deviceOsVersion.trim().ifEmpty { DeviceInfo.clean(osVersion, 64) }
                headers["x-device-model"] = settings.deviceModel.trim().ifEmpty { DeviceInfo.clean(model, 120) }
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
    /**
     * Xray 26.9+ verifies TLS against its built-in Mozilla bundle unless "useSystemCA" is set. Moth trusts the system and the
     * user-installed CAs (see TrustedCertificates), so every tlsSettings (also nested ones such as XHTTP downloadSettings) gets it.
     */
    fun useSystemCa(value: Any?, depth: Int = 0) {
        if (depth > 12) return
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { k ->
                val v = value.opt(k)
                if (k == "tlsSettings" && v is JSONObject && !v.has("useSystemCA")) v.put("useSystemCA", true)
                useSystemCa(v, depth + 1)
            }
            is org.json.JSONArray -> (0 until value.length()).forEach { useSystemCa(value.opt(it), depth + 1) }
        }
    }
    fun xray(profile: Profile, port: Int, username: String, password: String, settings: ClientSettings = ClientSettings(), bridge: Boolean = false): String {
        val source = JSONObject(profile.outbound)
        val group = source.optBoolean("group")
        val dpi = DpiTricks.active(settings)
        fun protectedOutbound(o: JSONObject) = o.apply {
            useSystemCa(this)
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

/** Tunnel health check interval (Settings › Connection). */
object HealthSchedule {
    val minutes = listOf(1, 2, 5, 10, 15)
    const val DEFAULT_SECONDS = 300
    /** A scheduled check still runs at least this often while traffic keeps it skipped. */
    const val MAX_SKIP_MS = 30 * 60_000L
    /** Received bytes since the previous scheduled check that count as "traffic is flowing" (more than a failed handshake or a few resets). */
    const val TRAFFIC_BYTES = 64 * 1024L
    /** Values from 0.7.5 and earlier (free 30–600 s field, default 60 s): the old default becomes 5 min, anything else the nearest option. */
    fun migrate(seconds: Int): Int = if (seconds == 60) DEFAULT_SECONDS else minutes.map { it * 60 }.minBy { kotlin.math.abs(it - seconds) }
    enum class Skip { NO_NETWORK, TRAFFIC }
    /**
     * Whether a scheduled check can be skipped. No network: nothing to check. Traffic arrived through the tunnel since the previous
     * scheduled check: the server works. Never while checks are failing (failover counts on them) and never for longer than [MAX_SKIP_MS].
     * [rxBefore] < 0: no previous sample (the first check after connecting always runs).
     */
    fun skip(hasNetwork: Boolean, failures: Int, rxBefore: Long, rxNow: Long, sinceLastCheckMs: Long): Skip? = when {
        !hasNetwork -> Skip.NO_NETWORK
        failures > 0 || rxBefore < 0 || sinceLastCheckMs >= MAX_SKIP_MS -> null
        rxNow - rxBefore >= TRAFFIC_BYTES -> Skip.TRAFFIC
        else -> null
    }
}

/** How long auto-select reuses check results after a Wi-Fi <-> mobile switch. */
object AutoSelectReuse {
    val minutes = listOf(5, 15, 30, 60)
    const val DEFAULT = 30
    fun normalize(value: Int) = if (value in minutes) value else DEFAULT
}

/**
 * Extra height of the expanded large title (Settings, Servers) in percent of the screen height, a developer option.
 * Soft limits: 10–30 % (the header itself is also kept within 56–260 dp, see LargeTitle.kt), so the page never loses its list.
 */
object LargeTitleSize {
    const val MIN = 10
    const val MAX = 30
    const val DEFAULT = 20
    val percents = MIN..MAX
    fun normalize(value: Int) = value.coerceIn(MIN, MAX)
    /** The extra height in dp for a screen [screenHeightDp] high, within 56–260 dp. */
    fun extraDp(percent: Int, screenHeightDp: Int): Float = extraDp(percent.toFloat(), screenHeightDp)
    /** The same for a fractional percent (the slider moves smoothly between whole steps while dragged). */
    fun extraDp(percent: Float, screenHeightDp: Int): Float = (screenHeightDp * percent.coerceIn(MIN.toFloat(), MAX.toFloat()) / 100f).coerceIn(56f, 260f)
    /** Size of the expanded title for a height [percent], relative to the default (20 %): 0.75 at 10 %, 1.25 at 30 %, so it grows with the header. */
    fun textScale(percent: Float): Float = 1f + (percent.coerceIn(MIN.toFloat(), MAX.toFloat()) - DEFAULT) * 0.025f
}
