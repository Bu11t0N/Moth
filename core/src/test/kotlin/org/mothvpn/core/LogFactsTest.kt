package org.mothvpn.core

import java.io.File
import kotlin.test.*

class LogFactsTest {
    @Test fun settingsChangesShowSafeValuesAndHideAddresses() {
        val a = ClientSettings(hwid = "aaaaaaaaaa")
        val b = a.copy(mtu = 1500, dns = "https://9.9.9.9/dns-query", hwid = "bbbbbbbbbb", updateChannel = "beta", userAgent = "X")
        val changes = LogFacts.settingsChanges(a, b)
        assertEquals(listOf("mtu=1400->1500", "dns changed", "hwid changed", "userAgent changed", "updateChannel=auto->beta"), changes)
        assertTrue(changes.none { it.contains("9.9.9.9") || it.contains("bbbb") })
        assertTrue(LogFacts.settingsChanges(a, a).isEmpty())
    }
    @Test fun hostTagIsStableAndRevealsNothing() {
        val tag = LogFacts.hostTag("https://sub.provider.example/path/secrettoken?x=1")
        assertEquals(8, tag.length)
        assertEquals(tag, LogFacts.hostTag("https://SUB.provider.example/other"))
        assertFalse(tag.contains("provider") || tag.contains("secret"))
        assertEquals("none", LogFacts.hostTag("not a url"))
    }
    @Test fun profileTagHasProtocolAndIdPrefixOnly() {
        assertEquals("vless#abcdef12", LogFacts.profileTag(Profile("abcdef1234567890", "Name", "vless", "{}")))
    }
    @Test fun loggingIsOffByDefault() {
        val source = File("../app/src/main/java/org/mothvpn/android/Logger.kt").readText()
        assertTrue(source.contains("""getString("level", "OFF")"""))
        assertTrue(source.contains("getOrDefault(LogLevel.OFF)"))
        assertEquals(1, Regex("""putString\("level"""").findAll(File("../app/src/main/java/org/mothvpn/android").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }).count())
    }
}
