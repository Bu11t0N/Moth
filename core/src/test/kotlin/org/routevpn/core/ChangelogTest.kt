package org.routevpn.core

import java.io.File
import kotlin.test.*

class ChangelogTest {
    @Test fun parsesSectionsGroupsAndBullets() {
        val s = Changelog.parse("# Changelog\n\nintro\n\n## 0.2.0\n\n- loose\n\n### New\n\n- a\n- b\n  more\n\n### Fixes\n- c\n\n## 0.1.0\n- d\n")
        assertEquals(listOf("0.2.0", "0.1.0"), s.map { it.title })
        assertEquals(listOf(null, "New", "Fixes"), s[0].groups.map { it.title })
        assertEquals(listOf("a", "b more"), s[0].groups[1].items)
        assertEquals(listOf("loose", "a", "b more", "c"), s[0].items)
        assertEquals(listOf("d"), s[1].items)
    }
    @Test fun repositoryFilesParseAndMatch() {
        val en = Changelog.parse(File("../CHANGELOG.md").readText())
        val ru = Changelog.parse(File("../CHANGELOG.ru.md").readText())
        val versions = listOf("0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0", "0.3.0", "0.2.0", "0.1.0")
        assertEquals(versions, en.map { it.title })
        assertEquals(versions, ru.map { it.title })
        assertEquals(en.map { it.groups.map { g -> g.items.size } }, ru.map { it.groups.map { g -> g.items.size } })
        assertTrue(en.all { s -> s.groups.all { it.title != null && it.items.isNotEmpty() } })
        assertEquals(listOf("Subscriptions"), en[0].groups.map { it.title })
        assertEquals(listOf("Подписки"), ru[0].groups.map { it.title })
        assertEquals(listOf("Fixes"), en[1].groups.map { it.title })
        assertEquals(listOf("Исправления"), ru[1].groups.map { it.title })
        assertEquals(3, en[1].items.size)
        assertEquals(listOf("Logs and diagnostics", "Server ping", "Interface", "Fixes"), en[2].groups.map { it.title })
        assertEquals(listOf("Журнал и диагностика", "Пинг серверов", "Интерфейс", "Исправления"), ru[2].groups.map { it.title })
    }
}
