package org.mothvpn.core

/** A `###` category (title may be null for bullets placed directly under the version) with its bullets. */
data class ChangelogGroup(val title: String?, val items: List<String>)
data class ChangelogSection(val title: String, val groups: List<ChangelogGroup>) {
    val items: List<String> get() = groups.flatMap { it.items }
}

/** Parses CHANGELOG.md: `## <version>` headings, optional `### <category>` sub-headings and `- ` bullets. Everything else is ignored. */
object Changelog {
    fun parse(text: String): List<ChangelogSection> {
        val sections = mutableListOf<ChangelogSection>()
        var title: String? = null
        val groups = mutableListOf<ChangelogGroup>()
        var groupTitle: String? = null
        val items = mutableListOf<String>()
        fun flushGroup() { if (items.isNotEmpty()) groups += ChangelogGroup(groupTitle, items.toList()); items.clear() }
        fun flushSection() { flushGroup(); title?.let { sections += ChangelogSection(it, groups.toList()) }; groups.clear() }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") && title != null -> { flushGroup(); groupTitle = line.removePrefix("### ").trim() }
                line.startsWith("## ") -> { flushSection(); title = line.removePrefix("## ").trim(); groupTitle = null }
                title != null && line.startsWith("- ") -> items += line.removePrefix("- ").trim()
                title != null && items.isNotEmpty() && line.startsWith("  ") && line.isNotBlank() -> items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        flushSection()
        return sections
    }
}
