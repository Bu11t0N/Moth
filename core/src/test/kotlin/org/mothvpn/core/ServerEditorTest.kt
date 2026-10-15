package org.mothvpn.core

import org.json.JSONObject
import kotlin.test.*
import org.mothvpn.core.ServerEditor.Error
import org.mothvpn.core.ServerEditor.Result

class ServerEditorTest {
    private fun err(t: String, e: String = "sing-box") = (ServerEditor.validate(t, e) as? Result.Invalid)?.error

    @Test fun rejectsInvalid() {
        assertEquals(Error.EMPTY, err("  "))
        assertEquals(Error.NOT_OBJECT, err("{"))
        assertEquals(Error.NOT_OBJECT, err("[1,2]"))
        assertEquals(Error.NOT_OBJECT, err("\"x\""))
        assertEquals(Error.NOT_OBJECT, err("{\"type\":\"vless\"} junk"))
        assertEquals(Error.NO_TYPE, err("{\"server\":\"a\"}"))
        assertEquals(Error.NO_PROTOCOL, err("{\"type\":\"vless\"}", "xray"))
        assertEquals(Error.TOO_LARGE, err("{\"type\":\"" + "a".repeat(ServerEditor.MAX_LENGTH) + "\"}"))
    }

    @Test fun acceptsValid() {
        assertIs<Result.Ok>(ServerEditor.validate("{\n \"type\": \"vless\", \"server\": \"a\"\n}", "sing-box"))
        assertIs<Result.Ok>(ServerEditor.validate("{\"protocol\":\"vless\"}", "xray"))
        assertIs<Result.Ok>(ServerEditor.validate("{\"group\":true,\"outbounds\":[]}", "xray"))
    }

    @Test fun applyKeepsIdAndMarksEdited() {
        val p = Subscriptions.parseLink("trojan://pw@a.example.com:443#n")
        val book = SubscriptionBook.migrate(SubscriptionRecord("https://e.example.com/s", listOf(p), p.id))
        val edited = JSONObject(p.outbound).put("transport", JSONObject().put("type", "ws")).toString()
        val next = ServerEditor.apply(book, p.id, (ServerEditor.validate(edited, p.engine) as Result.Ok).outbound)
        val q = SubscriptionBook.from(next.json()).profiles.single()
        assertEquals(p.id, q.id); assertTrue(q.edited)
        assertEquals("Trojan · WS · TLS", ServerLabels.label(q))
        assertTrue(JSONObject(ServerEditor.pretty(p)).similar(JSONObject(p.outbound)))
    }
}
