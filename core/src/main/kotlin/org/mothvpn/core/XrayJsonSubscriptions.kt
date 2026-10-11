package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Subscriptions delivered as full Xray-core JSON configs: an array of config documents, one config object,
 * or an object/array that only lists outbounds. Only a whitelist is taken from each document (proxy outbounds,
 * routing balancers, burstObservatory). Inbounds, dns, log, api, stats, policy and routing rules are ignored.
 *
 * Group ("fastest") profiles: Profile.engine = "xray" and Profile.outbound is
 * {"group":true,"outbounds":[...],"balancer":{...},"burstObservatory":{...}}; see LocalProxyConfig.xray.
 * Fallback decision: traffic is never sent direct. A balancer fallbackTag is kept only if it names a member proxy,
 * otherwise the first member is the fallback (so the first connection works before the observatory has data).
 */
object XrayJsonSubscriptions {
    private const val MAX_DOCUMENTS = 500
    private const val MAX_OUTBOUNDS = 300
    private val infrastructure = setOf("freedom", "blackhole", "dns", "loopback", "block", "direct")
    private val networks = setOf("tcp", "raw", "grpc", "ws", "xhttp", "splithttp", "httpupgrade")
    private val durationRegex = Regex("\\d{1,6}(ms|s|m|h)")

    private class Reject(message: String) : Exception(message)
    private fun reject(message: String): Nothing = throw Reject(message)
    private data class Conversion(val profiles: List<Profile>, val rejected: Int)

    /** Bound provider-controlled background work, including total probe frequency across a group. */
    private fun boundedDuration(value: String, defaultMs: Long, minMs: Long, maxMs: Long): String {
        val match = Regex("(\\d{1,9})(ms|s|m|h)").matchEntire(value)
        val millis = match?.let {
            val multiplier = when (it.groupValues[2]) { "ms" -> 1L; "s" -> 1000L; "m" -> 60_000L; else -> 3_600_000L }
            it.groupValues[1].toLong() * multiplier
        } ?: defaultMs
        return "${millis.coerceIn(minMs, maxMs)}ms"
    }
    private class Member(val tag: String, val outbound: JSONObject, val label: String, val address: String) {
        /** Display fallback when there are no remarks: a meaningful tag, else the address. */
        val fallbackName get() = tag.takeUnless { Regex("proxy(-\\d+)?").matches(it) } ?: address
    }

    private fun root(text: String): Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull()

    /** True when the text is an Xray JSON subscription (has outbounds / a list of Xray outbounds). */
    fun looksLike(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        return when (val r = root(text)) {
            is JSONArray -> (0 until r.length()).any { r.optJSONObject(it)?.let { o -> o.has("outbounds") || o.has("protocol") } == true }
            is JSONObject -> r.has("outbounds") || (r.optJSONArray("proxies")?.optJSONObject(0)?.has("protocol") == true)
            else -> false
        }
    }

