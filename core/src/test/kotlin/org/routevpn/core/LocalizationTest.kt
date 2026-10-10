package org.routevpn.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Checks the resource contract without requiring an emulator or real subscription. */
class LocalizationTest {
    private fun strings(folder: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../app/src/main/res/$folder/strings.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.removeSurrounding("\"")
        }
    }
    @Test fun translationsHaveMatchingKeysAndFormatArguments() {
        val english = strings("values")
        val russian = strings("values-ru")
        assertEquals(english.keys, russian.keys)
        val placeholders = Regex("%(?:[0-9]+\\$)?(?:\\.[0-9]+)?[sdf]")
        english.forEach { (key, value) ->
            assertEquals(placeholders.findAll(value).map { it.value }.toList(),
                placeholders.findAll(russian.getValue(key)).map { it.value }.toList(), key)
            assertTrue(value.isNotBlank(), key)
            assertTrue(russian.getValue(key).isNotBlank(), key)
        }
        assertEquals("Moth", english["app_name"])
        assertEquals("Мотылёк", russian["app_name"])
        assertEquals("Check cancelled", english["msg_check_cancelled_46"])
        assertEquals("Update subscription", english["msg_update_subscription_48"])
    }
    @Test fun formattedMessagesAcceptTheRuntimeArguments() {
        for (folder in listOf("values", "values-ru")) {
            val resources = strings(folder)
            assertTrue(resources.getValue("msg_host_saved_profiles_size_servers_17").format("example.com", 12).contains("12"))
            assertTrue(resources.getValue("msg_r_latencyms_ms_r_method_63").format(42, "HTTPS").contains("HTTPS"))
            assertTrue(resources.getValue("msg_profile_name_1f_mbit_s_2f_mib_130").format("Example", 8.0, 1.0).contains("Example"))
        }
    }
}
