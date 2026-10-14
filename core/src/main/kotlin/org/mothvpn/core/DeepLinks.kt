package org.mothvpn.core

import java.net.URLDecoder

/**
 * Links that open Moth: moth://import-sub?url=&name=, moth://import?link=, v2rayng://install-sub, v2rayng://install-config,
 * happ://add/<url>, hiddify://import/<url>#name, sing-box://import-remote-profile?url=#name, and server links themselves
 * (vless://, vmess://, …). Nothing is imported without the user's confirmation; the parsed values are never logged.
 */
object DeepLinks {
    sealed class Action {
        /** A subscription to download (HTTPS) with an optional name. */
        data class Subscription(val url: String, val name: String?) : Action()
        /** One or more server links for "My servers". */
        data class Links(val text: String) : Action()
    }
    val serverSchemes = setOf("vless", "vmess", "trojan", "ss", "hy2", "hysteria2", "tuic", "socks", "socks5", "wireguard", "wg")
    val appSchemes = setOf("moth", "v2rayng", "happ", "hiddify", "sing-box")
    private fun decode(v: String) = runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)
    private fun query(raw: String) = raw.split('&').filter { it.isNotBlank() }.associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', "")) }

    fun parse(raw: String?): Action? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 * 1024 } ?: return null
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme in serverSchemes) return links(text)
        if (scheme !in appSchemes) return null
        val rest = text.substringAfter("://")
        val path = rest.substringBefore('?').substringBefore('#').trim('/').lowercase()
        val q = query(rest.substringAfter('?', "").substringBefore('#'))
        val fragment = rest.substringAfter('#', "").takeIf { rest.contains('#') }?.let(::decode)
        return when (scheme) {
            "moth" -> when (path) {
                "import-sub", "add-sub", "subscription" -> subscription(q["url"], q["name"])
                "import" -> q["link"]?.let(::any)
                else -> null
            }
            "v2rayng" -> when (path) {
                "install-sub" -> subscription(q["url"], q["name"] ?: fragment)
                "install-config" -> q["url"]?.let(::any)
                else -> null
            }
            // happ://add/<url>: everything after "add/" is the link (it may have its own query).
            "happ" -> if (rest.startsWith("add/", true)) any(rest.substring(4)) else null
            "hiddify" -> if (rest.startsWith("import/", true)) rest.substring(7).let { body ->
                val target = body.substringBefore('#')
                if (target.substringBefore("://", "").lowercase() in serverSchemes) any(body) else subscription(target, fragment)
            } else null
            "sing-box" -> if (path == "import-remote-profile") subscription(q["url"], fragment ?: q["name"]) else null
            else -> null
        }
    }
    /** A subscription URL, server links, or base64 of server links. */
    private fun any(value: String): Action? {
        val v = value.trim()
        return when (val kind = ImportInput.classify(v)) {
            is ImportInput.Kind.Subscription -> subscription(kind.url, null)
            is ImportInput.Kind.Links -> Action.Links(kind.text)
            ImportInput.Kind.Invalid -> runCatching { Subscriptions.unbase64(v) }.getOrNull()?.let { decoded ->
                (ImportInput.classify(decoded) as? ImportInput.Kind.Links)?.let { Action.Links(it.text) } }
        }
    }
    private fun links(text: String): Action? = (ImportInput.classify(text) as? ImportInput.Kind.Links)?.let { Action.Links(it.text) }
    private fun subscription(url: String?, name: String?): Action? {
        val u = url?.trim() ?: return null
        val kind = ImportInput.classify(u) as? ImportInput.Kind.Subscription ?: return null
        if (runCatching { java.net.URI(kind.url).rawUserInfo }.getOrNull() != null) return null
        val n = name?.replace('\n', ' ')?.trim()?.take(SubscriptionSource.MAX_NAME)?.takeIf { it.isNotEmpty() && SubscriptionSource.validName(it) }
        return Action.Subscription(kind.url, n)
    }
    /** Host shown in the confirmation dialog. */
    fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
}
