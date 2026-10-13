package org.mothvpn.core

import java.net.URI
import kotlin.test.*

class UserAgentTest {
    @Test fun defaultUserAgentFollowsTheInstalledVersion() {
        listOf("Moth", "Moth/0.5.0", "Moth/0.5.0-beta.3", "Moth/0.4.7-beta.6", "Moth/0.5.1").forEach { saved ->
            assertEquals("Moth/0.5.2", ClientSettings(userAgent = saved).withAppVersion("0.5.2").userAgent, saved)
            assertEquals("Moth/0.5.2-beta.1", ClientSettings(userAgent = saved).withAppVersion("0.5.2-beta.1").userAgent, saved)
        }
        listOf("MyClient/1.0", "Moth custom", "v2rayNG/1.8.5", "Moth/abc").forEach { custom ->
            assertEquals(custom, ClientSettings(userAgent = custom).withAppVersion("0.5.2").userAgent, custom)
        }
        val stale = ClientSettings.from(ClientSettings(userAgent = "Moth/0.5.0").json()).withAppVersion("0.5.2")
        val headers = SubscriptionHeaders.create(stale, URI("https://sub.example/s"), URI("https://sub.example/s"), "14", "Pixel")
        assertEquals("Moth/0.5.2", headers["User-Agent"])
    }
}
