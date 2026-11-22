package org.mothvpn.core

import java.io.File
import kotlin.test.*

/** 1.0.4-beta.2: Updates page fit, filter on/off, change history, merged split-tunnel row, LAN status line, colour logo, Telegram plane. */
class Release104Test {
    private fun resources(language: String) = File("../app/src/main/res/$language/strings.xml").readText()
    private fun source(name: String) = File("../app/src/main/java/org/mothvpn/android/$name").readText()

    // --- Updates page: never scrolls; the moth shrinks, and below the minimum it is not shown -----------------------------------
    @Test fun updatesMothShrinksThenDisappears() {
        val artwork = 260f
        val home = MothArtworkSize.drawingSide(artwork)
        assertEquals(home, MothArtworkSize.fitPageDrawing(artwork, 1000f))
        assertEquals(120f, MothArtworkSize.fitPageDrawing(artwork, 120f))
        assertEquals(MothArtworkSize.MIN_PAGE_DRAWING, MothArtworkSize.fitPageDrawing(artwork, MothArtworkSize.MIN_PAGE_DRAWING))
        assertEquals(0f, MothArtworkSize.fitPageDrawing(artwork, MothArtworkSize.MIN_PAGE_DRAWING - 1f))
        assertEquals(0f, MothArtworkSize.fitPageDrawing(artwork, -50f))
    }
    @Test fun updatesPageHasNoScroll() {
        val page = source("UpdateScreen.kt")
        assertFalse(page.contains("verticalScroll")); assertFalse(page.contains("rememberScrollState"))
        assertTrue(page.contains("fitPageDrawing"))
    }

    // --- Server filter: keywords can be switched off without deleting them ----------------------------------------------------
    @Test fun switchedOffKeywordDoesNotFilter() {
        val rule = ServerFilter.Rule(listOf("Germany", "gaming"), ServerFilter.EXCLUDE)
        assertFalse(rule.shows("Germany 1"))
        val off = rule.toggle("germany", false)
        assertEquals(listOf("Germany", "gaming"), off.keywords)
        assertEquals(listOf("gaming"), off.active)
        assertTrue(off.shows("Germany 1")); assertFalse(off.shows("gaming 2"))
        assertTrue(off.valid())
        val on = off.toggle("Germany", true)
        assertEquals(listOf("Germany", "gaming"), on.active); assertFalse(on.shows("Germany 1"))
        val removed = off.remove("Germany")
        assertEquals(listOf("gaming"), removed.keywords); assertEquals(emptyList(), removed.disabled)
    }
    @Test fun allKeywordsOffShowsEverythingInOnlyMode() {
        val rule = ServerFilter.Rule(listOf("NL"), ServerFilter.ONLY).toggle("NL", false)
        assertTrue(rule.shows("Germany"))
        val p = listOf(Profile("a", "Germany", "vless", "{}"), Profile("b", "NL 1", "vless", "{}"))
        assertEquals(p, ServerFilter.pool(p, ClientSettings(wifiFilter = rule), "wifi"))
    }
    @Test fun filtersSavedBeforeAreAllOnAndStateRoundTrips() {
        val old = org.json.JSONObject().put("keywords", org.json.JSONArray(listOf("Germany"))).put("mode", "exclude")
        val migrated = ServerFilter.Rule.from(old)
        assertEquals(listOf("Germany"), migrated.active); assertEquals(emptyList(), migrated.disabled)
        val s = ClientSettings(wifiFilter = ServerFilter.Rule(listOf("Germany", "US"), ServerFilter.EXCLUDE, listOf("US")))
        val back = ClientSettings.from(s.json())
        assertEquals(s.wifiFilter, back.wifiFilter); assertEquals(listOf("Germany"), back.wifiFilter.active)
        // A switched-off keyword that is no longer in the list is dropped when read.
        val stray = ServerFilter.Rule.from(old.put("disabled", org.json.JSONArray(listOf("gone"))))
        assertEquals(emptyList(), stray.disabled)
    }

    // --- Change history -------------------------------------------------------------------------------------------------------
    private fun vless(id: String, name: String, host: String, port: Int, uuid: String = "11111111-1111-1111-1111-111111111111", sni: String = "a.example") =
        Profile(id, name, "vless", """{"type":"vless","server":"$host","server_port":$port,"uuid":"$uuid","tls":{"enabled":true,"server_name":"$sni"},"tag":"proxy"}""")
    private fun sub(vararg p: Profile, expires: Long = 0) = SubscriptionSource("s", "", "https://example.invalid/sub", p.toList(), expires = expires)

