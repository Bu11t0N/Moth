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

    @Test fun logoFromProfileLogoHeaderHttpsOnlyAndPersisted() {
        assertEquals("https://cdn.example.com/logo.png", ProviderLinks.parse({ if (it == "profile-logo") b64("https://cdn.example.com/logo.png") else null }).logo)
        assertEquals("https://cdn.example.com/i.png", ProviderLinks.parse({ null }, "#profile-icon: https://cdn.example.com/i.png\nvless://x").logo)
        assertEquals("", ProviderLinks.parse({ if (it == "profile-logo") "http://cdn.example.com/logo.png" else null }).logo)
        assertEquals("", ProviderLinks.parse({ if (it == "profile-logo") "tg://resolve?domain=x" else null }).logo)
        val links = ProviderLinks(logo = "https://cdn.example.com/logo.png")
        assertTrue(links.isEmpty); assertTrue(links.stored)
        assertEquals(links, SubscriptionSource.from(SubscriptionSource("id2", "", "https://s.example.com/x", emptyList(), links = links).json()).links)
    }

    @Test fun logoAliasesHeaderBeatsBodyAndBase64Body() {
        assertEquals("https://c.example.com/x.png", ProviderLinks.parse({ if (it == "x-logo-url") "https://c.example.com/x.png" else null }).logo)
        // A header wins over a body line, whatever the alias.
        assertEquals("https://h.example.com/a.png", ProviderLinks.parse({ if (it == "logo-url") "https://h.example.com/a.png" else null }, "#profile-logo: https://b.example.com/b.png").logo)
        assertEquals("https://b.example.com/s.png", ProviderLinks.parse({ null }, "#logo: https://b.example.com/s.png\nvless://x").logo)
        val body = java.util.Base64.getMimeEncoder().encodeToString("#profile-logo: https://b.example.com/l.png\n#support-url: https://t.me/s\nvless://u@h:443#n\n".toByteArray())
        val links = ProviderLinks.parse({ null }, body)
        assertEquals("https://b.example.com/l.png", links.logo); assertEquals("https://t.me/s", links.support)
        assertEquals(listOf("profile-logo", "support-url"), ProviderLinks.bodyNames(body))
    }

    @Test fun webIconFallbackOnlyForHttpsNonTelegramPages() {
        assertEquals("https://panel.example.com/apple-touch-icon.png", ProviderLinks.webIcon("https://panel.example.com/sub/abc123"))
        assertEquals("https://panel.example.com:8443/apple-touch-icon.png", ProviderLinks.webIcon("https://panel.example.com:8443/u"))
        assertEquals(null, ProviderLinks.webIcon("https://t.me/provider"))
        assertEquals(null, ProviderLinks.webIcon("tg://resolve?domain=x"))
        assertEquals(null, ProviderLinks.webIcon(""))
    }

    @Test fun announceTextPlainBase64BodySanitizedAndPersisted() {
        val ru = "Техработы в воскресенье"
        // Plain UTF-8 in a header arrives as Latin-1 from HttpURLConnection: repaired.
        val latin1 = String(ru.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals(ru, ProviderLinks.parse({ if (it == "announce") latin1 else null }).announceText)
        assertEquals(ru, ProviderLinks.parse({ if (it == "announce") "base64:" + java.util.Base64.getEncoder().encodeToString(ru.toByteArray()) else null }).announceText)
        assertEquals("Hello there", ProviderLinks.parse({ null }, "#announce: Hello   there\nvless://x").announceText)
        assertEquals("a\n\nb", ProviderLinks.announceText("a\u0007\r\n\n\n\nb\u202E"))
        val long = ProviderLinks.announceText("x".repeat(2000))
        assertEquals(ProviderLinks.MAX_ANNOUNCE, long.length); assertTrue(long.endsWith("…"))
        val links = ProviderLinks(announceText = ru)
        assertTrue(links.stored); assertTrue(links.isEmpty)
        assertEquals(links, SubscriptionSource.from(SubscriptionSource("id3", "", "https://s.example.com/x", emptyList(), links = links).json()).links)
    }

    @Test fun telegramAvatarPageAndPhoto() {
        assertEquals("https://t.me/dendi_support_bot", ProviderLinks.telegramPage(ProviderLinks(support = "https://t.me/dendi_support_bot?start=1", web = "https://example.com")))
        assertEquals("https://t.me/news_chan", ProviderLinks.telegramPage(ProviderLinks(web = "https://sub.example.com", announce = "https://telegram.me/s/news_chan/42")))
        assertEquals("abc_user", ProviderLinks.telegramUsername("tg://resolve?domain=abc_user"))
        for (bad in listOf("https://t.me/+AbCdEf123", "https://t.me/joinchat/xyz", "https://t.me/c/123/4", "https://t.me/share/url?url=x", "https://evil.example/t.me/abcde", "ftp://t.me/abcde", "https://t.me/"))
            assertNull(bad, ProviderLinks.telegramUsername(bad))
        assertNull(ProviderLinks.telegramPage(ProviderLinks(support = "https://example.com/help")))
        val page = """<html><head><meta property="og:title" content="Dendi"><meta property="og:image" content="https://cdn4.telesco.pe/file/abc.jpg?x=1&amp;y=2"></head>
            <body><img class="tgme_page_photo_image" src="https://cdn4.telesco.pe/file/photo.jpg"></body></html>"""
        assertEquals("https://cdn4.telesco.pe/file/photo.jpg", ProviderLinks.telegramPhoto(page))
        assertEquals("https://cdn4.telesco.pe/file/abc.jpg?x=1&y=2", ProviderLinks.telegramPhoto(page.replace("tgme_page_photo_image", "other")))
        assertNull(ProviderLinks.telegramPhoto("<meta property='og:image' content='https://telegram.org/img/t_logo.png'>"))
        assertNull(ProviderLinks.telegramPhoto("<meta property=\"og:image\" content=\"http://cdn.example/a.jpg\">"))
        assertNull(ProviderLinks.telegramPhoto("<html></html>"))
    }
}
