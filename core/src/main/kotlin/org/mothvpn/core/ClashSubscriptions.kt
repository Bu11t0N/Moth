package org.mothvpn.core

import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

object ClashSubscriptions {
    private fun enc(value: Any?) = URLEncoder.encode(value?.toString().orEmpty(), "UTF-8").replace("+", "%20")
    private const val MAX_PROVIDERS = 10
    fun looksLike(text: String) = listOf("proxies:", "proxy-providers:").any { text.startsWith(it) || text.contains("\n$it") }
    /**
     * [fetch] downloads a proxy-provider ("type: http", HTTPS URL only, at most 10); a provider may hold Clash YAML or a link list.
     * Without [fetch] providers are skipped.
     */
    fun parse(text: String, fetch: ((String) -> String)? = null): ImportResult {
        require(text.length <= 2 * 1024 * 1024)
        val options = LoaderOptions().apply { codePointLimit = 2 * 1024 * 1024; maxAliasesForCollections = 20; nestingDepthLimit = 30; isAllowDuplicateKeys = false }
        val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *> ?: error("Нужна Clash-подписка с разделом proxies")
        val proxies = root["proxies"] as? List<*> ?: emptyList<Any>()
        val providers = root["proxy-providers"] as? Map<*, *>
        require(proxies.isNotEmpty() || !providers.isNullOrEmpty()) { "Нужна Clash-подписка с разделом proxies" }
        require(proxies.size <= 2000) { "Слишком много серверов" }
        var rejected = 0
        val reasons = mutableListOf<String>()
        val converted = proxies.mapIndexedNotNull { index, entry ->
            runCatching { convert(entry as Map<*, *>) }.getOrElse { e ->
                rejected++
                if (reasons.size < 100) reasons += "proxy ${index + 1} (${((entry as? Map<*, *>)?.get("type")?.toString() ?: "?").take(12).lowercase()}): ${rejectReason(e)}"
                null
            }
        }.toMutableList()
        providers?.entries?.forEachIndexed { index, (_, value) ->
            val label = "provider ${index + 1}"
            val p = value as? Map<*, *>
            val url = p?.get("url")?.toString()?.trim().orEmpty()
            when {
                index >= MAX_PROVIDERS -> { rejected++; if (reasons.size < 100) reasons += "$label: too many providers" }
                fetch == null -> { if (reasons.size < 100) reasons += "$label: skipped" }
                p?.get("type")?.toString() != "http" || ImportInput.classify(url) !is ImportInput.Kind.Subscription -> { rejected++; if (reasons.size < 100) reasons += "$label: only HTTPS providers are supported" }
                else -> try {
                    val sub = Subscriptions.parse(fetch(url))
                    require(converted.size + sub.profiles.size <= 2000) { "Слишком много серверов" }
                    converted += sub.profiles; rejected += sub.rejected
                    sub.reasons.take(20).forEach { if (reasons.size < 100) reasons += "$label: $it" }
                } catch (e: Exception) { rejected++; if (reasons.size < 100) reasons += "$label: ${rejectReason(e)}" }
            }
        }
        val (profiles, duplicates) = dedupe(converted, "proxy")
        require(profiles.isNotEmpty()) { "Clash: нет совместимых серверов; проверь протоколы и параметры TLS" }
        return ImportResult(profiles, rejected, reasons, duplicates, 0, converted.size)
    }
    private fun convert(p: Map<*, *>): Profile {
        fun value(key: String, default: String = "") = p[key]?.toString() ?: default
        require(value("skip-cert-verify") != "true") { "Небезопасный TLS" }
        val type = value("type").lowercase()
        require(p["dialer-proxy"] == null) { "Зависимый профиль не поддерживается" }
        require(p["plugin"] == null || (type == "ss" && value("plugin") == "shadow-tls")) { "Зависимый профиль не поддерживается" }
        val server = value("server").let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        val port = value("port").toInt(); require(port in 1..65535)
        val name = value("name", server)
        direct(type, p, value("server"), port, name)?.let { return it }
        val network = value("network", "tcp")
        val ws = p["ws-opts"] as? Map<*, *>
        val grpc = p["grpc-opts"] as? Map<*, *>
        val xhttp = p["xhttp-opts"] as? Map<*, *>
        val headers = ws?.get("headers") as? Map<*, *>
        require(headers == null || headers.keys.all { it.toString().equals("Host", true) }) { "Нестандартные WS headers" }
        require(ws?.get("max-early-data") == null && ws?.get("early-data-header-name") == null) { "WS early data пока не поддерживается" }
        val host = headers?.entries?.firstOrNull { it.key.toString().equals("Host", true) }?.value?.toString() ?: xhttp?.get("host")?.toString().orEmpty()
        val path = ws?.get("path")?.toString() ?: xhttp?.get("path")?.toString() ?: "/"
        val reality = p["reality-opts"] as? Map<*, *>
        val q = linkedMapOf("type" to network, "host" to host, "path" to path, "sni" to value("servername", value("sni", value("server"))))
        q["security"] = if (reality != null) "reality" else if (value("tls") == "true" || type in setOf("trojan", "hysteria", "hysteria2", "tuic")) "tls" else "none"
        reality?.let { q["pbk"] = it["public-key"]?.toString().orEmpty(); q["sid"] = it["short-id"]?.toString().orEmpty() }
        if (value("client-fingerprint").isNotEmpty()) q["fp"] = value("client-fingerprint")
        (p["alpn"] as? List<*>)?.let { q["alpn"] = it.joinToString(",") }
        grpc?.get("grpc-service-name")?.let { q["serviceName"] = it.toString() }
        if (value("flow").isNotBlank()) q["flow"] = value("flow")
        xhttp?.get("mode")?.let { q["mode"] = it.toString() }
        xhttp?.get("extra")?.let { q["extra"] = JSONObject(it as Map<*, *>).toString() }
        val user: String
        val scheme: String
        when (type) {
            "ss" -> {
                scheme = "ss"; user = enc("${value("cipher")}:${value("password")}")
                if (p["plugin"] != null) {
                    val opts = p["plugin-opts"] as? Map<*, *> ?: error("ShadowTLS: нет параметров")
                    val ss = Subscriptions.parseLink("ss://$user@$server:$port#${enc(name)}")
                    return ProxyProtocols.shadowTls(ss, value("server"), port, opts["version"]?.toString()?.toIntOrNull() ?: 2, opts["password"]?.toString().orEmpty(),
                        opts["host"]?.toString().orEmpty(), value("client-fingerprint"))
                }
            }
            "vless" -> { scheme = type; user = enc(value("uuid")) }
            "trojan" -> { scheme = type; user = enc(value("password")) }
            "hysteria2" -> {
                scheme = "hy2"; user = enc(value("password"))
                if (value("obfs").isNotBlank()) { q["obfs"] = value("obfs"); q["obfs-password"] = value("obfs-password") }
            }
            "hysteria" -> { scheme = type; user = enc(value("auth-str")); q["auth"] = value("auth-str"); q["upmbps"] = value("up"); q["downmbps"] = value("down"); if (value("obfs").isNotBlank()) q["obfs"] = value("obfs") }
            "tuic" -> {
                scheme = type; user = enc(value("uuid")) + ":" + enc(value("password"))
                q["congestion_control"] = value("congestion-controller", "cubic"); q["udp_relay_mode"] = value("udp-relay-mode", "native")
                require(p["token"] == null) { "TUIC v4 не поддерживается" }
            }
            "vmess" -> {
                val v = JSONObject().put("add", value("server")).put("port", port.toString()).put("id", value("uuid")).put("ps", name)
                    .put("aid", value("alterId", "0").toInt()).put("scy", value("cipher", "auto")).put("net", network)
                    .put("host", host).put("path", if (network == "grpc") q["serviceName"].orEmpty() else path)
                    .put("tls", if (value("tls") == "true") "tls" else "").put("sni", q["sni"])
                    .put("fp", q["fp"] ?: "").put("alpn", q["alpn"] ?: "").put("mode", q["mode"] ?: "auto")
                return Subscriptions.parseLink("vmess://" + Base64.getEncoder().encodeToString(v.toString().toByteArray()))
            }
            "anytls" -> error("AnyTLS пока не поддерживается")
            else -> error("Неподдерживаемый протокол")
        }
        return Subscriptions.parseLink("$scheme://$user@$server:$port?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" } + "#${enc(name)}")
    }
    /** SOCKS5, HTTP, WireGuard and SSH become sing-box outbounds directly; the id is a hash of the outbound (stable across refreshes). */
    private fun direct(type: String, p: Map<*, *>, server: String, port: Int, name: String): Profile? {
        fun v(key: String) = p[key]?.toString().orEmpty()
        fun list(key: String) = when (val x = p[key]) { is List<*> -> x.map { it.toString() }; null -> emptyList(); else -> listOf(x.toString()) }
        val made = when (type) {
            "socks5" -> { require(v("tls") != "true") { "SOCKS5 поверх TLS не поддерживается" }; ProxyProtocols.socks(server, port, v("username"), v("password"), name, "") }
            "http" -> ProxyProtocols.http(server, port, v("username"), v("password"), v("tls") == "true", v("sni").ifBlank { v("servername") }.ifBlank { null }, name, "")
            "wireguard" -> {
                require(p["amnezia-wg-option"] == null) { "AmneziaWG не поддерживается" }
                val reserved = when (val r = p["reserved"]) { is List<*> -> r.map { it.toString().toInt() }; null -> null
                    else -> java.util.Base64.getDecoder().decode(r.toString()).map { it.toInt() and 0xff } }
                ProxyProtocols.wireguard(server, port, v("private-key"), v("public-key"), v("pre-shared-key").ifBlank { null },
                    listOf(v("ip"), v("ipv6")).filter { it.isNotBlank() }, reserved, v("mtu").toIntOrNull(), name, "")
            }
            "ssh" -> {
                val key = v("private-key").ifBlank { null }
                require(key == null || key.contains("-----BEGIN")) { "SSH: ключ из файла не поддерживается" }
                ProxyProtocols.ssh(server, port, v("username"), v("password"), key, v("private-key-passphrase").ifBlank { null }, list("host-key"), name, "")
            }
            else -> return null
        }
        return made.copy(id = ProxyProtocols.sha("clash|" + made.outbound))
    }
}
