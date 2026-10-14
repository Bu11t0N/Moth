package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class BackupTest {
    private val a = listOf(Subscriptions.parseLink("hy2://secret@a.example.com:443#A1"), Subscriptions.parseLink("trojan://secret@a2.example.com:443#A2"))
    private val local = listOf(Subscriptions.parseLink("ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@l.example:8388#L"))
    private val book = SubscriptionBook(listOf(
        SubscriptionSource("one", "Home", "https://one.example/s", a, providerName = "P", expires = 1_900_000_000, usedBytes = 5, totalBytes = 10, updatedAt = 7),
        SubscriptionSource(SubscriptionSource.LOCAL_ID, "My servers", SubscriptionRecord.LOCAL_URL, local)), a[0].id, a[0].id, local[0].id)
    private val settings = ClientSettings(theme = "dark", favorites = listOf(a[1].id), killSwitch = true, subUpdateHours = 6,
        routingRules = listOf(RoutingRule("domain_suffix", "example.com", "direct")), splitMode = "bypass", splitApps = listOf("com.example.app"))
    private val content = Backup.Content(settings, book, "0.5.3", 1234)

    @Test fun plainRoundTripKeepsEverything() {
        val text = Backup.export(content, null)
        assertFalse(Backup.encrypted(text))
        val j = JSONObject(text); assertEquals("moth-backup", j.getString("format")); assertEquals(1, j.getInt("version"))
        val back = Backup.read(text)
        assertEquals(content, back)
        assertEquals(local[0].id, back.book.record()!!.mobileSelected)
        assertEquals(Backup.Summary(1, 3, 1, 1, 1), Backup.summary(back))
    }

    @Test fun encryptedRoundTripAndWrongPassword() {
        val text = Backup.export(content, "correct horse")
        assertTrue(Backup.encrypted(text))
        assertFalse(text.contains("secret") || text.contains("one.example") || text.contains("example.com"), "no plaintext secrets")
        assertEquals(content, Backup.read(text, "correct horse"))
        assertFailsWith<Backup.WrongPassword> { Backup.read(text, "wrong") }
        assertFailsWith<Backup.PasswordRequired> { Backup.read(text, null) }
        // Tampered ciphertext is rejected as well.
        val j = JSONObject(text); val data = java.util.Base64.getDecoder().decode(j.getString("data")); data[0] = (data[0].toInt() xor 1).toByte()
        assertFailsWith<Backup.WrongPassword> { Backup.read(j.put("data", java.util.Base64.getEncoder().encodeToString(data)).toString(), "correct horse") }
        assertNotEquals(text, Backup.export(content, "correct horse"), "fresh salt and IV each time")
    }

    @Test fun rejectsForeignNewerAndInvalidFiles() {
        assertFailsWith<IllegalArgumentException> { Backup.read("not json") }
        assertFailsWith<IllegalArgumentException> { Backup.read("{\"format\":\"other\",\"version\":1}") }
        assertFailsWith<IllegalArgumentException> { Backup.read(JSONObject(Backup.export(content, null)).put("version", 2).toString()) }
        val bad = JSONObject(Backup.export(content, null)).apply { getJSONObject("payload").getJSONObject("settings").put("mtu", 10) }
        assertFailsWith<IllegalArgumentException> { Backup.read(bad.toString()) }
    }

    @Test fun olderPayloadsMigrate() {
        // Settings written by an older version (missing newer keys) and a 0.5.1 single-subscription record.
        val oldSettings = ClientSettings(hwid = "abcdefghij12").json().apply { listOf("routingRules", "favorites", "expiryWarning", "killSwitch").forEach { remove(it) } }
        val oldRecord = SubscriptionRecord("https://old.example/s", a, a[1].id, providerName = "Old").json()
        val text = JSONObject().put("format", "moth-backup").put("version", 1).put("encrypted", false)
            .put("payload", JSONObject().put("settings", oldSettings).put("subscriptions", oldRecord)).toString()
        val c = Backup.read(text)
        assertEquals(emptyList(), c.settings.routingRules); assertEquals("abcdefghij12", c.settings.hwid)
        assertEquals(SubscriptionBook.MIGRATED_ID, c.book.sources.single().id); assertEquals(a[1].id, c.book.record()!!.selected)
        val empty = JSONObject().put("format", "moth-backup").put("version", 1).put("payload", JSONObject().put("settings", oldSettings)).toString()
        assertTrue(Backup.read(empty).book.sources.isEmpty())
    }
}
