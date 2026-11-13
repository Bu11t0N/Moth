package org.mothvpn.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Checks the resource contract without requiring an emulator or real subscription. */
class LocalizationTest {
    private val placeholders = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[sdf]")

    private fun strings(folder: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../app/src/main/res/$folder/strings.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.removeSurrounding("\"")
        }
    }

    /** Placeholder lists per quantity for every <plurals> in a strings.xml. */
    private fun plurals(folder: String): Map<String, Map<String, String>> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../app/src/main/res/$folder/strings.xml")).getElementsByTagName("plurals")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            val items = (0 until node.childNodes.length).mapNotNull { i ->
                val item = node.childNodes.item(i)
                if (item.nodeName != "item") null
                else item.attributes.getNamedItem("quantity").nodeValue to item.textContent.removeSurrounding("\"")
            }.toMap()
            name to items
        }
    }

    @Test fun translationsHaveMatchingKeysAndFormatArguments() {
        val english = strings("values")
        val russian = strings("values-ru")
        assertEquals(english.keys, russian.keys)
        english.forEach { (key, value) ->
            assertEquals(placeholders.findAll(value).map { it.value }.toList(),
                placeholders.findAll(russian.getValue(key)).map { it.value }.toList(), key)
            assertTrue(value.isNotBlank(), key)
            assertTrue(russian.getValue(key).isNotBlank(), key)
        }
        val enPlurals = plurals("values")
        val ruPlurals = plurals("values-ru")
        assertEquals(enPlurals.keys, ruPlurals.keys)
        enPlurals.forEach { (key, enItems) ->
            val ruItems = ruPlurals.getValue(key)
            // Every quantity that exists in both languages must use the same placeholders; every
            // Russian-only form (few/many) must match English "other" (or "one" if that is the only form).
            val enDefault = enItems["other"] ?: enItems.values.first()
            val enDefaultPh = placeholders.findAll(enDefault).map { it.value }.toList()
            ruItems.forEach { (quantity, text) ->
                val expected = enItems[quantity]?.let { placeholders.findAll(it).map { p -> p.value }.toList() } ?: enDefaultPh
                assertEquals(expected, placeholders.findAll(text).map { it.value }.toList(), "$key/$quantity")
            }
        }
        assertEquals("Moth", english["app_name"])
        assertEquals("Мотылёк", russian["app_name"])
        assertEquals("Check cancelled", english["msg_check_cancelled_46"])
        assertEquals("Update subscription", english["msg_update_subscription_48"])
    }

    @Test fun probeValidationErrorsHaveEnglishAndRussianResources() {
        val mappings = File("../app/src/main/java/org/mothvpn/android/AppStrings.kt").readText()
        val english = strings("values")
        val russian = strings("values-ru")
        val cases = listOf(
            Triple(ClientSettings(probeParallel = 17), "msg_probe_parallel_range", "Parallel probes: from 1 to 16"),
            Triple(ClientSettings(probeTimeoutMs = 499), "msg_probe_timeout_range", "Ping timeout: from 500 to 30000 ms")
        )
        cases.forEach { (settings, key, translation) ->
            val error = assertFailsWith<IllegalArgumentException> { settings.validate() }
            assertEquals(error.message, russian.getValue(key))
            assertEquals(translation, english.getValue(key))
            assertTrue(mappings.contains("\"${error.message}\" to R.string.$key"), key)
        }
    }

    @Test fun formattedMessagesAcceptTheRuntimeArguments() {
        for (folder in listOf("values", "values-ru")) {
            val resources = strings(folder)
            assertTrue(resources.getValue("sub_servers_count").format(12).contains("12"))
        }
    }

    /**
     * A formatted string used through [tr]/getString/stringResource without arguments is shown to the
     * user as a raw "%1$d" (or similar). Every formatted resource must have at least one such call
     * that passes arguments — including when the id is chosen by an if/when expression.
     */
    @Test fun formattedStringsArePassedArgumentsAtCallSites() {
        val english = strings("values")
        val formatted = english.filter { placeholders.containsMatchIn(it.value) }.keys
        val sources = File("../app/src/main").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()
        val files = sources.map { it.readText() }
        val formatFns = setOf("tr", "trPlural", "getString", "stringResource", "getQuantityString")
        formatted.forEach { key ->
            val argCounts = mutableListOf<Int>()
            val needle = Regex("""R\.string\.$key(?![A-Za-z0-9_])""")
            files.forEach { text ->
                needle.findAll(text).forEach { m ->
                    enclosingFormatCall(text, m.range.first, formatFns)?.let { argCounts += it }
                }
            }
            val pluralNeedle = Regex("""R\.plurals\.$key(?![A-Za-z0-9_])""")
            files.forEach { text ->
                pluralNeedle.findAll(text).forEach { m ->
                    enclosingFormatCall(text, m.range.first, formatFns)?.let { argCounts += it }
                }
            }
            assertTrue(argCounts.isNotEmpty(), "formatted string $key is never passed to tr/getString/stringResource")
            assertTrue(argCounts.all { it >= 2 }, "formatted string $key is used without arguments and would show a raw placeholder: $argCounts")
        }
    }

    /** Argument count of the nearest tr/getString-style call around [pos], or null if the id is only a reference. */
    private fun enclosingFormatCall(text: String, pos: Int, formatFns: Set<String>): Int? {
        var depth = 0
        var i = pos - 1
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        val before = text.substring(0, i).trimEnd()
                        val fn = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(before)?.groupValues?.get(1) ?: return null
                        if (fn !in formatFns) return null
                        val close = matchingClose(text, i) ?: return null
                        return 1 + topLevelCommas(text.substring(i + 1, close))
                    }
                    depth--
                }
            }
            i--
        }
        return null
    }

    private fun matchingClose(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun topLevelCommas(s: String): Int {
        var depth = 0
        var n = 0
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) n++
                '"', '\'' -> {
                    val q = c
                    i++
                    while (i < s.length && s[i] != q) {
                        if (s[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return n
    }
}
