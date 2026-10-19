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
 * - logo: `profile-logo` (Prizrak-Box, Hammer), aliases `x-logo-url` (Hammer), `profile-icon`, `logo-url`, and the body short form
 *   `#logo:`. Happ, v2RayTun, INCY, Hiddify, Remnawave and Marzban document no logo header (Happ shows provider logos only for a
 *   registered `providerid`, from its own servers). Only https, see [logoUrl]. Without a logo header the app may try the provider's
 *   web page icon, see [webIcon].
 * Only https:// and tg:// links are kept. These URLs can identify the user: they are stored with the subscription and never logged.
 */
data class ProviderLinks(val support: String = "", val web: String = "", val announce: String = "", val logo: String = "",
    /** The provider's announcement text (`announce` header or `#announce:` body line), sanitized, see [announceText]. Never logged. */
    val announceText: String = "") {
    /** No link buttons (the logo and the announcement are not buttons). */
    val isEmpty get() = support.isEmpty() && web.isEmpty() && announce.isEmpty()
    val stored get() = !isEmpty || logo.isNotEmpty() || announceText.isNotEmpty()
    fun json(): JSONObject = JSONObject().apply { if (support.isNotEmpty()) put("support", support); if (web.isNotEmpty()) put("web", web); if (announce.isNotEmpty()) put("announce", announce); if (logo.isNotEmpty()) put("logo", logo)
        if (announceText.isNotEmpty()) put("announceText", announceText) }

    companion object {
        const val SUPPORT = "support-url"
        const val WEB = "profile-web-page-url"
        const val ANNOUNCE = "announce-url"
        /** Announcement text (Happ, v2RayTun, INCY, Remnawave): plain text or `base64:`. */
        const val ANNOUNCE_TEXT = "announce"
        const val MAX_ANNOUNCE = 500
        const val LOGO = "profile-logo"
        const val LOGO_ALIAS = "profile-icon"
        /** Every logo header / body name, in priority order. */
        val LOGO_NAMES = listOf(LOGO, "x-logo-url", LOGO_ALIAS, "logo-url", "logo")
        private const val MAX_LENGTH = 2048

        /** [header] looks up a response header (case-insensitively); body lines are used for headers that are missing. */
        fun parse(header: (String) -> String?, body: String = ""): ProviderLinks {
            val lines = bodyValues(body)
            fun value(name: String) = (header(name)?.let(::link) ?: lines[name]?.let(::link)).orEmpty()
            val logo = (LOGO_NAMES.firstNotNullOfOrNull { name -> header(name)?.let(::logoUrl) } ?: LOGO_NAMES.firstNotNullOfOrNull { name -> lines[name]?.let(::logoUrl) }).orEmpty()
            val text = (header(ANNOUNCE_TEXT)?.let { announceText(it, fromHeader = true) }?.takeIf { it.isNotEmpty() } ?: lines[ANNOUNCE_TEXT]?.let { announceText(it) }).orEmpty()
            return ProviderLinks(value(SUPPORT), value(WEB), value(ANNOUNCE), logo, text)
        }

        /**
         * An announcement as shown to the user: `base64:` decoded, a UTF-8 header value read as Latin-1 by the HTTP stack repaired,
         * control characters dropped (line breaks kept, at most two in a row), surrounding spaces trimmed, at most [MAX_ANNOUNCE] characters.
         */
        fun announceText(raw: String, fromHeader: Boolean = false): String {
            var v = raw.trim().removeSurrounding("\"").trim()
            if (v.startsWith("base64:", ignoreCase = true)) v = decode(v.substring(7)) ?: return ""
            else if (fromHeader && v.all { it.code < 256 } && v.any { it.code >= 128 }) {
                val bytes = v.toByteArray(Charsets.ISO_8859_1)
                val utf8 = Charsets.UTF_8.newDecoder().runCatching { decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                if (utf8 != null) v = utf8
            }
            val clean = v.replace("\r\n", "\n").replace('\r', '\n').filter { it == '\n' || !it.isISOControl() }
                .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
                .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            return if (clean.length <= MAX_ANNOUNCE) clean else clean.take(MAX_ANNOUNCE - 1).trimEnd() + "…"
        }

        fun from(j: JSONObject?): ProviderLinks = if (j == null) ProviderLinks() else
            ProviderLinks(j.optString("support").let { safe(it).orEmpty() }, j.optString("web").let { safe(it).orEmpty() }, j.optString("announce").let { safe(it).orEmpty() },
                j.optString("logo").let { logoUrl(it).orEmpty() }, announceText(j.optString("announceText")))

        /** A header value as a link: quotes trimmed, `base64:` decoded, then checked with [safe]. */
        fun link(raw: String): String? {
            var v = raw.trim().removeSurrounding("\"").trim()
            if (v.startsWith("base64:", ignoreCase = true)) v = decode(v.substring(7)) ?: return null
            return safe(v.trim())
        }

        /** A logo header value: like [link], but https only (the image is downloaded by the app). */
        fun logoUrl(raw: String): String? = link(raw)?.takeIf { it.startsWith("https://", ignoreCase = true) }

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

        /**
         * The provider's web page icon as a logo fallback: `https://<host of profile-web-page-url>/apple-touch-icon.png` (a 180 px icon
         * most sites have). Only for an https web page that is not Telegram; null otherwise.
         */
        fun webIcon(web: String): String? {
            if (web.isEmpty() || telegram(web)) return null
            val u = runCatching { URI(web) }.getOrNull() ?: return null
            if (!u.scheme.equals("https", true) || u.host.isNullOrBlank() || u.rawUserInfo != null) return null
            return "https://" + u.host + (if (u.port > 0 && u.port != 443) ":" + u.port else "") + "/apple-touch-icon.png"
        }

        /** Names of the `#name: value` body lines (lowercase), for the developer diagnostic. Values are never returned. */
        fun bodyNames(body: String): List<String> = bodyLines(body).mapNotNull { line ->
            val i = line.indexOf(':'); if (i > 1) line.substring(1, i).trim().lowercase().takeIf { n -> n.isNotEmpty() && n.length <= 64 && n.all { it.isLetterOrDigit() || it == '-' || it == '_' } } else null
        }.distinct()

        /** The first lines starting with `#`, from the body as sent or, when it is base64 (the usual v2ray subscription), decoded. */
        private fun bodyLines(body: String): List<String> {
            fun hashes(text: String) = text.lineSequence().take(64).map { it.trim() }.filter { it.startsWith("#") }.toList()
            val plain = hashes(body)
            if (plain.isNotEmpty() || body.length > 4 * 1024 * 1024) return plain
            val compact = body.trim().replace("\n", "").replace("\r", "")
            if (compact.isEmpty() || !compact.all { it.isLetterOrDigit() || it in "+/=-_" }) return plain
            return decode(compact)?.let(::hashes).orEmpty()
        }

        /** `#name: value` lines among the first lines of the body (Happ / v2RayTun / Hammer "body headers"), also inside a base64 body. */
        private fun bodyValues(body: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            bodyLines(body).asSequence().take(64).map { it.trim() }.filter { it.startsWith("#") }.forEach { line ->
                val i = line.indexOf(':')
                if (i > 1) {
                    val name = line.substring(1, i).trim().lowercase()
                    if ((name in setOf(SUPPORT, WEB, ANNOUNCE, ANNOUNCE_TEXT) || name in LOGO_NAMES) && name !in out) out[name] = line.substring(i + 1).trim()
                }
            }
            return out
        }
    }
}
