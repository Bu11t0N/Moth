package org.mothvpn.core

import org.json.JSONObject
import java.net.URI

/**
 * Links a provider sends with the subscription, shown as buttons on the subscription card. Real-world headers
 * (Remnawave, Marzban, Happ, v2RayTun, Hiddify):
 * - `support-url`: support chat, usually a Telegram bot or group (https://t.me/...);
 * - `profile-web-page-url`: the provider's subscription / account page (Marzban and Remnawave send the user's own page);
 * - `announce-url`: the link behind the provider's announcement, usually a news channel.
 * Values may be sent as `base64:<...>` (Remnawave's rwEncodeBase64) and also as `#name: value` lines at the top of a plain-text body.
 * Only https:// and tg:// links are kept. These URLs can identify the user: they are stored with the subscription and never logged.
 */
data class ProviderLinks(val support: String = "", val web: String = "", val announce: String = "") {
    val isEmpty get() = support.isEmpty() && web.isEmpty() && announce.isEmpty()
    fun json(): JSONObject = JSONObject().apply { if (support.isNotEmpty()) put("support", support); if (web.isNotEmpty()) put("web", web); if (announce.isNotEmpty()) put("announce", announce) }

    companion object {
        const val SUPPORT = "support-url"
        const val WEB = "profile-web-page-url"
        const val ANNOUNCE = "announce-url"
        private const val MAX_LENGTH = 2048

        /** [header] looks up a response header (case-insensitively); body lines are used for headers that are missing. */
        fun parse(header: (String) -> String?, body: String = ""): ProviderLinks {
            val lines = bodyValues(body)
            fun value(name: String) = (header(name)?.let(::link) ?: lines[name]?.let(::link)).orEmpty()
            return ProviderLinks(value(SUPPORT), value(WEB), value(ANNOUNCE))
        }

        fun from(j: JSONObject?): ProviderLinks = if (j == null) ProviderLinks() else
            ProviderLinks(j.optString("support").let { safe(it).orEmpty() }, j.optString("web").let { safe(it).orEmpty() }, j.optString("announce").let { safe(it).orEmpty() })

        /** A header value as a link: quotes trimmed, `base64:` decoded, then checked with [safe]. */
        fun link(raw: String): String? {
            var v = raw.trim().removeSurrounding("\"").trim()
            if (v.startsWith("base64:", ignoreCase = true)) v = decode(v.substring(7)) ?: return null
            return safe(v.trim())
        }

        /** https with a host and no user info, or tg://; at most 2 KB, no spaces or control characters. */
        fun safe(url: String): String? {
            if (url.isEmpty() || url.length > MAX_LENGTH || url.any { it.isWhitespace() || it.isISOControl() }) return null
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            return when (u.scheme?.lowercase()) {
                "https" -> url.takeIf { !u.host.isNullOrBlank() && u.rawUserInfo == null }
                "tg" -> url.takeIf { !u.rawSchemeSpecificPart.isNullOrBlank() }
                else -> null
            }
        }

        /** Telegram links (t.me, telegram.me, telegram.dog, tg://) get the Telegram-style button. */
        fun telegram(url: String): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            if (u.scheme.equals("tg", true)) return true
            val host = u.host?.lowercase()?.removePrefix("www.") ?: return false
            return host in setOf("t.me", "telegram.me", "telegram.dog")
        }

        private fun decode(text: String): String? {
            val t = text.trim()
            return listOf(java.util.Base64.getDecoder(), java.util.Base64.getUrlDecoder(), java.util.Base64.getMimeDecoder()).firstNotNullOfOrNull { d ->
                runCatching { String(d.decode(t), Charsets.UTF_8) }.getOrNull()
            }
        }

        /** `#name: value` lines among the first lines of a plain-text body (Happ / v2RayTun "body headers"). */
        private fun bodyValues(body: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            body.lineSequence().take(64).map { it.trim() }.filter { it.startsWith("#") }.forEach { line ->
                val i = line.indexOf(':')
                if (i > 1) {
                    val name = line.substring(1, i).trim().lowercase()
                    if (name in setOf(SUPPORT, WEB, ANNOUNCE) && name !in out) out[name] = line.substring(i + 1).trim()
                }
            }
            return out
        }
    }
}
