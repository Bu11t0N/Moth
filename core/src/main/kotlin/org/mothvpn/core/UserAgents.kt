package org.mothvpn.core

/**
 * Subscription User-Agent choices: Moth (automatic "Moth/<version>"), a few popular clients and the user's own value.
 * Preset strings come from each client's source code or official docs:
 * - v2rayNG: HttpUtil.getUrlContentWithUserAgent, "v2rayNG/${BuildConfig.VERSION_NAME}" (release 2.3.11);
 * - Hiddify: AppInfoEntity.userAgent, "HiddifyNext/$version ($operatingSystem) like ClashMeta v2ray sing-box" (v4.1.1, android);
 * - sing-box (SFA): HTTPClient.userAgent, "SFA (sing-box <libbox version>; language <locale>)" (1.14.3);
 * - INCY: docs.incy.cc subscription format, "INCY/<version>/<platform>".
 * A saved value is shown as the preset whose pattern it matches (any version), otherwise as the user's own value. Never logged.
 */
object UserAgents {
    const val MOTH = "moth"
    const val CUSTOM = "custom"
    private const val VER = "[0-9][0-9A-Za-z.+-]*"

    data class Preset(val id: String, val label: String, val value: String, val pattern: Regex)

    fun presets(version: String): List<Preset> = listOf(
        Preset(MOTH, "Moth", "Moth/$version", Regex("Moth(/$VER)?")),
        Preset("v2rayng", "v2rayNG", "v2rayNG/2.3.11", Regex("v2rayNG/$VER")),
        Preset("hiddify", "Hiddify", "HiddifyNext/4.1.1 (android) like ClashMeta v2ray sing-box", Regex("HiddifyNextX?/$VER \\([a-z]+\\) like ClashMeta v2ray sing-box")),
        Preset("singbox", "sing-box", "SFA (sing-box 1.14.3; language en_US)", Regex("SFA \\(sing-box $VER; language [A-Za-z0-9_]+\\)")),
        Preset("incy", "INCY", "INCY/2.5.6/Android", Regex("INCY/$VER/[A-Za-z]+")))

    /** What the picker shows for [userAgent]: a preset id with an empty custom field, or [CUSTOM] prefilled with the value. */
    data class Choice(val id: String, val custom: String = "")

    fun choice(userAgent: String, version: String): Choice =
        presets(version).firstOrNull { it.pattern.matches(userAgent) }?.let { Choice(it.id) } ?: Choice(CUSTOM, userAgent)

    /** Row text: the preset name, or the user's own value. */
    fun title(userAgent: String, version: String): String = presets(version).firstOrNull { it.pattern.matches(userAgent) }?.label ?: userAgent

    /** Same rule as [ClientSettings.validate]: printable ASCII, 1–160 characters. */
    fun valid(value: String) = value.isNotBlank() && value.length <= 160 && value.all { it.code in 32..126 }
}
