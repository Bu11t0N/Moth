package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * AmneziaWG servers on the Xray engine: a WireGuard outbound whose handshake is preceded by AmneziaWG junk packets
 * (Jc × random Jmin..Jmax bytes) and the I1–I5 signature packets, sent with Xray's UDP "finalmask" noise.
 *
 * Only the part of AmneziaWG that keeps the WireGuard packets themselves unchanged is supported: S1–S4 must be 0 and
 * H1–H4 must be the standard 1–4 (the Amnezia defaults for "compatible" servers). Servers with packet padding or custom
 * message headers need the AmneziaWG core itself and are rejected with a clear reason.
 * Accepted inputs: wireguard:// / wg:// links with jc, jmin, … parameters, Clash "amnezia-wg-option", WireGuard/AmneziaWG
 * .conf text ([Interface] / [Peer]) and Amnezia "vpn://" keys with an AmneziaWG container.
 */
object AmneziaWg {
    const val PROTOCOL = "amneziawg"
    val keys = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
    private val cpsTag = Regex("<(b (0x)?[0-9a-fA-F]{1,4096}|(r|rc|rd) \\d{1,4}(-\\d{1,4})?|t|c|n)>")

    fun has(params: Map<String, String>) = params.keys.any { it.lowercase() in keys }

    /** finalmask for Xray's streamSettings, or an error naming the unsupported AmneziaWG feature. [params] keys are lower case. */
    fun finalmask(params: Map<String, String>): JSONObject {
        fun int(k: String) = params[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: error("AmneziaWG: неверный параметр ${k.uppercase()}") }
        listOf("s1", "s2", "s3", "s4").forEach { k ->
            require((int(k) ?: 0) == 0) { "AmneziaWG: S1–S4 (дополнение пакетов) пока не поддерживаются, нужны S1–S4 = 0" }
        }
        listOf("h1", "h2", "h3", "h4").forEachIndexed { i, k ->
            val v = params[k]?.trim().orEmpty()
            require(v.isEmpty() || v == "${i + 1}" || v == "${i + 1}-${i + 1}") { "AmneziaWG: свои заголовки H1–H4 пока не поддерживаются, нужны H1–H4 = 1–4" }
        }
        val jc = int("jc") ?: 0
        val jmin = int("jmin") ?: 0
        val jmax = int("jmax") ?: jmin
        require(jc in 0..128) { "AmneziaWG: Jc от 0 до 128" }
        require(jc == 0 || (jmin in 1..1280 && jmax in jmin..1280)) { "AmneziaWG: Jmin/Jmax от 1 до 1280, Jmin ≤ Jmax" }
        val items = JSONArray()
        (1..5).mapNotNull { params["i$it"]?.trim()?.takeIf(String::isNotEmpty) }.forEach { packet ->
            // Tags keep their single inner space; whitespace between tags is dropped.
            val compact = packet.replace(Regex(">\\s+<"), "><")
            require(compact.length <= 10_000 && cpsTag.findAll(compact).joinToString("") { it.value } == compact) { "AmneziaWG: неверный пакет I1–I5" }
            items.put(JSONObject().put("type", "exp").put("packet", compact))
        }
        repeat(jc) { items.put(JSONObject().put("rand", if (jmin == jmax) "$jmin" else "$jmin-$jmax")) }
        require(items.length() > 0) { "AmneziaWG: нет Jc и I1–I5 — это обычный WireGuard" }
        // AmneziaWG sends the junk before each handshake (every ~2 minutes); "reset" re-arms the noise after that time.
        val noise = JSONObject().put("reset", "120").put("noise", items)
        return JSONObject().put("udp", JSONArray().put(JSONObject().put("type", "noise").put("settings", noise)))
    }

    /** An Xray WireGuard outbound with the AmneziaWG noise. Keys are checked like plain WireGuard. */
    fun profile(server: String, port: Int, privateKey: String?, publicKey: String?, preSharedKey: String?, addresses: List<String>, reserved: List<Int>?,
                mtu: Int?, params: Map<String, String>, name: String, id: String): Profile {
        // Validates keys, addresses, MTU and reserved exactly as the sing-box WireGuard path does.
        val wg = JSONObject(ProxyProtocols.wireguard(server, port, privateKey, publicKey, preSharedKey, addresses, reserved, mtu, name, id).outbound)
        val p = wg.getJSONArray("peers").getJSONObject(0)
        val host = p.getString("address")
        val peer = JSONObject().put("publicKey", p.getString("public_key")).put("allowedIPs", JSONArray(listOf("0.0.0.0/0", "::/0")))
            .put("endpoint", (if (host.contains(':')) "[$host]" else host) + ":" + p.getInt("port"))
        p.optString("pre_shared_key").takeIf { it.isNotEmpty() }?.let { peer.put("preSharedKey", it) }
        val settings = JSONObject().put("secretKey", wg.getString("private_key")).put("address", wg.getJSONArray("address"))
            .put("peers", JSONArray().put(peer)).put("noKernelTun", true).put("mtu", wg.optInt("mtu", 1280))
        p.optJSONArray("reserved")?.let { settings.put("reserved", it) }
        val o = JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", settings)
            .put("streamSettings", JSONObject().put("finalmask", finalmask(params.mapKeys { it.key.lowercase() })))
        return Profile(id, name.take(160), PROTOCOL, o.toString(), "xray")
    }

