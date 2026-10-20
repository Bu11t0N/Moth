package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class SubscriptionBookTest {
    private val a = listOf(Subscriptions.parseLink("hy2://secret@a.example.com:443#A1"), Subscriptions.parseLink("trojan://secret@a2.example.com:443#A2"))
    private val b = listOf(Subscriptions.parseLink("trojan://secret@b.example.com:443#B1"), a[1])

    @Test fun onlyTheSubscriptionWithTheServerInUseIsLocked() {
        val book = SubscriptionBook(listOf(SubscriptionSource("sa", "A", "https://a.example.com/s", a), SubscriptionSource("sb", "B", "https://b.example.com/s", listOf(b[0]))), a[0].id)
        assertTrue(book.inUse("sa", a[0].id)); assertFalse(book.inUse("sb", a[0].id))
        assertFalse(book.inUse("sa", "")); assertFalse(book.inUse("missing", a[0].id))
        // Deleting the other one keeps the selection on the server in use.
        val next = book.remove("sb")
        assertEquals(a[0].id, next.selected); assertEquals(listOf("sa"), next.sources.map { it.id })
        // A server present in both: both subscriptions are locked.
        val shared = book.upsert(SubscriptionSource("sb", "B", "https://b.example.com/s", b))
        assertTrue(shared.inUse("sa", a[1].id)); assertTrue(shared.inUse("sb", a[1].id))
    }

    @Test fun legacySubscriptionMigratesWithoutDataLoss() {
        val old = SubscriptionRecord("https://sub.example.com/secret", a, a[0].id, wifiSelected = a[0].id, mobileSelected = a[1].id,
            providerName = "Provider", expires = 1_900_000_000, usedBytes = 300, totalBytes = 1000, updatedAt = 1234)
        val book = SubscriptionBook.from(old.json())
        assertEquals(1, book.sources.size)
        val s = book.sources[0]
        assertEquals(SubscriptionBook.MIGRATED_ID, s.id)
        assertEquals(old.url, s.url); assertEquals(a, s.profiles); assertEquals("Provider", s.providerName)
        assertEquals(1_900_000_000, s.expires); assertEquals(300, s.usedBytes); assertEquals(1000, s.totalBytes); assertEquals(1234, s.updatedAt)
        assertEquals(old, book.record())
        // Saved again in the new format and read back: nothing changes.
        val again = SubscriptionBook.from(JSONObject(book.json().toString()))
        assertEquals(book, again)
        // The new file still carries the old top-level fields for an older app version.
        assertEquals(old, SubscriptionRecord.from(book.json()))
    }

    @Test fun legacyLocalServersBecomeTheLocalEntry() {
        val old = SubscriptionRecord(SubscriptionRecord.LOCAL_URL, a, a[1].id, providerName = "My servers")
        val book = SubscriptionBook.from(old.json())
        assertEquals(SubscriptionSource.LOCAL_ID, book.sources[0].id); assertTrue(book.sources[0].local)
        assertEquals("My servers", book.sources[0].title()); assertEquals(a[1].id, book.record()!!.selected)
    }

    @Test fun severalSubscriptionsKeepTheirOwnInfoAndShareSelection() {
        var book = SubscriptionBook(listOf(SubscriptionSource("one", "Home", "https://one.example/s", a, expires = 100, totalBytes = 10)), a[1].id)
        book = book.upsert(SubscriptionSource("two", "", "https://two.example/s", b, providerName = "Two VPN", expires = 200))
        assertEquals(listOf(a[0], a[1], b[0]), book.profiles) // duplicate server listed once
        assertEquals("one", book.sourceOf(a[1].id)?.id); assertEquals("two", book.sourceOf(b[0].id)?.id)
        assertEquals("Two VPN", book.source("two")!!.title()); assertEquals("Home", book.source("one")!!.title())
        assertEquals(a[1].id, book.record()!!.selected)
        assertEquals("", book.record()!!.url) // several subscriptions: no single link
        val roundTrip = SubscriptionBook.from(JSONObject(book.json().toString()))
        assertEquals(book, roundTrip)
        assertEquals(100, roundTrip.source("one")!!.expires); assertEquals(200, roundTrip.source("two")!!.expires)
        // Refreshing one subscription replaces it in place.
        val refreshed = book.upsert(book.source("one")!!.copy(profiles = listOf(a[0]), updatedAt = 5))
        assertEquals(listOf("one", "two"), refreshed.sources.map { it.id })
        assertEquals(a[1].id, refreshed.record()!!.selected) // still exists via "two"
        // Removing the subscription with the selected server falls back to an existing one.
        val removed = book.remove("one").withSelection(book.record()!!.copy(mobileSelected = "gone"))
        assertEquals(b.map { it.id }, removed.profiles.map { it.id })
        assertEquals(a[1].id, removed.record()!!.selected)
        assertEquals(a[1].id, removed.record()!!.mobileSelected)
        assertNull(book.remove("one").remove("two").record())
        assertEquals("New", book.rename("two", "  New ").source("two")!!.name)
    }

    @Test fun groupsPutFavoritesFirstThenEachSubscription() {
        val book = SubscriptionBook(listOf(SubscriptionSource("one", "", "https://one.example/s", a), SubscriptionSource("two", "", "https://two.example/s", b)))
        val ordered = listOf(b[0], a[1], a[0])
        val groups = ServerGroups.of(book, ordered, setOf(a[0].id))
        assertEquals(listOf(null, "one", "two"), groups.map { it.sourceId })
        assertEquals(listOf(a[0]), groups[0].profiles)
        assertEquals(listOf(a[1]), groups[1].profiles)
        assertEquals(listOf(b[0]), groups[2].profiles)
        assertEquals(listOf("one"), ServerGroups.of(book, listOf(a[0]), emptySet()).map { it.sourceId })
        assertTrue(SubscriptionSource.validName("Дом")); assertFalse(SubscriptionSource.validName("a\nb")); assertFalse(SubscriptionSource.validName("x".repeat(81)))
    }
}