    private fun documents(root: Any?): List<JSONObject> {
        fun wrap(outbounds: JSONArray, remarks: String) = JSONObject().put("outbounds", outbounds).put("remarks", remarks)
        return when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { i ->
                val o = root.optJSONObject(i) ?: return@mapNotNull null
                when {
                    o.has("outbounds") -> o
                    o.has("protocol") -> wrap(JSONArray().put(o), o.optString("remarks"))
                    else -> null
                }
            }
            is JSONObject -> when {
                root.has("outbounds") -> listOf(root)
                else -> root.optJSONArray("proxies")?.let { listOf(wrap(it, root.optString("remarks"))) } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    fun parse(text: String): ImportResult {
        require(text.length <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
        val docs = documents(root(text))
        require(docs.isNotEmpty()) { "Нет конфигураций Xray JSON" }
        require(docs.size <= MAX_DOCUMENTS) { "Слишком много серверов" }
        var rejected = 0
        val reasons = mutableListOf<String>()
        val profiles = mutableListOf<Profile>()
        docs.forEachIndexed { index, doc ->
            try {
                val converted = convertDocument(doc, index, reasons)
                profiles += converted.profiles
                rejected += converted.rejected
            }
            catch (e: Reject) { rejected++; reasons += "document $index: ${e.message}" }
            catch (e: Exception) { rejected++; reasons += "document $index: ${e.javaClass.simpleName}" }
        }
        val unique = profiles.distinctBy { it.id }
        require(unique.isNotEmpty()) { "Нет поддерживаемых серверов в Xray JSON" }
        return ImportResult(unique, rejected, reasons)
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun convertDocument(doc: JSONObject, index: Int, reasons: MutableList<String>): Conversion {
        val remarks = doc.optString("remarks").trim()
        val outbounds = doc.optJSONArray("outbounds") ?: reject("нет outbounds")
        require(outbounds.length() <= MAX_OUTBOUNDS) { "Слишком много outbounds" }
        val proxies = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("protocol").lowercase() !in infrastructure }
        if (proxies.isEmpty()) reject("нет прокси-outbounds")
        val xray = mutableListOf<Member>()
        val others = mutableListOf<Pair<String, Profile>>() // tag to sing-box profile (Hysteria2)
        var rejectedOutbounds = 0
        proxies.forEachIndexed { i, o ->
            val tag = o.optString("tag").ifBlank { "proxy-$i" }
            try {
                when (o.optString("protocol").lowercase()) {
                    "vless" -> xray += convertVless(o, tag)
                    "hysteria" -> others += tag to convertHysteria(o, remarks)
                    else -> reject("протокол ${o.optString("protocol").take(20).filter { it.isLetterOrDigit() }} не поддерживается")
                }
            } catch (e: Reject) { rejectedOutbounds++; reasons += "document $index outbound $i: ${e.message}" }
            catch (e: Exception) { rejectedOutbounds++; reasons += "document $index outbound $i: ${e.javaClass.simpleName}" }
        }
        // Balancer group over the Xray members (the provider's "fastest" entry).
        val balancer = doc.optJSONObject("routing")?.optJSONArray("balancers")?.optJSONObject(0)
        if (balancer != null) {
            val selector = balancer.optJSONArray("selector")?.let { s -> (0 until s.length()).map { s.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            val members = xray.filter { m -> selector.isEmpty() || selector.any { m.tag.startsWith(it) } }
            if (members.size >= 2) {
                if (others.isNotEmpty()) reasons += "document $index: ${others.size} Hysteria member(s) skipped in balancer group"
                val excluded = xray.size - members.size + others.size
                if (xray.size > members.size) reasons += "document $index: ${xray.size - members.size} proxy member(s) outside balancer selector skipped"
                return Conversion(listOf(group(remarks, members, balancer, doc.optJSONObject("burstObservatory"))), rejectedOutbounds + excluded)
            }
        }
        val result = mutableListOf<Profile>()
        val multiple = proxies.size > 1
        xray.forEach { m ->
            val name = if (multiple) listOf(remarks, m.tag).filter { it.isNotBlank() }.joinToString(" · ") else remarks.ifBlank { m.fallbackName }
            result += Profile(sha(remarks + "|" + m.outbound.toString()), name.take(160), "vless · ${m.label}".lowercase(), m.outbound.put("tag", "proxy").toString(), "xray")
        }
        others.forEach { (tag, p) -> result += if (multiple) p.copy(name = listOf(remarks, tag).filter { it.isNotBlank() }.joinToString(" · ").take(160)) else p }
        if (rejectedOutbounds > 0 && result.isNotEmpty()) reasons += "document $index: $rejectedOutbounds outbound(s) rejected"
        return Conversion(result, rejectedOutbounds)
    }

    private fun group(remarks: String, members: List<Member>, balancer: JSONObject, burst: JSONObject?): Profile {
        val tags = members.mapIndexed { i, m -> "proxy-${i + 1}" }
        val outbounds = JSONArray()
        members.forEachIndexed { i, m -> outbounds.put(JSONObject(m.outbound.toString()).put("tag", tags[i])) }
        val originalFallback = balancer.optString("fallbackTag")
        val fallback = members.indexOfFirst { it.tag == originalFallback }.takeIf { it >= 0 }?.let { tags[it] } ?: tags.first()
        val strategyIn = balancer.optJSONObject("strategy")
        val type = strategyIn?.optString("type").orEmpty().ifBlank { "random" }
        require(type in setOf("leastLoad", "leastPing", "random", "roundRobin")) { "Неизвестная стратегия balancer" }
        val strategy = JSONObject().put("type", type)
        strategyIn?.optJSONObject("settings")?.let { s ->
            val clean = JSONObject()
            if (s.has("expected")) s.optInt("expected", -1).takeIf { it in 1..100 }?.let { clean.put("expected", it) }
            s.optString("maxRTT").takeIf { durationRegex.matches(it) }?.let { clean.put("maxRTT", it) }
            if (s.has("tolerance")) s.optDouble("tolerance", -1.0).takeIf { it in 0.0..1.0 }?.let { clean.put("tolerance", it) }
            s.optJSONArray("baselines")?.let { b -> clean.put("baselines", JSONArray((0 until b.length()).map { b.optString(it) }.filter { durationRegex.matches(it) })) }
            if (clean.length() > 0) strategy.put("settings", clean)
        }
        val ping = burst?.optJSONObject("pingConfig")
        val pingClean = JSONObject().put("destination", "https://www.gstatic.com/generate_204").put("interval", "5m").put("sampling", 2).put("timeout", "5s")
        ping?.optString("destination")?.takeIf { it.startsWith("https://") && it.length <= 200 && !it.substringAfter("://").substringBefore('/').contains('@') && it.none(Char::isWhitespace) }?.let { pingClean.put("destination", it) }
        // At least 10 seconds between probes per member, and at most one per second on average across the group.
        // Xray's own interval guard is insufficient in the pinned version; enforce the bounds here.
        pingClean.put("interval", boundedDuration(ping?.optString("interval").orEmpty(), 300_000L, maxOf(10_000L, members.size * 1000L), 86_400_000L))
        pingClean.put("timeout", boundedDuration(ping?.optString("timeout").orEmpty(), 5000L, 500L, 30_000L))
        if (ping?.has("sampling") == true) ping.optInt("sampling", -1).takeIf { it in 1..20 }?.let { pingClean.put("sampling", it) }
        val g = JSONObject().put("group", true).put("outbounds", outbounds)
            .put("balancer", JSONObject().put("tag", "balancer").put("selector", JSONArray(tags)).put("fallbackTag", fallback).put("strategy", strategy))
            .put("burstObservatory", JSONObject().put("subjectSelector", JSONArray(tags)).put("pingConfig", pingClean))
        val name = remarks.ifBlank { "Xray balancer" }
        return Profile(sha(remarks + "|group|" + outbounds.toString()), name.take(160), "vless · balancer", g.toString(), "xray")
    }

    // ---- VLESS ----
    private fun safeText(value: String, max: Int = 255) = value.isNotEmpty() && value.length <= max && value.none { it.code < 32 || it.code == 127 }
    private fun bool(o: JSONObject, key: String) = o.opt(key).let { it == true || it == "true" || it == 1 || it == "1" }
    private fun port(value: Any?): Int = (value?.toString()?.toIntOrNull() ?: reject("нет порта")).also { if (it !in 1..65535) reject("неверный порт") }

    private fun convertVless(o: JSONObject, tag: String): Member {
        val s = o.optJSONObject("settings") ?: reject("нет settings")
        val vnext = s.optJSONArray("vnext")
        if (vnext != null && vnext.length() != 1) reject("нужен ровно один сервер")
        val server = vnext?.optJSONObject(0)
        val user = server?.optJSONArray("users")?.also { if (it.length() != 1) reject("нужен ровно один пользователь") }?.optJSONObject(0)
        val address = (server?.optString("address") ?: s.optString("address")).removeSurrounding("[", "]")
        val id = user?.optString("id") ?: s.optString("id")
        val flow = user?.optString("flow") ?: s.optString("flow")
        val encryption = (user?.optString("encryption") ?: s.optString("encryption")).ifBlank { "none" }
        val port = port(server?.opt("port") ?: s.opt("port"))
        if (!Regex("[A-Za-z0-9._:-]{1,255}").matches(address)) reject("неверный адрес сервера")
        val uuid = runCatching { UUID.fromString(id).toString() }.getOrNull() ?: reject("неверный id")
        if (encryption != "none") reject("шифрование VLESS $encryption не поддерживается")
        if (flow !in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) reject("flow не поддерживается")
        val stream = stream(o.optJSONObject("streamSettings") ?: JSONObject(), address)
        val network = stream.getString("network")
        if (flow.isNotEmpty() && network != "tcp") reject("Vision flow только для TCP")
        val account = JSONObject().put("id", uuid).put("encryption", "none").apply { if (flow.isNotEmpty()) put("flow", flow) }
        val out = JSONObject().put("tag", tag).put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", address).put("port", port).put("users", JSONArray().put(account)))))
            .put("streamSettings", stream)
        o.optJSONObject("mux")?.takeIf { bool(it, "enabled") }?.let { m ->
            if (flow.isEmpty()) {
                val mux = JSONObject().put("enabled", true)
                m.optInt("concurrency", 0).takeIf { it in -1..1024 && it != 0 }?.let { mux.put("concurrency", it) }
                m.optInt("xudpConcurrency", 0).takeIf { it in -1..1024 && it != 0 }?.let { mux.put("xudpConcurrency", it) }
                m.optString("xudpProxyUDP443").takeIf { it in setOf("reject", "allow", "skip") }?.let { mux.put("xudpProxyUDP443", it) }
                out.put("mux", mux)
            }
        }
        val security = stream.getString("security")
        return Member(tag, out, "$network · $security".replace("none", "plain").lowercase(), address)
    }

    private fun headers(h: JSONObject?): JSONObject? {
        if (h == null || h.length() == 0) return null
        require(h.length() <= 32) { "Слишком много заголовков" }
        val clean = JSONObject()
        for (name in h.keys()) {
            val value = h.opt(name)?.toString() ?: ""
            if (!Regex("[A-Za-z0-9-]{1,64}").matches(name) || value.length > 1024 || value.any { it.code < 32 && it != '\t' || it.code == 127 }) reject("недопустимый заголовок WS")
            clean.put(name, value)
        }
        return clean
    }

    private fun stream(s: JSONObject, address: String): JSONObject {
        var network = s.optString("network", "tcp").lowercase().ifBlank { "tcp" }
        if (network !in networks) reject("транспорт ${network.take(20).filter { it.isLetterOrDigit() }} не поддерживается")
        if (network == "raw") network = "tcp"
        if (network == "splithttp") network = "xhttp"
        val security = s.optString("security", "none").lowercase().ifBlank { "none" }
        if (security !in setOf("none", "tls", "reality")) reject("security $security не поддерживается")
        val out = JSONObject().put("network", network).put("security", security)
        when (network) {
            "tcp" -> {
                val header = (s.optJSONObject("tcpSettings") ?: s.optJSONObject("rawSettings"))?.optJSONObject("header")
                if (header != null && header.optString("type", "none") != "none") reject("TCP header obfuscation не поддерживается")
            }
            "grpc" -> {
                val g = s.optJSONObject("grpcSettings") ?: JSONObject()
                val c = JSONObject()
                g.optString("serviceName").takeIf { safeText(it, 200) }?.let { c.put("serviceName", it) }
                g.optString("authority").takeIf { safeText(it, 255) && it.none(Char::isWhitespace) }?.let { c.put("authority", it) }
                val mode = g.opt("mode")
                if (mode == true || mode == "multi" || bool(g, "multiMode")) c.put("multiMode", true)
                out.put("grpcSettings", c)
            }
            "ws" -> {
                val w = s.optJSONObject("wsSettings") ?: JSONObject()
                val c = JSONObject().put("path", w.optString("path", "/").ifBlank { "/" }.also { if (!safeText(it, 1024)) reject("неверный WS path") })
                w.optString("host").takeIf { it.isNotBlank() }?.let { if (!safeText(it) || it.any(Char::isWhitespace)) reject("неверный WS host"); c.put("host", it) }
                headers(w.optJSONObject("headers"))?.let { c.put("headers", it) }
                w.optInt("heartbeatPeriod", 0).takeIf { it in 1..3600 }?.let { c.put("heartbeatPeriod", it) }
                out.put("wsSettings", c)
            }
            "xhttp" -> {
                val x = s.optJSONObject("xhttpSettings") ?: s.optJSONObject("splithttpSettings") ?: JSONObject()
                val mode = x.optString("mode", "auto").ifBlank { "auto" }
                if (mode !in setOf("auto", "packet-up", "stream-up", "stream-one")) reject("неизвестный режим XHTTP")
                val c = JSONObject().put("mode", mode).put("path", x.optString("path", "/").ifBlank { "/" }.also { if (!safeText(it, 1024)) reject("неверный XHTTP path") })
                x.optString("host").takeIf { it.isNotBlank() }?.let { if (!safeText(it) || it.any(Char::isWhitespace)) reject("неверный XHTTP host"); c.put("host", it) }
                x.optJSONObject("extra")?.let { e ->
                    val extra = JSONObject()
                    for (k in listOf("xPaddingBytes", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts", "xmux")) if (e.has(k)) extra.put(k, e.get(k))
                    if (extra.length() > 0 && extra.toString().length <= 4096) c.put("extra", extra)
                }
                out.put("xhttpSettings", c)
            }
            "httpupgrade" -> {
                val h = s.optJSONObject("httpupgradeSettings") ?: JSONObject()
                val c = JSONObject().put("path", h.optString("path", "/").ifBlank { "/" }.also { if (!safeText(it, 1024)) reject("неверный path") })
                h.optString("host").takeIf { it.isNotBlank() }?.let { if (!safeText(it) || it.any(Char::isWhitespace)) reject("неверный host"); c.put("host", it) }
                out.put("httpupgradeSettings", c)
            }
        }
        if (security == "tls") {
            val t = s.optJSONObject("tlsSettings") ?: JSONObject()
            if (bool(t, "allowInsecure") || bool(t, "insecure")) reject("небезопасный TLS (allowInsecure)")
            val c = JSONObject().put("serverName", t.optString("serverName").ifBlank { address }.also { if (!safeText(it)) reject("неверный serverName") })
                .put("fingerprint", t.optString("fingerprint").ifBlank { "chrome" })
            t.optJSONArray("alpn")?.let { a -> c.put("alpn", JSONArray((0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() && it.length <= 32 })) }
            if (t.has("enableSessionResumption")) c.put("enableSessionResumption", bool(t, "enableSessionResumption"))
            out.put("tlsSettings", c)
        } else if (security == "reality") {
            val r = s.optJSONObject("realitySettings") ?: reject("нет realitySettings")
            if (bool(r, "allowInsecure")) reject("небезопасный TLS")
            val key = r.optString("publicKey")
            if (key.isBlank() || !safeText(key, 200)) reject("нет публичного ключа REALITY")
            val c = JSONObject().put("serverName", r.optString("serverName").ifBlank { address }.also { if (!safeText(it)) reject("неверный serverName") })
                .put("publicKey", key).put("shortId", r.optString("shortId")).put("spiderX", r.optString("spiderX").ifBlank { "/" })
                .put("fingerprint", r.optString("fingerprint").ifBlank { "chrome" })
            out.put("realitySettings", c)
        }
        return out
    }

    // ---- Hysteria 2 -> the app's sing-box hysteria2 profile ----
    private fun convertHysteria(o: JSONObject, remarks: String): Profile {
        val s = o.optJSONObject("settings") ?: reject("нет settings")
        val version = o.optJSONObject("streamSettings")?.optJSONObject("hysteriaSettings")?.optInt("version", 2) ?: s.optInt("version", 2)
        if (version != 2) reject("поддерживается только Hysteria 2")
        val server = s.optJSONArray("servers")?.optJSONObject(0) ?: s.optJSONArray("vnext")?.optJSONObject(0)
        val address = (server?.optString("address") ?: s.optString("address")).removeSurrounding("[", "]")
        val port = port(server?.opt("port") ?: s.opt("port"))
        if (!Regex("[A-Za-z0-9._:-]{1,255}").matches(address)) reject("неверный адрес сервера")
        val stream = o.optJSONObject("streamSettings") ?: JSONObject()
        val auth = stream.optJSONObject("hysteriaSettings")?.optString("auth").orEmpty()
        if (auth.isBlank()) reject("нет пароля Hysteria2")
        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
        if (bool(tls, "allowInsecure") || bool(tls, "insecure")) reject("небезопасный TLS (allowInsecure)")
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val query = mutableListOf("sni=" + enc(tls.optString("serverName").ifBlank { address }))
        tls.optJSONArray("alpn")?.let { a -> val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }; if (l.isNotEmpty()) query += "alpn=" + enc(l.joinToString(",")) }
        val host = if (address.contains(':')) "[$address]" else address
        val name = remarks.ifBlank { o.optString("tag").ifBlank { address } }
        val p = ExtraProtocols.parse("hy2://${enc(auth)}@$host:$port?${query.joinToString("&")}#${enc(name)}")
        return p.copy(id = sha(remarks + "|" + o.toString()))
    }
}
