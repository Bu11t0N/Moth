package org.mothvpn.core

import kotlin.test.*

class UserAgentsTest {
    private val v = "0.7.2"
    private fun id(ua: String) = UserAgents.choice(ua, v).id

    @Test fun presetsMapToThemselves() {
        for (p in UserAgents.presets(v)) {
            assertEquals(UserAgents.Choice(p.id), UserAgents.choice(p.value, v), p.label)
            assertEquals(p.label, UserAgents.title(p.value, v))
            assertTrue(UserAgents.valid(p.value), p.label)
            assertTrue(ClientSettings(userAgent = p.value).validate().userAgent == p.value)
        }
        assertEquals(listOf("Moth", "v2rayNG", "Hiddify", "sing-box", "INCY"), UserAgents.presets(v).map { it.label })
        // The exact strings of the latest client releases (Oct 2026).
        assertEquals(listOf("Moth/0.7.2", "v2rayNG/2.3.11", "HiddifyNext/4.1.1 (android) like ClashMeta v2ray sing-box",
            "SFA (sing-box 1.14.3; language en_US)", "INCY/3.7.9/android"), UserAgents.presets(v).map { it.value })
    }

    @Test fun savedValuesMapToPresetsAcrossVersions() {
        assertEquals(UserAgents.MOTH, id("Moth")); assertEquals(UserAgents.MOTH, id("Moth/0.6.0")); assertEquals(UserAgents.MOTH, id(ClientSettings().withAppVersion(v).userAgent))
        assertEquals("v2rayng", id("v2rayNG/1.8.5"))
        assertEquals("hiddify", id("HiddifyNext/2.5.7 (android) like ClashMeta v2ray sing-box"))
        assertEquals("hiddify", id("HiddifyNextX/4.0.0 (windows) like ClashMeta v2ray sing-box"))
        assertEquals("singbox", id("SFA (sing-box 1.11.0; language ru_RU)"))
        assertEquals("incy", id("INCY/1.0.0/ios")); assertEquals("incy", id("INCY/2.5.6/Android")) // the earlier preset
        assertEquals("singbox", id("SFA (sing-box 1.15.0-alpha.12; language en_US)"))
    }

    @Test fun anythingElseIsCustomPrefilled() {
        for (ua in listOf("clash-verge/1.0", "Mozilla/5.0 (Linux; Android 14)", "v2rayNG", "moth/1.0", "Happ/1.2.0", "MothX/1"))
            assertEquals(UserAgents.Choice(UserAgents.CUSTOM, ua), UserAgents.choice(ua, v), ua)
        assertEquals("clash-verge/1.0", UserAgents.title("clash-verge/1.0", v))
    }

    @Test fun customValidation() {
        assertFalse(UserAgents.valid("")); assertFalse(UserAgents.valid("   ")); assertFalse(UserAgents.valid("x".repeat(161))); assertFalse(UserAgents.valid("Клиент/1"))
        assertTrue(UserAgents.valid("x".repeat(160))); assertTrue(UserAgents.valid("My Client/1.0 (test)"))
    }
}
