package org.mothvpn.core

import java.io.File
import kotlin.test.*

class LanShareInstructionsTest {
    private fun resources(language: String) = File("../app/src/main/res/$language/strings.xml").readText()
    private fun steps(language: String) = Regex("""<string name="lan_howto">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .find(resources(language))!!.groupValues[1].replace("\\n", "\n").lines()

    @Test fun russianSecondStepCarriesHostAndPortAndNothingFollowsThird() {
        val lines = steps("values-ru")
        assertEquals(5, lines.size)
        assertEquals(listOf("Адрес хоста: адрес выше до двоеточия", "Порт: число после двоеточия", "3. Сохраните введенные настройки"), lines.drop(2))
        assertTrue(lines[0].startsWith("1. Включите точку доступа"))
        assertTrue(lines[1].startsWith("2. На другом устройстве"))
    }
    @Test fun englishInstructionsMatchTheShortThreeStepStructure() {
        val lines = steps("values")
        assertEquals(5, lines.size)
        assertEquals(listOf("Host address: the address above before the colon", "Port: the number after the colon", "3. Save the entered settings"), lines.drop(2))
    }
    @Test fun obsoleteProtocolLineRemovedFromBothLanguages() {
        listOf("values", "values-ru").forEach { assertFalse(resources(it).contains("name=\"lan_protocol\"")) }
    }
    @Test fun waitingStateAndNetworkTypeLinesRemoved() {
        listOf("values", "values-ru").forEach { assertFalse(resources(it).contains("name=\"lan_state_waiting\"")); assertFalse(resources(it).contains("name=\"lan_kind_")) }
    }
    @Test fun lanInfoHasNoParenthesesAndSaysDevice() {
        listOf("values", "values-ru").forEach { lang ->
            val info = Regex("""<string name="lan_info">(.*?)</string>""").find(resources(lang))!!.groupValues[1]
            assertFalse(info.contains("(") || info.contains(")"), lang)
        }
        assertTrue(resources("values-ru").contains("Другие устройства, подключённые к точке доступа этого устройства, могут им пользоваться"))
    }
    @Test fun noPhoneWordingInUserStrings() {
        assertFalse(Regex("телефон", RegexOption.IGNORE_CASE).containsMatchIn(resources("values-ru")))
        assertFalse(Regex("\\bphones?\\b", RegexOption.IGNORE_CASE).containsMatchIn(resources("values")))
    }
}
