package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.mothvpn.core.XrayJsonFixtures as F

class BalancerPingTest {
    private fun parse(vararg docs: JSONObject) = Subscriptions.parse(JSONArray().also { a -> docs.forEach { a.put(it) } }.toString()).profiles
    /** A typical provider subscription: a "fastest" balancer document plus each server on its own. */
    private val list = parse(F.config("Fastest", F.ws(), F.xhttp(), F.tcpReality(), balancer = true),
        F.config("WS", F.ws()), F.config("XHTTP", F.xhttp()), F.config("Reality", F.tcpReality()), F.config("Other", F.tcpReality(host = "o.example.com")))
    private val group = list.first { BalancerPing.isGroup(it) }
    private fun id(name: String) = list.first { it.name == name }.id

    @Test fun membersMatchTheServersFromTheSameSubscription() {
        assertEquals(1, list.count(BalancerPing::isGroup))
        assertEquals(listOf(id("WS"), id("XHTTP"), id("Reality")), BalancerPing.members(group, list))
    }

    @Test fun membersMatchByIdentityAcrossFormats() {
        // The same VLESS server imported as a share link (sing-box outbound): same address, port and user id.
        val link = Subscriptions.parseLink("vless://${F.UUID1}@a.example.com:443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp&flow=xtls-rprx-vision#Link")
        val reality = list.first { it.name == "Reality" }
        assertEquals(BalancerPing.identity(JSONObject(reality.outbound)), BalancerPing.identity(JSONObject(link.outbound)))
        assertEquals(listOf(link.id), BalancerPing.members(group, listOf(group, link)))
        // Different port: not a member.
        val other = Subscriptions.parseLink("vless://${F.UUID1}@a.example.com:8443?security=reality&pbk=${F.KEY}&sni=b.example.com&type=tcp#X")
        assertTrue(BalancerPing.members(group, listOf(group, other)).isEmpty())
    }

    @Test fun derivedPingIsTheBestMember() {
        val m = listOf("a", "b", "c")
        assertEquals(BalancerPing.Derived.Value(80, "b"), BalancerPing.derive(m, mapOf("a" to BalancerPing.Member(120, false), "b" to BalancerPing.Member(80, false), "c" to BalancerPing.Member(null, true))))
        // One member measured is enough.
        assertEquals(BalancerPing.Derived.Value(300, "c"), BalancerPing.derive(m, mapOf("c" to BalancerPing.Member(300, false))))
        // Unavailable only when every member failed.
        assertEquals(BalancerPing.Derived.Failed("a"), BalancerPing.derive(m, m.associateWith { BalancerPing.Member(null, true) }))
        assertEquals(BalancerPing.Derived.Unknown, BalancerPing.derive(m, mapOf("a" to BalancerPing.Member(null, true))))
        assertEquals(BalancerPing.Derived.Unknown, BalancerPing.derive(m, emptyMap()))
        assertEquals(BalancerPing.Derived.Unknown, BalancerPing.derive(emptyList(), emptyMap()))
    }

    @Test fun checksDoNotProbeDerivableBalancers() {
        val all = BalancerPing.plan(list, list, single = false)
        assertFalse(all.probe.any(BalancerPing::isGroup))
        assertEquals(list.filterNot(BalancerPing::isGroup).map { it.id }, all.probe.map { it.id })
        assertEquals(mapOf(group.id to listOf(id("WS"), id("XHTTP"), id("Reality"))), all.derived)
        // Checking just the balancer measures its members.
        val one = BalancerPing.plan(listOf(group), list, single = true)
        assertEquals(listOf(id("WS"), id("XHTTP"), id("Reality")), one.probe.map { it.id })
        // A balancer whose members are not listed is probed itself (fallback).
        val alone = parse(F.config("Fastest", F.ws(), F.xhttp(), balancer = true))
        assertEquals(alone.map { it.id }, BalancerPing.plan(alone, alone, single = false).probe.map { it.id })
        assertTrue(BalancerPing.plan(alone, alone, single = true).derived.isEmpty())
    }

    @Test fun autoSelectAndFailoverNeverPickBalancers() {
        val pool = ProbePlan.autoPool(list, ClientSettings(), "wifi")
        assertTrue(pool.none(BalancerPing::isGroup))
        assertEquals(list.size - 1, pool.size)
        assertTrue(ProbePlan.autoPool(listOf(group), ClientSettings(), "wifi").isEmpty())
    }

    @Test fun deadBalancerFailsOverToALivePlainServer() {
        // The user picked the balancer; its members all stopped answering, so the health check through the tunnel fails.
        assertTrue(Reselect.failoverDue(autoSelect = true, manualPick = false, failures = 3, after = 3))
        assertFalse(Reselect.failoverDue(autoSelect = false, manualPick = false, failures = 10, after = 3)) // auto-select off: nothing switches
        val candidates = ProbePlan.autoPool(list, ClientSettings(), "wifi").map { it.id }
        assertEquals(Reselect.Decision.Switch(id("Other")), Reselect.decide(Reselect.Reason.FAILOVER, group.id, listOf(id("Other"), id("WS")).filter { it in candidates }))
        assertEquals(Reselect.Decision.Stay, Reselect.decide(Reselect.Reason.FAILOVER, group.id, emptyList())) // none answer: stay (backoff)
        assertEquals(Reselect.Decision.Switch(id("WS")), Reselect.decide(Reselect.Reason.RESELECT, group.id, listOf(id("WS"))))
    }
}
