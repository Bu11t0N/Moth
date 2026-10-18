package org.mothvpn.core

/**
 * Keyword server filter (new design). Keywords match server names case-insensitively as substrings, at display time,
 * so the filter keeps working after a subscription update renames or replaces the servers.
 */
object ServerFilter {
    const val EXCLUDE = "exclude"
    const val ONLY = "only"
    val modes = setOf(EXCLUDE, ONLY)
    const val MAX_KEYWORDS = 50
    const val MAX_LENGTH = 64

    /** A keyword as stored: trimmed, inner whitespace collapsed; null when empty or too long. */
    fun clean(keyword: String): String? = keyword.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** [keywords] plus [keyword], without case-insensitive duplicates; unchanged when the keyword is invalid or the list is full. */
    fun add(keywords: List<String>, keyword: String): List<String> {
        val k = clean(keyword) ?: return keywords
        if (keywords.size >= MAX_KEYWORDS || keywords.any { it.equals(k, ignoreCase = true) }) return keywords
        return keywords + k
    }

    fun valid(keywords: List<String>) = keywords.size <= MAX_KEYWORDS && keywords.all { clean(it) == it }

    fun normalize(keywords: List<String>): List<String> =
        keywords.fold(emptyList()) { acc, k -> add(acc, k) }

    /** True when a server called [name] is shown. No keywords: every server is shown, in both modes. */
    fun shows(name: String, keywords: List<String>, mode: String): Boolean {
        if (keywords.isEmpty()) return true
        val hit = keywords.any { name.contains(it, ignoreCase = true) }
        return if (mode == ONLY) hit else !hit
    }
}
