package org.mothvpn.core

/**
 * Plain-text routing rules: one rule per line, "<type>:<value> <action>", e.g. "suffix:example.com direct".
 * Types: domain, suffix, keyword, ip; actions: proxy, direct, block. Empty lines and lines starting with '#' are ignored.
 * A line without a type prefix ("example.com" or "example.com block") uses the default type; without an action, the default action.
 */
object RuleText {
    private val shortType = mapOf("domain" to CustomRouting.DOMAIN, "suffix" to CustomRouting.SUFFIX, "keyword" to CustomRouting.KEYWORD, "ip" to CustomRouting.CIDR)
    private val typeNames = shortType + CustomRouting.types.associateWith { it } + mapOf("domain-suffix" to CustomRouting.SUFFIX, "domain-keyword" to CustomRouting.KEYWORD,
        "cidr" to CustomRouting.CIDR, "ip-cidr" to CustomRouting.CIDR)
    private val shortName = shortType.entries.associate { (k, v) -> v to k }
    const val MAX_CHARS = 1_000_000

    fun line(rule: RoutingRule) = "${shortName.getValue(rule.type)}:${rule.value} ${rule.action}"
    fun format(rules: List<RoutingRule>): String = rules.joinToString("") { line(it) + "\n" }

    /** [invalidLines]: 1-based numbers of lines that could not be read. [rules]: the full list after the import. */
    data class Result(val rules: List<RoutingRule>, val added: Int, val invalidLines: List<Int>, val duplicates: Int, val overLimit: Int) {
        val invalid get() = invalidLines.size
    }

    /** Parses one line; null when it is invalid. */
    fun parseLine(raw: String, defaultType: String, defaultAction: String): RoutingRule? {
        val tokens = raw.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens.size > 2 || tokens[0].isEmpty()) return null
        val action = (tokens.getOrNull(1) ?: defaultAction).lowercase()
        if (action !in CustomRouting.actions) return null
        val first = tokens[0]
        val prefix = first.substringBefore(':', "").lowercase()
        val type = typeNames[prefix]
        if (type != null) return CustomRouting.normalize(type, first.substringAfter(':'))?.let { RoutingRule(type, it, action) }
        // Bare value: the default type; an IP address or network in a domain list is recognized as an IP rule.
        CustomRouting.normalize(defaultType, first)?.let { return RoutingRule(defaultType, it, action) }
        if (defaultType != CustomRouting.CIDR && (first.any { it == ':' } || first.first().isDigit()))
            CustomRouting.normalize(CustomRouting.CIDR, first)?.let { return RoutingRule(CustomRouting.CIDR, it, action) }
        return null
    }

    /** Appends the rules in [text] to [existing], skipping invalid lines, duplicates and anything beyond [CustomRouting.MAX_RULES]. */
    fun import(text: String, existing: List<RoutingRule>, defaultType: String = CustomRouting.SUFFIX, defaultAction: String = CustomRouting.DIRECT): Result {
        require(defaultType in CustomRouting.types && defaultAction in CustomRouting.actions)
        val out = existing.toMutableList()
        val seen = existing.map { it.type to it.value }.toHashSet()
        val invalid = mutableListOf<Int>(); var duplicates = 0; var over = 0; var added = 0
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { i, raw ->
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) return@forEachIndexed
            val rule = parseLine(l, defaultType, defaultAction) ?: run { invalid += i + 1; return@forEachIndexed }
            when {
                !seen.add(rule.type to rule.value) -> duplicates++
                out.size >= CustomRouting.MAX_RULES -> over++
                else -> { out += rule; added++ }
            }
        }
        return Result(out, added, invalid, duplicates, over)
    }
}
