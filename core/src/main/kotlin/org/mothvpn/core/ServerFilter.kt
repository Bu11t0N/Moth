package org.mothvpn.core

/**
 * Keyword server filter. Keywords match server names case-insensitively as substrings, at display time,
 * so the filter keeps working after a subscription update renames or replaces the servers.
 */
object ServerFilter {
    /**
     * One network's filter: keywords (as stored, see [clean]) and [EXCLUDE] / [ONLY]. [disabled] are keywords kept in the list but
     * switched off (1.0.4); they do not filter. Filters saved before 1.0.4 have no such list, so every keyword stays on.
     */
    data class Rule(val keywords: List<String> = emptyList(), val mode: String = EXCLUDE, val disabled: List<String> = emptyList()) {
        /** The keywords that filter now (switched on). */
        val active: List<String> get() = keywords.filter { k -> disabled.none { it.equals(k, ignoreCase = true) } }
        fun enabled(keyword: String) = disabled.none { it.equals(keyword, ignoreCase = true) }
        /** Switches [keyword] on or off; it stays in the list either way. */
        fun toggle(keyword: String, on: Boolean): Rule =
            copy(disabled = if (on) disabled.filterNot { it.equals(keyword, ignoreCase = true) } else if (enabled(keyword)) disabled + keyword else disabled)
        /** Deletes [keyword] from the list (and from the switched-off ones). */
        fun remove(keyword: String): Rule = copy(keywords = keywords - keyword, disabled = disabled.filterNot { it.equals(keyword, ignoreCase = true) })
        fun valid() = ServerFilter.valid(keywords) && mode in modes && disabled.all { d -> keywords.any { it.equals(d, ignoreCase = true) } }
        fun shows(name: String) = shows(name, active, mode)
        fun json(): org.json.JSONObject = org.json.JSONObject().put("keywords", org.json.JSONArray(keywords)).put("mode", mode).put("disabled", org.json.JSONArray(disabled))
        companion object {
            private fun list(a: org.json.JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
            private fun read(a: org.json.JSONArray?, mode: String, off: org.json.JSONArray? = null): Rule {
                val keywords = normalize(list(a))
                val disabled = normalize(list(off)).filter { d -> keywords.any { it.equals(d, ignoreCase = true) } }
                return Rule(keywords, mode.takeIf { it in modes } ?: EXCLUDE, disabled)
            }
            fun from(j: org.json.JSONObject) = read(j.optJSONArray("keywords"), j.optString("mode", EXCLUDE), j.optJSONArray("disabled"))
            /** The single filter saved by 0.7.0 beta 4, or an empty rule. */
            fun legacy(settings: org.json.JSONObject) = read(settings.optJSONArray("serverFilter"), settings.optString("serverFilterMode", EXCLUDE))
        }
    }

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

    /**
     * Servers auto-select and failover may pick on network [kind]: the same ones that network's list shows. Without switched-on keywords every server is a candidate. Empty when the filter hides everything.
     */
    fun pool(profiles: List<Profile>, settings: ClientSettings, kind: String): List<Profile> {
        val rule = settings.filterFor(kind)
        return if (rule.active.isEmpty()) profiles else profiles.filter { rule.shows(it.name) }
    }
}
