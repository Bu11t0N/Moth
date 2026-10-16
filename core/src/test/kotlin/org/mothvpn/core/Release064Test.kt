package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

class Release064Test {
    private val original = Subscriptions.parseLink("trojan://old-password@old.example.com:443#Demo")
    private val source = SubscriptionSource("demo", "My subscription", "https://sub.example.com/list", listOf(original))
    private val book = SubscriptionBook(listOf(source), original.id)

    @Test fun editedServerSharesCurrentAddressAndPasswordAfterSaving() {
        val outbound = JSONObject(original.outbound).put("server", "new.example.com").put("password", "new-password").toString()
        val updated = ServerEditor.apply(book, original.id, outbound)
        val edited = SubscriptionBook.from(updated.json()).profiles.single()
        assertEquals(original.id, edited.id)
        val shared = Subscriptions.parseLink(assertNotNull(ShareLinks.of(edited)))
        assertEquals("new.example.com", JSONObject(shared.outbound).getString("server"))
        assertEquals("new-password", JSONObject(shared.outbound).getString("password"))
        assertEquals(original.link, ShareLinks.of(original))
    }

    @Test fun editedUnsupportedTransportDoesNotShareTheOriginalLink() {
        val edited = original.copy(edited = true, outbound = JSONObject(original.outbound)
            .put("transport", JSONObject().put("type", "unknown")).toString())
        assertNull(ShareLinks.of(edited))
    }

    @Test fun wireGuardTcpCheckIsRecognizedAsUdp() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D"
        val wg = Subscriptions.parseLink("wireguard://$key@wg.example.com:51820?publickey=$key&address=10.0.0.2%2F32#WG")
        assertNull(ProbePolicy.tcpEndpoint(wg))
        assertEquals("old.example.com" to 443, ProbePolicy.tcpEndpoint(original))
    }

    @Test fun inFlightRefreshCannotRecreateDeletedOrReplacedSubscription() {
        val removed = book.remove(source.id)
        assertFailsWith<StaleSubscriptionRefresh> { removed.refreshTarget(source.id, source.url) }
        val replaced = removed.upsert(source.copy(id = "replacement"))
        assertFailsWith<StaleSubscriptionRefresh> { replaced.refreshTarget(source.id, source.url) }
        assertEquals(listOf("replacement"), replaced.sources.map { it.id })
    }

    @Test fun inFlightRefreshCannotOverwriteEditedUrl() {
        val newUrl = "https://new.example.com/list"
        val edited = book.upsert(source.copy(url = newUrl))
        assertFailsWith<StaleSubscriptionRefresh> { edited.refreshTarget(source.id, source.url) }
        assertEquals(newUrl, edited.source(source.id)!!.url)
    }

    @Test fun deliberateUrlEditKeepsIdentityAndLatestName() {
        val renamed = book.rename(source.id, "Renamed while downloading")
        val target = assertNotNull(renamed.refreshTarget(source.id, "https://new.example.com/list", expectedUrl = source.url))
        assertEquals(source.id, target.id)
        assertEquals("Renamed while downloading", target.name)
        assertEquals(source, book.refreshTarget(source.id, source.url))
        assertEquals(source, book.refreshTarget(null, source.url))
        assertNull(book.refreshTarget(null, "https://another.example.com/list"))
    }
}
