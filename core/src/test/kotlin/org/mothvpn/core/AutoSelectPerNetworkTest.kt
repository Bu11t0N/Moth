package org.mothvpn.core

import kotlin.test.*

class AutoSelectPerNetworkTest {
    @Test fun oldSingleSettingSeedsBoth() {
        for (on in listOf(true, false)) {
            val old = ClientSettings().json().apply { remove("autoSelectWifi"); remove("autoSelectMobile"); put("autoSelect", on) }
            val s = ClientSettings.from(old)
            assertEquals(on, s.autoSelectWifi); assertEquals(on, s.autoSelectMobile)
        }
        // Nothing saved at all: off on both.
        val none = ClientSettings.from(ClientSettings().json().apply { remove("autoSelectWifi"); remove("autoSelectMobile"); remove("autoSelect") })
        assertFalse(none.autoSelectWifi); assertFalse(none.autoSelectMobile)
    }

    @Test fun savedSeparatelyAndRoundTrips() {
        val s = ClientSettings(autoSelectWifi = true, autoSelectMobile = false)
        val back = ClientSettings.from(s.json())
        assertEquals(s, back)
        // A stale combined value never overrides the separate ones.
        assertEquals(s, ClientSettings.from(s.json().put("autoSelect", false)))
        // Backups carry the settings JSON, so both flags survive a backup.
        val restored = Backup.read(Backup.export(Backup.Content(s, SubscriptionBook(emptyList())), null)).settings
        assertTrue(restored.autoSelectWifi); assertFalse(restored.autoSelectMobile)
    }

    @Test fun theFlagOfTheNetworkInUseApplies() {
        val wifiOnly = ClientSettings(autoSelectWifi = true)
        assertTrue(wifiOnly.autoSelectFor("wifi")); assertFalse(wifiOnly.autoSelectFor("mobile"))
        assertTrue(wifiOnly.autoSelectFor("ethernet")) // anything but mobile counts as Wi-Fi, as for the server filter
        val mobileOnly = ClientSettings(autoSelectMobile = true)
        assertFalse(mobileOnly.autoSelectFor("wifi")); assertTrue(mobileOnly.autoSelectFor("mobile"))
        // Failover and the start plan follow it.
        assertTrue(Reselect.failoverDue(wifiOnly.autoSelectFor("wifi"), 3, 3))
        assertFalse(Reselect.failoverDue(wifiOnly.autoSelectFor("mobile"), 3, 3))
        assertEquals(Reselect.StartPlan.CONNECT, Reselect.startPlan(false, mobileOnly.autoSelectFor("wifi"), true, 5))
        assertEquals(Reselect.StartPlan.MEASURE_THEN_CONNECT, Reselect.startPlan(false, mobileOnly.autoSelectFor("mobile"), true, 5))
        // Switching networks: the new network's flag decides (Wi-Fi -> mobile with auto only on mobile: auto-select there).
        assertEquals(NetworkServerChoice.Decision.AutoSelect,
            NetworkServerChoice.decide("wifi", "mobile", "a", "b", setOf("a", "b"), mobileOnly.autoSelectFor("mobile"), false))
        assertEquals(NetworkServerChoice.Decision.Switch("b"),
            NetworkServerChoice.decide("mobile", "wifi", "a", "b", setOf("a", "b"), mobileOnly.autoSelectFor("wifi"), false))
    }

    @Test fun changingTheFlagDoesNotReconnect() {
        val a = ClientSettings()
        assertFalse(a.needsReconnect(a.copy(autoSelectWifi = true)))
        assertFalse(a.needsReconnect(a.copy(autoSelectMobile = true)))
    }
}
