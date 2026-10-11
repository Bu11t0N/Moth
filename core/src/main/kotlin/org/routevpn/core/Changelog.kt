package org.routevpn.core

data class ChangelogSection(val title: String, val items: List<String>)

/** Parses CHANGELOG.md: `## <version>` headings followed by `- ` bullets. Everything else is ignored. */
object Changelog {
    fun parse(text: String): List<ChangelogSection> {
        val sections = mutableListOf<ChangelogSection>()
        var title: String? = null
        val items = mutableListOf<String>()
        fun flush() { title?.let { sections += ChangelogSection(it, items.toList()) }; items.clear() }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> { flush(); title = line.removePrefix("## ").trim() }
                title != null && line.startsWith("- ") -> items += line.removePrefix("- ").trim()
                title != null && items.isNotEmpty() && line.startsWith("  ") && line.isNotBlank() -> items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        flush()
        return sections
    }
}
