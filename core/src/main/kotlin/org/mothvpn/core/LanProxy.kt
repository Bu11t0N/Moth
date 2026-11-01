package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sharing the VPN with other devices: a sing-box "mixed" inbound (HTTP and SOCKS5 on one port) listening on every interface of the
 * phone, so a laptop or a second phone on the phone's hotspot, USB/Bluetooth tethering or the same Wi-Fi can put the phone's address
 * and this port into its proxy settings. Their traffic then takes the same route as the phone's own (server, routing rules, DNS).
 *
 * Only clients from private addresses (LAN, hotspot) are served; the phone's own addresses known at connect time and loopback are
 * refused, so apps on the phone (split tunneling) cannot use it to get around their setting.
 */
object LanProxy {
    const val TAG = "lan-in"
    const val DEFAULT_PORT = 10808
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    fun validPort(port: Int) = port in MIN_PORT..MAX_PORT

    /** The inbound: HTTP + SOCKS5 (with UDP) on [port], all IPv4 interfaces. */
    fun inbound(port: Int): JSONObject = JSONObject().put("type", "mixed").put("tag", TAG).put("listen", "0.0.0.0").put("listen_port", port)

    /** Route rules for the inbound, put first: public sources, loopback and the phone's own [ownAddresses] are rejected. */
    fun rules(ownAddresses: Collection<String>): List<JSONObject> {
        val own = (listOf("127.0.0.0/8", "::1/128") + ownAddresses.mapNotNull(::hostCidr)).distinct()
        return listOf(
            // "source_ip_is_private": false is read as "not set" by sing-box (it would reject every client), hence the logical rule.
            JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray().put(JSONObject().put("inbound", JSONArray().put(TAG)))
                .put(JSONObject().put("source_ip_is_private", true).put("invert", true))).put("action", "reject"),
            JSONObject().put("inbound", JSONArray().put(TAG)).put("source_ip_cidr", JSONArray(own)).put("action", "reject"))
    }

    /** "192.168.1.5" -> "192.168.1.5/32", "fe80::1%wlan0" -> "fe80::1/128"; anything else -> null. */
    fun hostCidr(address: String): String? {
        val a = address.substringBefore('%').trim()
        return when {
            Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(a) && a.split('.').all { it.toInt() in 0..255 } -> "$a/32"
            a.contains(':') && a.all { it.isLetterOrDigit() || it == ':' || it == '.' } -> "$a/128"
            else -> null
        }
    }

    /** Addresses to show in Settings ("host:port"): private IPv4 addresses of the phone's interfaces, hotspot / tethering first. */
    fun endpoints(interfaces: List<Pair<String, String>>, port: Int): List<Pair<String, String>> = interfaces
        .filter { (name, ip) -> !name.startsWith("tun") && !name.startsWith("lo") && privateV4(ip) }
        .sortedBy { (name, _) -> if (sharingInterface(name)) 0 else 1 }
        .map { (name, ip) -> name to "$ip:$port" }.distinctBy { it.second }

    /** Interface names Android uses for the hotspot and tethering (ap0, swlan0, wlan1, rndis0, usb0, ncm0, bt-pan …). */
    fun sharingInterface(name: String) = listOf("ap", "swlan", "softap", "rndis", "usb", "ncm", "bt-pan", "bt_pan", "wlan1", "wigig").any { name.startsWith(it) }

    fun privateV4(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
}
