package org.mothvpn.core

/** Removes anything that could identify a server or a credential before text reaches the developer log. */
object LogRedactor {
    private val rules = listOf(
        Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"'<>]+") to "<url>",
        Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b") to "<id>",
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b(?::\\d{1,5})?") to "<ip>",
        Regex("(?i)\\[?(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}\\]?") to "<ip6>",
        Regex("(?i)\\b(?:[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?\\.)+[a-z]{2,}\\b(?::\\d{1,5})?") to "<host>",
        Regex("\\b[A-Za-z0-9+/_=-]{24,}\\b") to "<secret>"
    )
    fun redact(text: String?): String {
        var result = text ?: return ""
        for ((regex, replacement) in rules) result = regex.replace(result, replacement)
        return result.replace('\n', ' ').replace('\r', ' ').take(600)
    }
}
