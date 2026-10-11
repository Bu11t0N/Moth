package org.mothvpn.core

import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

object ClashSubscriptions {
    private fun enc(value: Any?) = URLEncoder.encode(value?.toString().orEmpty(), "UTF-8").replace("+", "%20")
    fun parse(text: String): ImportResult {
        require(text.length <= 2 * 1024 * 1024)
        val options = LoaderOptions().apply { codePointLimit = 2 * 1024 * 1024; maxAliasesForCollections = 20; nestingDepthLimit = 30; isAllowDuplicateKeys = false }
        val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *> ?: error("Нужна Clash-подписка с разделом proxies")
        val proxies = root["proxies"] as? List<*> ?: error("Clash: нужен встроенный список proxies; proxy-providers пока не поддерживаются")
        require(proxies.size <= 2000) { "Слишком много серверов" }
        var rejected = 0
        val reasons = mutableListOf<String>()
        val profiles = proxies.mapIndexedNotNull { index, entry ->
            runCatching { convert(entry as Map<*, *>) }.getOrElse { e ->
                rejected++
                if (reasons.size < 100) reasons += "proxy ${index + 1} (${((entry as? Map<*, *>)?.get("type")?.toString() ?: "?").take(12).lowercase()}): ${rejectReason(e)}"
                null
            }
        }.distinctBy { it.id }
        require(profiles.isNotEmpty()) { "Clash: нет совместимых серверов; проверь протоколы и параметры TLS" }
        return ImportResult(profiles, rejected, reasons)
    }
    private fun convert(p: Map<*, *>): Profile {
        fun value(key: String, default: String = "") = p[key]?.toString() ?: default
        require(value("skip-cert-verify") != "true") { "Небезопасный TLS" }
        require(p["plugin"] == null && p["dialer-proxy"] == null) { "Зависимый профиль не поддерживается" }
        val type = value("type").lowercase()
        val server = value("server").let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
        val port = value("port").toInt(); require(port in 1..65535)
        val name = value("name", server)
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
            "ss" -> { scheme = "ss"; user = enc("${value("cipher")}:${value("password")}") }
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
            else -> error("Неподдерживаемый протокол")
        }
        return Subscriptions.parseLink("$scheme://$user@$server:$port?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" } + "#${enc(name)}")
    }
}
