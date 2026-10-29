package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*

/** 0.8.0 beta 5: rules that can be switched off, the default Russian-sites rule, large titles switch, notification and title defaults. */
class Beta5Test {
    private val profile = Subscriptions.parseLink("trojan://secret@example.com:443?security=tls#Name")
    private val on = RoutingRule("domain_suffix", "ads.example", "block")
    private val off = RoutingRule("domain", "mail.example.com", "direct", enabled = false)

    @Test fun ruleSwitchRoundTripsAndOldFilesStayOn() {
        val s = ClientSettings(routingRules = listOf(on, off))
        assertEquals(s, ClientSettings.from(s.json()))
        assertFalse(on.json().has("enabled"), "a rule that is on keeps the old file format")
        assertTrue(RoutingRule.from(JSONObject().put("type", "domain").put("value", "a.example").put("action", "proxy")).enabled)
        assertEquals(listOf(on.copy(enabled = false), off), CustomRouting.setEnabled(listOf(on, off), 0, false))
        assertTrue(ClientSettings().needsReconnect(s)); assertTrue(s.needsReconnect(s.copy(routingRules = CustomRouting.setEnabled(s.routingRules, 1, true))))
        assertEquals(listOf("routingRulesOff=1->0"), LogFacts.settingsChanges(s, s.copy(routingRules = CustomRouting.setEnabled(s.routingRules, 1, true))))
    }

    @Test fun rulesThatAreOffAreSkipped() {
        val r = JSONObject(TunnelConfig.create(profile, ClientSettings(routingRules = listOf(off), ipv6 = false))).getJSONObject("route").getJSONArray("rules").toString()
        assertFalse("mail.example.com" in r); assertFalse("sniff" in r, "only rules that are on need sniffing")
        assertEquals(listOf("ads.example"), CustomRouting.singBox(listOf(off, on)).map { it.getJSONArray("domain_suffix").getString(0) })
        assertEquals(1, CustomRouting.xray(listOf(off, on)) { it }.size)
        assertFalse(CustomRouting.needsSniff(listOf(off)))
    }

    @Test fun russianSitesRuleKeepsTheSavedChoice() {
        // The old "Russian sites and IPs directly" setting is now the default rule; the stored key is the same, so nothing is lost.
        assertTrue(ClientSettings.from(ClientSettings(directRu = true).json()).directRu)
        assertFalse(ClientSettings.from(ClientSettings(directRu = false).json()).directRu)
        assertFalse(ClientSettings().directRu)
        val r = JSONObject(TunnelConfig.create(profile, ClientSettings(directRu = true), mapOf("geoip-ru" to "/x/geoip-ru.srs"))).getJSONObject("route").getJSONArray("rules").toString()
        assertTrue("geoip-ru" in r)
    }

    @Test fun largeTitlesSwitchAndSmoothHeight() {
        assertTrue(ClientSettings().largeTitles)
        assertFalse(ClientSettings.from(ClientSettings(largeTitles = false).json()).largeTitles)
        assertTrue(ClientSettings.from(ClientSettings().json().apply { remove("largeTitles") }).largeTitles)
        val d = ClientSettings()
        assertEquals(listOf("largeTitles=true->false"), LogFacts.settingsChanges(d, d.copy(largeTitles = false)))
        assertFalse(d.needsReconnect(d.copy(largeTitles = false)))
        assertEquals(164f, LargeTitleSize.extraDp(20.5f, 800), 0.001f)
        assertEquals(LargeTitleSize.extraDp(20, 800), LargeTitleSize.extraDp(20f, 800))
    }

    @Test fun notificationDefaults() {
        val d = ClientSettings()
        assertTrue(d.notifySpeed); assertTrue(d.notifyTotal); assertFalse(d.notifyUptime)
        // A choice already saved (0.7.6 wrote every line) is kept.
        assertTrue(ClientSettings.from(ClientSettings(notifyUptime = true).json()).notifyUptime)
    }
}
