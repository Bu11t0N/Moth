package org.mothvpn.core

import kotlin.test.*

class RuleTextTest {
    private val rules = listOf(RoutingRule("domain", "mail.example.com", "direct"), RoutingRule("domain_suffix", "example.org", "block"),
        RoutingRule("domain_keyword", "ads", "block"), RoutingRule("ip_cidr", "10.0.0.0/8", "proxy"), RoutingRule("ip_cidr", "2001:db8::/32", "direct"))

    @Test fun formatAndParseRoundTrip() {
        val text = RuleText.format(rules)
        assertEquals("domain:mail.example.com direct\nsuffix:example.org block\nkeyword:ads block\nip:10.0.0.0/8 proxy\nip:2001:db8::/32 direct\n", text)
        val r = RuleText.import(text, emptyList())
        assertEquals(rules, r.rules); assertEquals(5, r.added); assertEquals(0, r.invalid); assertEquals(0, r.duplicates)
    }

    @Test fun bareListsUseDefaultsAndReportProblems() {
        val text = "\uFEFF# my list\nExample.COM\n\n*.second.example block\n1.2.3.4\nbad value here\nsuffix:example.com\nfoo:bar direct\nip:300.1.1.1 direct\nexample.net teleport\nкто.рф\n"
        val r = RuleText.import(text, listOf(RoutingRule("domain_suffix", "existing.example", "direct")), CustomRouting.SUFFIX, CustomRouting.BLOCK)
        assertEquals(listOf(
            RoutingRule("domain_suffix", "existing.example", "direct"),
            RoutingRule("domain_suffix", "example.com", "block"),
            RoutingRule("domain_suffix", "second.example", "block"),
            RoutingRule("ip_cidr", "1.2.3.4/32", "block"),
            RoutingRule("domain_suffix", "xn--j1ail.xn--p1ai", "block")), r.rules)
        assertEquals(4, r.added)
        assertEquals(listOf(6, 8, 9, 10), r.invalidLines) // 3 tokens, unknown type, bad ip, unknown action
        assertEquals(1, r.duplicates) // suffix:example.com again
        assertEquals(RoutingRule("domain", "a.example", "proxy"), RuleText.parseLine("a.example", CustomRouting.DOMAIN, CustomRouting.PROXY))
        assertEquals(RoutingRule("keyword", "x", "direct").copy(type = "domain_keyword"), RuleText.parseLine("domain_keyword:x", CustomRouting.DOMAIN, CustomRouting.DIRECT))
    }

    @Test fun respectsTheLimit() {
        val existing = (1..498).map { RoutingRule("domain", "h$it.example", "direct") }
        val r = RuleText.import("a.example\nb.example\nc.example\nd.example\n", existing, CustomRouting.DOMAIN, CustomRouting.DIRECT)
        assertEquals(500, r.rules.size); assertEquals(2, r.added); assertEquals(2, r.overLimit)
        assertTrue(CustomRouting.valid(r.rules))
    }
}
