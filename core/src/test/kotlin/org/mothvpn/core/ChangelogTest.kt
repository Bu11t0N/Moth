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
        for (i in 0..6) {
            assertEquals(listOf("0.8.0", "0.7.6", "0.7.5", "0.7.4", "0.7.3", "0.7.2", "0.7.1")[i], enAll[i].title); assertEquals(enAll[i].title, ruAll[i].title)
            assertEquals(listOf("Changes"), enAll[i].groups.map { it.title }); assertEquals(listOf("Изменения"), ruAll[i].groups.map { it.title })
            assertEquals(enAll[i].items.size, ruAll[i].items.size)
        }
        val enRest = enAll.drop(7); val ruRest = ruAll.drop(7)
        restParseAndMatch(enRest, ruRest)
    }
    private fun restParseAndMatch(enAll: List<ChangelogSection>, ruAll: List<ChangelogSection>) {
        assertEquals("0.7.0", enAll[0].title); assertEquals("0.7.0", ruAll[0].title)
        assertEquals(listOf("Design", "Servers and subscriptions", "Connection and settings", "Fixes and updates"), enAll[0].groups.map { it.title })
        assertEquals(listOf("Дизайн", "Серверы и подписки", "Подключение и настройки", "Исправления и обновления"), ruAll[0].groups.map { it.title })
        assertEquals(enAll[0].groups.map { it.items.size }, ruAll[0].groups.map { it.items.size })
        assertEquals("0.6.5", enAll[1].title); assertEquals("0.6.5", ruAll[1].title)
        assertEquals(listOf("Changes"), enAll[1].groups.map { it.title }); assertEquals(listOf("Изменения"), ruAll[1].groups.map { it.title })
        assertEquals(enAll[1].items.size, ruAll[1].items.size)
        assertEquals("0.6.4", enAll[2].title); assertEquals("0.6.4", ruAll[2].title)
        assertEquals(listOf("Changes"), enAll[2].groups.map { it.title }); assertEquals(listOf("Изменения"), ruAll[2].groups.map { it.title })
        assertEquals(enAll[2].items.size, ruAll[2].items.size)
        assertEquals("0.6.3", enAll[3].title); assertEquals("0.6.3", ruAll[3].title)
        assertEquals(enAll[3].items.size, ruAll[3].items.size)
        val en = enAll.drop(4)
        val ru = ruAll.drop(4)
        val versions = listOf("0.6.2", "0.6.1", "0.6.0", "0.5.3", "0.5.2", "0.5.1", "0.5.0", "0.4.7", "0.4.6", "0.4.5", "0.4.4", "0.4.3", "0.4.2", "0.4.1", "0.4.0", "0.3.0", "0.2.0", "0.1.0")
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
        assertEquals(listOf("Changes"), en[7].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[7].groups.map { it.title })
        assertEquals(listOf("Changes"), en[8].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[8].groups.map { it.title })
        assertEquals(listOf("Changes"), en[9].groups.map { it.title })
        assertEquals(listOf("Изменения"), ru[9].groups.map { it.title })
        assertEquals(listOf("Subscriptions"), en[10].groups.map { it.title })
        assertEquals(listOf("Подписки"), ru[10].groups.map { it.title })
        assertEquals(listOf("Fixes"), en[11].groups.map { it.title })
        assertEquals(listOf("Исправления"), ru[11].groups.map { it.title })
        assertEquals(listOf("Logs and diagnostics", "Server ping", "Interface", "Fixes"), en[12].groups.map { it.title })
        assertEquals(listOf("Журнал и диагностика", "Пинг серверов", "Интерфейс", "Исправления"), ru[12].groups.map { it.title })
    }
}
