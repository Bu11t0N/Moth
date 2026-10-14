package org.mothvpn.core

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
        val enAll = Changelog.parse(File("../CHANGELOG.md").readText())
        val ruAll = Changelog.parse(File("../CHANGELOG.ru.md").readText())
        assertEquals("0.5.4", enAll[0].title); assertEquals("0.5.4", ruAll[0].title)
        assertEquals(listOf("Changes"), enAll[0].groups.map { it.title }); assertEquals(listOf("Изменения"), ruAll[0].groups.map { it.title })
        assertEquals(enAll[0].items.size, ruAll[0].items.size)
        val en = enAll.drop(1)
        val ru = ruAll.drop(1)
        val versions = listOf("0.5.3", "0.5.2", "0.5.1", "0.5.0", "0.4.7", "0.4.6", "0.4.5", "0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0", "0.3.0", "0.2.0", "0.1.0")
        assertEquals(versions, en.map { it.title })
        assertEquals(versions, ru.map { it.title })
        assertEquals(en.map { it.groups.map { g -> g.items.size } }, ru.map { it.groups.map { g -> g.items.size } })
        assertTrue(en.all { s -> s.groups.all { it.title != null && it.items.isNotEmpty() } })
        assertEquals(listOf("Changes"), en[0].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[0].groups.map { it.title })
        assertEquals(listOf("Changes"), en[1].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[1].groups.map { it.title })
        assertEquals(listOf("Changes"), en[2].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[2].groups.map { it.title })
        assertEquals(listOf("Changes"), en[3].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[3].groups.map { it.title })
        assertEquals(listOf("Changes"), en[4].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[4].groups.map { it.title })
        assertEquals(listOf("Changes"), en[5].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[5].groups.map { it.title })
        assertEquals(listOf("Changes"), en[6].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[6].groups.map { it.title })
        assertEquals(listOf("Subscriptions"), en[7].groups.map { it.title })
        assertEquals(listOf("Подписки"), ru[7].groups.map { it.title })
        assertEquals(listOf("Fixes"), en[8].groups.map { it.title })
        assertEquals(listOf("Исправления"), ru[8].groups.map { it.title })
        assertEquals(listOf("Logs and diagnostics", "Server ping", "Interface", "Fixes"), en[9].groups.map { it.title })
        assertEquals(listOf("Журнал и диагностика", "Пинг серверов", "Интерфейс", "Исправления"), ru[9].groups.map { it.title })
    }
}
