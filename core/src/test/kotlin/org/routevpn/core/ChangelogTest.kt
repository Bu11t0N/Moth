package org.routevpn.core

import java.io.File
import kotlin.test.*

class ChangelogTest {
    @Test fun parsesSectionsAndBullets() {
        val s = Changelog.parse("# Changelog\n\nintro\n\n## 0.2\n\n- a\n- b\n  more\n\n## 0.1\n- c\n")
        assertEquals(listOf("0.2", "0.1"), s.map { it.title })
        assertEquals(listOf("a", "b more"), s[0].items)
        assertEquals(listOf("c"), s[1].items)
    }
    @Test fun repositoryFilesParseAndMatch() {
        val en = Changelog.parse(File("../CHANGELOG.md").readText())
        val ru = Changelog.parse(File("../CHANGELOG.ru.md").readText())
        assertEquals(listOf("0.4.2", "0.4.1", "0.4.0", "0.3.0-alpha.1", "0.2.0-alpha.1", "0.1.0-alpha.1"), en.map { it.title.substringBefore(' ') })
        assertEquals(en.map { it.title.substringBefore(' ') }, ru.map { it.title.substringBefore(' ') })
        assertEquals(en.map { it.items.size }, ru.map { it.items.size })
        assertTrue(en.all { it.items.isNotEmpty() })
    }
}
