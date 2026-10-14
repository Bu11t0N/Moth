package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * SOCKS5, HTTP(S), WireGuard and SSH servers (sing-box outbounds). Credentials and keys stay inside the profile; they are never
 * written to the log (error messages name the problem only).
 */
object ProxyProtocols {
    val schemes = setOf("socks", "socks5", "socks5h", "http", "https", "wireguard", "wg", "ssh", "anytls")
    private fun decode(v: String) = URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
    private fun query(raw: String?) = raw.orEmpty().split('&').filter { it.isNotBlank() }.associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', "")) }
    internal fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun printable(s: String) = s.isNotEmpty() && s.all { it.code in 32..126 }
    private fun unbase64(s: String): String? = runCatching { Subscriptions.unbase64(s) }.getOrNull()?.takeIf(::printable)
    private val hostPort = Regex("(\\[[0-9A-Fa-f:.]+]|[^\\s:/@\\[\\]]+):\\d{1,5}([/?].*)?")

    /** True for servers whose traffic is not encrypted between the phone and the server (plain SOCKS and HTTP proxies). */
    fun unencrypted(p: Profile): Boolean {
        val first = p.protocol.lowercase().substringBefore(' ').substringBefore('/')
        return first in setOf("socks", "http") && !p.protocol.contains("tls")
    }

    fun parse(link: String): Profile = when (link.substringBefore("://").lowercase()) {
        "socks", "socks5", "socks5h" -> socksLink(link)
        "http", "https" -> httpLink(link)
        "wireguard", "wg" -> wireguardLink(link)
        "ssh" -> sshLink(link)
        "anytls" -> anytlsLink(link)
        else -> error("Протокол не поддерживается")
    }

    private fun name(fragment: String?, host: String) = fragment?.let(::decode)?.trim()?.ifBlank { host } ?: host
    private fun cleanHost(h: String?) = h?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: error("Неверный адрес сервера")
    private fun checkPort(p: Int) = p.also { require(it in 1..65535) { "Неверный порт" } }

    // ---- SOCKS5 ----
    /**
     * socks://BASE64(user:pass)@host:port (v2rayNG), socks5://user:pass@host:port (percent-encoded), the legacy
     * socks://BASE64(user:pass@host:port) and links without credentials.
     */
    private fun socksLink(link: String): Profile {
        val fragment = link.substringAfter('#', "").takeIf { link.contains('#') }
        var body = link.substringAfter("://").substringBefore('#')
        if (!body.contains('@') && !hostPort.matches(body)) body = unbase64(body.substringBefore('?')) ?: error("Неверная ссылка SOCKS")
        // Split at the last '@' by hand: base64 credentials may contain '/' or '+', which URI would misread.
        val at = body.lastIndexOf('@')
        val raw = if (at >= 0) body.substring(0, at) else ""
        val u = URI("socks://" + body.substring(at + 1))
        val host = cleanHost(u.host)
        val port = checkPort(if (u.port == -1) 1080 else u.port)
        var user = ""; var pass = ""
        if (raw.isNotEmpty()) {
            val decoded = if (raw.contains(':')) null else unbase64(decode(raw))
            when {
                raw.contains(':') -> { user = decode(raw.substringBefore(':')); pass = decode(raw.substringAfter(':')) }
                decoded != null && decoded.contains(':') -> { user = decoded.substringBefore(':'); pass = decoded.substringAfter(':') }
                else -> user = decode(raw)
            }
        }
        return socks(host, port, user, pass, name(fragment, host), sha(link.substringBefore('#')))
    }
    fun socks(server: String, port: Int, username: String, password: String, name: String, id: String): Profile {
        require(username.length <= 255 && password.length <= 255) { "Слишком длинные данные входа SOCKS" }
        val o = JSONObject().put("type", "socks").put("tag", "proxy").put("server", server).put("server_port", checkPort(port)).put("version", "5")
        if (username.isNotEmpty() || password.isNotEmpty()) o.put("username", username).put("password", password)
        return Profile(id, name.take(160), "socks", o.toString())
    }

    // ---- HTTP(S) proxy ----
    private fun httpLink(link: String): Profile {
        val u = URI(link.trim())
        val tls = u.scheme.equals("https", true)
        val host = cleanHost(u.host)
        val port = checkPort(if (u.port == -1) (if (tls) 443 else 80) else u.port)
        val q = query(u.rawQuery)
        require(q["allowinsecure"] !in setOf("1", "true") && q["insecure"] !in setOf("1", "true")) { "Небезопасная проверка TLS не поддерживается" }
        val raw = u.rawUserInfo.orEmpty()
        return http(host, port, decode(raw.substringBefore(':')), decode(raw.substringAfter(':', "")), tls, q["sni"] ?: q["peer"], name(u.rawFragment, host), sha(link.trim().substringBefore('#')))
    }
    fun http(server: String, port: Int, username: String, password: String, tls: Boolean, sni: String?, name: String, id: String): Profile {
        val o = JSONObject().put("type", "http").put("tag", "proxy").put("server", server).put("server_port", checkPort(port))
        if (username.isNotEmpty() || password.isNotEmpty()) o.put("username", username).put("password", password)
        if (tls) o.put("tls", JSONObject().put("enabled", true).put("server_name", sni?.takeIf { it.isNotBlank() } ?: server))
        return Profile(id, name.take(160), if (tls) "https" else "http", o.toString())
    }

