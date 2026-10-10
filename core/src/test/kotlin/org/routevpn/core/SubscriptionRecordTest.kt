package org.routevpn.core

import kotlin.test.*

class SubscriptionRecordTest {
    private val profiles = listOf(Subscriptions.parseLink("hy2://secret@example.com:443"), Subscriptions.parseLink("trojan://secret@other.example.com:443"))
    @Test fun previousSubscriptionMigratesWithoutLosingSecretsOrSelection() {
        val original = SubscriptionRecord("https://sub.example.com/secret", profiles, profiles[1].id)
        val oldJson = original.json().apply { listOf("wifiSelected", "mobileSelected", "providerName", "expires", "usedBytes", "totalBytes", "updatedAt").forEach { remove(it) } }
        val loaded = SubscriptionRecord.from(oldJson)
        assertEquals(original, loaded)
        assertEquals(profiles[1].id, loaded.selection("wifi"))
        assertEquals(profiles[1].id, loaded.selection("mobile"))
    }
    @Test fun selectionsAreIndependentAndMissingProfileFallsBack() {
        val original = SubscriptionRecord("https://sub.example.com/secret", profiles, profiles[0].id,
            wifiSelected = profiles[0].id, mobileSelected = profiles[1].id, providerName = "Example", expires = 1700000000, usedBytes = 300, totalBytes = 1000)
        val loaded = SubscriptionRecord.from(original.json())
        assertEquals(original, loaded)
        assertNotEquals(loaded.selection("wifi"), loaded.selection("mobile"))
        val invalid = original.json().put("mobileSelected", "removed")
        assertEquals(profiles[0].id, SubscriptionRecord.from(invalid).selection("mobile"))
        assertEquals(profiles, loaded.profiles)
    }
}