    fun isAmnezia(o: JSONObject) = o.optString("protocol") == "wireguard" && o.optJSONObject("streamSettings")?.has("finalmask") == true

    // ---- WireGuard / AmneziaWG .conf ----
    fun looksLikeConf(text: String): Boolean {
        val t = text.trim()
        return t.contains(Regex("^\\s*\\[Interface]\\s*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))) &&
            t.contains(Regex("^\\s*\\[Peer]\\s*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)))
    }

    /** One server from a .conf file. Plain WireGuard (no AmneziaWG keys) stays on sing-box. */
    fun parseConf(text: String, name: String = "WireGuard"): Profile {
        require(text.length <= 64 * 1024) { "WireGuard: файл слишком большой" }
        val iface = mutableMapOf<String, String>(); val peer = mutableMapOf<String, String>()
        var section: MutableMap<String, String>? = null; var peers = 0
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when (line.lowercase()) {
                "[interface]" -> section = iface
                "[peer]" -> { peers++; section = if (peers == 1) peer else null }
                else -> section?.let { s ->
                    val k = line.substringBefore('=').trim().lowercase(); val v = line.substringAfter('=', "").trim()
                    if (k.isNotEmpty() && line.contains('=')) s[k] = if (k == "address" && s[k] != null) s[k] + "," + v else v
                }
            }
        }
        require(peers >= 1) { "WireGuard: нет раздела [Peer]" }
        val endpoint = peer["endpoint"] ?: error("WireGuard: нет Endpoint")
        val host = if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: error("WireGuard: неверный Endpoint")
        val addresses = iface["address"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val mtu = iface["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500)
        val id = ProxyProtocols.sha("wgconf:" + text.trim())
        val awg = iface.filterKeys { it in keys }
        return if (awg.isEmpty() || awg.all { (k, v) -> v.trim().let { it.isEmpty() || it == "0" || (k.startsWith("h") && it == k.drop(1)) } })
            ProxyProtocols.wireguard(host, port, iface["privatekey"], peer["publickey"], peer["presharedkey"], addresses, null, mtu, name, id)
        else profile(host, port, iface["privatekey"], peer["publickey"], peer["presharedkey"], addresses, null, mtu, awg, name, id)
    }

    // ---- Amnezia "vpn://" keys (base64url of Qt qCompress'ed JSON) ----
    fun vpnKey(link: String): Profile {
        val payload = link.trim().substringAfter("://").substringBefore('#').filterNot(Char::isWhitespace)
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload.trimEnd('=')) }.getOrNull() ?: error("Amnezia: неверный ключ")
        require(bytes.size in 5..256 * 1024) { "Amnezia: неверный ключ" }
        val json = runCatching { inflate(bytes.copyOfRange(4, bytes.size)) }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: error("Amnezia: неверный ключ")
        val containers = json.optJSONArray("containers") ?: JSONArray()
        val awg = (0 until containers.length()).mapNotNull { containers.optJSONObject(it)?.optJSONObject("awg") }.firstOrNull()
            ?: error("Amnezia: в ключе нет AmneziaWG (поддерживается только AmneziaWG)")
        val last = awg.opt("last_config").let { if (it is String) runCatching { JSONObject(it) }.getOrNull() else it as? JSONObject } ?: error("Amnezia: в ключе нет настроек AmneziaWG")
        val name = json.optString("description").ifBlank { json.optString("name") }.ifBlank { "AmneziaWG" }
        val conf = last.optString("config")
        if (looksLikeConf(conf) && (!conf.contains('$') || last.optString("client_priv_key").isEmpty()))
            return parseConf(conf, name).copy(id = ProxyProtocols.sha(link.trim().substringBefore('#')))
        val host = last.optString("hostName").ifBlank { json.optString("hostName") }
        val port = last.optString("port").toIntOrNull() ?: awg.optString("port").toIntOrNull() ?: error("Amnezia: нет порта")
        val params = keys.associateWith { k -> last.optString(k.uppercase()).ifEmpty { awg.optString(k.uppercase()) } }.filterValues { it.isNotEmpty() }
        val addresses = last.optString("client_ip").split(',').map(String::trim).filter(String::isNotEmpty)
        return profile(host, port, last.optString("client_priv_key"), last.optString("server_pub_key"), last.optString("psk_key").ifBlank { null },
            addresses, null, last.optString("mtu").toIntOrNull()?.coerceIn(1280, 1500), params, name, ProxyProtocols.sha(link.trim().substringBefore('#')))
    }
    private fun inflate(data: ByteArray): String {
        val inflater = Inflater(); inflater.setInput(data)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() <= 1024 * 1024) { "Amnezia: ключ слишком большой" }
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }
}
