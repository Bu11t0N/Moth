package org.mothvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLinksTest {
    private fun b64(s: String) = "base64:" + java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun readsRemnawaveAndMarzbanHeadersIncludingBase64() {
        val headers = mapOf("support-url" to "https://t.me/example_support_bot", "profile-web-page-url" to b64("https://sub.example.com/abc123"),
            "announce-url" to "\"https://t.me/example_news\"")
        val links = ProviderLinks.parse({ headers[it] })
        assertEquals(ProviderLinks("https://t.me/example_support_bot", "https://sub.example.com/abc123", "https://t.me/example_news"), links)
    }

    @Test fun bodyLinesFillMissingHeadersOnly() {
        val body = "#profile-title: Name\n#support-url: https://t.me/body_support\n#announce-url: ${b64("tg://resolve?domain=news")}\nvless://x@h:443#a"
        val links = ProviderLinks.parse({ if (it == "support-url") "https://help.example.com" else null }, body)
        assertEquals(ProviderLinks("https://help.example.com", "", "tg://resolve?domain=news"), links)
    }

    @Test fun rejectsUnsafeLinks() {
        listOf("http://example.com", "javascript:alert(1)", "https://user:pw@example.com/", "intent://x#Intent;end", "https://exa mple.com", "base64:!!!", "", "file:///sdcard/x")
            .forEach { assertNull(it, ProviderLinks.link(it)) }
    }

    @Test fun telegramDetection() {
        assertTrue(ProviderLinks.telegram("https://t.me/bot")); assertTrue(ProviderLinks.telegram("tg://resolve?domain=x"))
        assertTrue(ProviderLinks.telegram("https://www.telegram.me/x")); assertFalse(ProviderLinks.telegram("https://example.com/t.me"))
    }

    @Test fun linksPersistWithTheSubscriptionAndOldFilesHaveNone() {
        val links = ProviderLinks("https://t.me/s", "https://w.example.com/u", "")
        val source = SubscriptionSource("id1", "", "https://sub.example.com/x", emptyList(), links = links)
        assertEquals(links, SubscriptionSource.from(source.json()).links)
        assertEquals(ProviderLinks(), SubscriptionSource.from(source.copy(links = ProviderLinks()).json()).links)
        assertTrue(SubscriptionSource.from(source.json().apply { remove("providerLinks") }).links.isEmpty)
    }
}
