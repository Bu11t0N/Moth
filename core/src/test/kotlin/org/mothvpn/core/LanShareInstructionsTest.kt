package org.mothvpn.core

import java.io.File
import kotlin.test.*

class LanShareInstructionsTest {
    private fun resources(language: String) = File("../app/src/main/res/$language/strings.xml").readText()
    private fun steps(language: String) = Regex("""<string name="lan_howto">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .find(resources(language))!!.groupValues[1].replace("\\n", "\n").lines()

    @Test fun russianThirdStepHasTwoExactLinesAndNothingFollowsFourth() {
        val lines = steps("values-ru")
        assertEquals(5, lines.size)
        assertEquals(listOf("3. Адрес хоста: адрес выше до двоеточия", "Порт: число после двоеточия", "4. Сохраните введенные настройки"), lines.drop(2))
        assertTrue(lines[0].startsWith("1. Включите точку доступа"))
        assertTrue(lines[1].startsWith("2. На другом устройстве"))
    }
    @Test fun englishInstructionsMatchTheShortFourStepStructure() {
        val lines = steps("values")
        assertEquals(5, lines.size)
        assertEquals(listOf("3. Host address: the address above before the colon", "Port: the number after the colon", "4. Save the entered settings"), lines.drop(2))
    }
    @Test fun obsoleteProtocolLineRemovedFromBothLanguages() {
        listOf("values", "values-ru").forEach { assertFalse(resources(it).contains("name=\"lan_protocol\"")) }
    }
}