    @Test fun diffFindsAddedChangedAndRemoved() {
        val old = sub(vless("1", "Alpha", "10.0.0.1", 443), vless("2", "Beta", "10.0.0.2", 443), vless("3", "Gamma", "10.0.0.3", 443))
        // Beta: new link (new id), same name, other port and key -> changed. Gamma removed. Delta added. Alpha renamed (same id).
        val new = sub(vless("1", "Alpha 2", "10.0.0.1", 443), vless("2b", "Beta", "10.0.0.2", 8443, uuid = "22222222-2222-2222-2222-222222222222"),
            vless("4", "Delta", "10.0.0.4", 443))
        val e = SubscriptionChanges.diff(old, new, 1000L)!!
        assertEquals(1000L, e.at)
        assertEquals(listOf("Delta"), e.added.map { it.name }); assertEquals("10.0.0.4:443", e.added[0].address)
        assertEquals(listOf("Gamma"), e.removed.map { it.name })
        assertEquals(listOf("Alpha 2", "Beta"), e.changed.map { it.after.name }.sorted())
        val alpha = e.changed.first { it.before.name == "Alpha" }
        assertEquals(listOf(SubscriptionChanges.Field("name", "Alpha", "Alpha 2")), alpha.fields)
        val beta = e.changed.first { it.after.name == "Beta" }
        assertEquals(SubscriptionChanges.Field("server_port", "443", "8443"), beta.fields.first { it.key == "server_port" })
        val key = beta.fields.first { it.key == "uuid" }
        assertTrue(key.secret); assertEquals("", key.old); assertEquals("", key.new) // secrets: "changed", never the values
        assertFalse(SubscriptionChanges.toJson(mapOf("s" to listOf(e))).toString().contains("2222"))
    }
    @Test fun noEntryWithoutContentChangeAndOtherChangesListed() {
        val a = sub(vless("1", "Alpha", "10.0.0.1", 443))
        assertNull(SubscriptionChanges.diff(a, a.copy(usedBytes = 5), 1L))
        assertNull(SubscriptionChanges.diff(null, a, 1L))
        val e = SubscriptionChanges.diff(a, a.copy(expires = 99), 2L)!!
        assertEquals(listOf(SubscriptionChanges.OTHER_EXPIRES), e.other); assertTrue(e.added.isEmpty() && e.removed.isEmpty() && e.changed.isEmpty())
    }
    @Test fun historyIsBoundedNewestFirstAndRoundTrips() {
        var list = emptyList<SubscriptionChanges.Entry>()
        repeat(30) { list = SubscriptionChanges.append(list, SubscriptionChanges.Entry(it.toLong(), other = listOf("total"))) }
        assertEquals(SubscriptionChanges.MAX_ENTRIES, list.size); assertEquals(29L, list.first().at)
        val old = sub(vless("1", "Alpha", "10.0.0.1", 443)); val new = sub(vless("1", "Alpha", "10.0.0.1", 443, sni = "b.example"), vless("2", "B", "h.example", 80))
        val e = SubscriptionChanges.diff(old, new, 5L)!!
        val back = SubscriptionChanges.fromJson(org.json.JSONObject(SubscriptionChanges.toJson(mapOf("s" to listOf(e))).toString()))
        assertEquals(listOf(e), back["s"])
    }
    @Test fun changeHistoryIsADeveloperToggleOffByDefault() {
        assertFalse(ClientSettings().changeHistory)
        assertTrue(ClientSettings.from(ClientSettings(changeHistory = true).json()).changeHistory)
        val servers = source("ServersScreen.kt")
        assertTrue(servers.contains("settings.changeHistory) NdSheetItem(tr(R.string.sub_changes)"))
        listOf("values", "values-ru").forEach { assertTrue(resources(it).contains("name=\"dev_change_history\"")) }
        assertTrue(resources("values-ru").contains("<string name=\"sub_changes\">Изменения</string>"))
    }

    // --- Smaller items --------------------------------------------------------------------------------------------------------
    @Test fun splitTunnelHasOneShowSystemRow() {
        listOf("values", "values-ru").forEach { assertFalse(resources(it).contains("name=\"split_all_apps\"")) }
        assertTrue(resources("values-ru").contains("<string name=\"split_show_system\">Показывать системные</string>"))
        val page = source("SplitTunnelScreen.kt")
        assertEquals(1, Regex("Checkbox\\(showSystem").findAll(page).count())
        assertFalse(page.contains("mutableStateOf(false) }\n    LaunchedEffect(settings.splitAllApps)"))
    }
    @Test fun lanProxyHasNoRunningOnPortLine() {
        listOf("values", "values-ru").forEach { assertFalse(resources(it).contains("name=\"lan_state_running\"")); assertTrue(resources(it).contains("name=\"lan_state_busy\"")) }
    }
    @Test fun providerLogoIsInColourAndTelegramUsesItsPlane() {
        val design = source("NewDesign.kt")
        assertFalse(design.contains("setToSaturation"))
        assertFalse(design.contains("Icons.AutoMirrored.Rounded.Send"))
        assertTrue(design.contains("ProviderLinks.telegram(it)) TelegramPlane"))
    }
    @Test fun homeServerSheetHidesUnavailableWithALabel() {
        val design = source("NewDesign.kt")
        assertTrue(design.contains("!results[\"\$kind:\${it.id}\"].unavailable()"))
        assertTrue(design.contains("if (hiding) Text(tr(R.string.msg_hide_unavailable_56)"))
    }
}