    // ---- AnyTLS (sing-box 1.12) ----
    /** anytls://password@host:port?sni=&alpn=&fp=#name (insecure=1 is rejected). */
    private fun anytlsLink(link: String): Profile {
        val u = URI(link.trim().substringBefore('#').replace(" ", "%20"))
        val q = query(u.rawQuery)
        require(q["insecure"] !in setOf("1", "true") && q["allowinsecure"] !in setOf("1", "true")) { "Небезопасная проверка TLS не поддерживается" }
        val host = cleanHost(u.host)
        val password = u.rawUserInfo?.let(::decode)?.takeIf { it.isNotEmpty() } ?: error("Нет пароля")
        return anytls(host, checkPort(if (u.port == -1) 443 else u.port), password, q["sni"] ?: q["peer"], q["alpn"]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            q["fp"].orEmpty(), name(link.substringAfter('#', "").takeIf { link.contains('#') }, host), sha(link.trim().substringBefore('#')))
    }
    fun anytls(server: String, port: Int, password: String, sni: String?, alpn: List<String>, fingerprint: String, name: String, id: String): Profile {
        require(password.isNotEmpty()) { "Нет пароля" }
        val tls = JSONObject().put("enabled", true).put("server_name", sni?.takeIf { it.isNotBlank() } ?: server)
        if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn))
        if (fingerprint.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        val o = JSONObject().put("type", "anytls").put("tag", "proxy").put("server", server).put("server_port", checkPort(port)).put("password", password).put("tls", tls)
        return Profile(id, name.take(160), "anytls", o.toString())
    }

    // ---- WireGuard (sing-box endpoint) ----
    private val amnezia = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
    fun wgKey(value: String?, what: String): String {
        val v = value?.trim().orEmpty()
        val bytes = runCatching { Base64.getDecoder().decode(v) }.getOrNull() ?: runCatching { Base64.getUrlDecoder().decode(v) }.getOrNull()
        require(bytes != null && bytes.size == 32) { "WireGuard: неверный $what" }
        return Base64.getEncoder().encodeToString(bytes)
    }
    private fun prefix(a: String): String {
        val v = a.trim()
        require(Regex("[0-9A-Fa-f:.]{2,45}(/\\d{1,3})?").matches(v)) { "WireGuard: неверный адрес интерфейса" }
        return if (v.contains('/')) v else if (v.contains(':')) "$v/128" else "$v/32"
    }
    private fun reserved(v: String?): List<Int>? {
        val t = v?.trim().orEmpty().ifEmpty { return null }
        val list = if (t.contains(',') || t.all { it.isDigit() }) t.split(',').map { it.trim().toInt() }
            else (runCatching { Base64.getDecoder().decode(t) }.getOrNull() ?: error("WireGuard: неверный reserved")).map { it.toInt() and 0xff }
        require(list.size == 3 && list.all { it in 0..255 }) { "WireGuard: неверный reserved" }
        return list
    }
    private fun wireguardLink(link: String): Profile {
        val u = URI(link.trim())
        val q = query(u.rawQuery)
        require(q.keys.none { it in amnezia }) { "AmneziaWG не поддерживается" }
        val host = cleanHost(u.host)
        val port = checkPort(if (u.port == -1) 51820 else u.port)
        val privateKey = u.rawUserInfo?.takeIf { it.isNotEmpty() }?.let(::decode) ?: q["privatekey"] ?: q["private_key"] ?: q["pk"]
        val addresses = (q["address"] ?: q["ip"] ?: q["local_address"] ?: q["localaddress"] ?: error("WireGuard: нет адреса интерфейса")).split(',').filter { it.isNotBlank() }
        return wireguard(host, port, privateKey, q["publickey"] ?: q["public_key"] ?: q["peer_pk"] ?: q["peer_public_key"] ?: q["peerpublickey"],
            q["presharedkey"] ?: q["pre_shared_key"] ?: q["psk"], addresses, reserved(q["reserved"]), q["mtu"]?.toIntOrNull(), name(u.rawFragment, host), sha(link.trim().substringBefore('#')))
    }
    fun wireguard(server: String, port: Int, privateKey: String?, publicKey: String?, preSharedKey: String?, addresses: List<String>, reserved: List<Int>?,
                  mtu: Int?, name: String, id: String): Profile {
        require(addresses.isNotEmpty() && addresses.size <= 4) { "WireGuard: нет адреса интерфейса" }
        val peer = JSONObject().put("address", server).put("port", checkPort(port)).put("public_key", wgKey(publicKey, "публичный ключ"))
            .put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
        preSharedKey?.takeIf { it.isNotBlank() }?.let { peer.put("pre_shared_key", wgKey(it, "общий ключ")) }
        reserved?.let { peer.put("reserved", JSONArray(it)) }
        val o = JSONObject().put("type", "wireguard").put("tag", "proxy").put("system", false).put("address", JSONArray(addresses.map(::prefix)))
            .put("private_key", wgKey(privateKey, "закрытый ключ")).put("peers", JSONArray().put(peer))
        mtu?.let { require(it in 1280..1500) { "WireGuard: MTU от 1280 до 1500" }; o.put("mtu", it) }
        return Profile(id, name.take(160), "wireguard", o.toString())
    }
    /** Server address and port of a WireGuard endpoint (used for TCP-less probes and descriptions). */
    fun wireguardPeer(o: JSONObject): Pair<String, Int>? = o.optJSONArray("peers")?.optJSONObject(0)?.let { it.optString("address") to it.optInt("port") }

    // ---- SSH ----
    private fun sshLink(link: String): Profile {
        val u = URI(link.trim())
        val q = query(u.rawQuery)
        val host = cleanHost(u.host)
        val raw = u.rawUserInfo ?: error("SSH: нет имени пользователя")
        val key = (q["pk"] ?: q["private_key"] ?: q["privatekey"])?.let { k -> if (k.trimStart().startsWith("-----BEGIN")) k else unbase64Multiline(k) }
        // Host keys are "algorithm base64"; a '+' that stands for the space (form encoding) is restored.
        val hostKeys = (q["hk"] ?: q["host_key"] ?: q["hostkey"])?.split(',')?.map { it.trim().replaceFirst(Regex("^([a-z0-9@.-]+)\\+(?=AAAA)"), "$1 ") }?.filter { it.isNotEmpty() }.orEmpty()
        require(hostKeys.all { Regex("[a-z0-9@.-]+ [A-Za-z0-9+/]+=*").matches(it) }) { "SSH: неверный ключ сервера" }
        return ssh(host, if (u.port == -1) 22 else u.port, decode(raw.substringBefore(':')), decode(raw.substringAfter(':', "")), key, q["pkp"] ?: q["private_key_passphrase"],
            hostKeys, name(u.rawFragment, host), sha(link.trim().substringBefore('#')))
    }
    private fun unbase64Multiline(v: String): String = runCatching { Subscriptions.unbase64(v) }.getOrNull()?.takeIf { it.contains("-----BEGIN") } ?: error("SSH: неверный закрытый ключ")
    fun ssh(server: String, port: Int, user: String, password: String, privateKey: String?, passphrase: String?, hostKeys: List<String>, name: String, id: String): Profile {
        require(user.isNotBlank() && user.length <= 255) { "SSH: нет имени пользователя" }
        require(password.isNotEmpty() || !privateKey.isNullOrBlank()) { "SSH: нужен пароль или ключ" }
        require(privateKey == null || (privateKey.length <= 16384 && privateKey.contains("-----BEGIN"))) { "SSH: неверный закрытый ключ" }
        val o = JSONObject().put("type", "ssh").put("tag", "proxy").put("server", server).put("server_port", checkPort(port)).put("user", user)
        if (password.isNotEmpty()) o.put("password", password)
        privateKey?.let { o.put("private_key", it) }
        passphrase?.takeIf { it.isNotEmpty() }?.let { o.put("private_key_passphrase", it) }
        if (hostKeys.isNotEmpty()) o.put("host_key", JSONArray(hostKeys.take(8)))
        return Profile(id, name.take(160), "ssh", o.toString())
    }

    // ---- Shadowsocks over ShadowTLS (Clash plugin / sing-box detour) ----
    fun shadowTls(ss: Profile, server: String, port: Int, version: Int, password: String, sni: String, fingerprint: String?): Profile {
        require(version in 1..3) { "ShadowTLS: неверная версия" }
        require(version == 1 || password.isNotEmpty()) { "ShadowTLS: нет пароля" }
        require(sni.isNotBlank()) { "ShadowTLS: нет SNI" }
        val tls = JSONObject().put("enabled", true).put("server_name", sni)
        fingerprint?.takeIf { it.isNotBlank() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        val hop = JSONObject().put("type", "shadowtls").put("tag", "proxy-shadowtls").put("server", server).put("server_port", checkPort(port))
            .put("version", version).put("tls", tls).apply { if (version > 1) put("password", password) }
        val o = JSONObject(ss.outbound).put("detour", "proxy-shadowtls").put(SingBoxOutbounds.CHAIN, JSONArray().put(hop))
        return ss.copy(id = sha(ss.id + "|shadowtls|" + hop), protocol = "ss + shadowtls", outbound = o.toString(), link = "")
    }
}
