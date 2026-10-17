package org.mothvpn.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import org.mothvpn.core.ProbeErrorKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProbeResult(val checking: Boolean = false, val latencyMs: Long? = null, val error: String? = null, val checkedAt: Long = System.currentTimeMillis(), val method: String = "HTTPS", val networkHandle: Long = 0, val cancelled: Boolean = false, val kind: ProbeErrorKind? = null)
data class HealthResult(val checking: Boolean = false, val latencyMs: Long? = null, val message: String = tr(R.string.msg_connection_has_not_been_checked_yet_0), val failures: Int = 0)
object Diagnostics {
    internal val resultsMutable = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val results = resultsMutable.asStateFlow()
    internal val healthMutable = MutableStateFlow(HealthResult())
    val health = healthMutable.asStateFlow()
    internal val runningMutable = MutableStateFlow(false)
    val running = runningMutable.asStateFlow()
    /** Puts back [previous] (or removes the entry) after a check was cancelled. */
    @Synchronized fun restore(id: String, previous: ProbeResult?) { resultsMutable.value = if (previous != null) resultsMutable.value + (id to previous) else resultsMutable.value - id }
    @Synchronized fun result(id: String, value: ProbeResult) { resultsMutable.value = resultsMutable.value + (id to value) }
    /** Built-in probe URLs are public and logged verbatim; a user-supplied URL is only reported as "custom". */
    fun describeUrl(url: String): String = if (url in setOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204")) url else "custom"
    fun friendly(e: Throwable) = kindText(ProbeErrorKind.of(e))
    /** Localized text for an outcome code (used for the log-free tooltip of the detailed icons). */
    fun kindText(k: ProbeErrorKind) = when (k) {
        ProbeErrorKind.TIMEOUT -> tr(R.string.msg_server_timed_out_1)
        ProbeErrorKind.DNS_TIMEOUT -> tr(R.string.msg_dns_lookup_timed_out_2)
        ProbeErrorKind.DNS_UNRESOLVED -> tr(R.string.msg_could_not_resolve_the_server_address_3)
        ProbeErrorKind.PORT_CLOSED -> tr(R.string.msg_server_port_is_not_responding_4)
        ProbeErrorKind.TLS -> tr(R.string.msg_could_not_verify_https_5)
        ProbeErrorKind.FAILED -> tr(R.string.msg_check_failed_6)
        ProbeErrorKind.CANCELLED -> tr(R.string.msg_check_cancelled_46)
        ProbeErrorKind.UDP_PROTOCOL -> tr(R.string.msg_udp_protocol_choose_an_https_check_129)
    }
    fun checkTunnel(manager: ConnectivityManager, address: String, timeoutMs: Long): Long {
        val network = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            ?: error(tr(R.string.msg_vpn_network_is_not_ready_yet_7))
        val started = System.nanoTime()
        val c = network.openConnection(URL(address)) as HttpsURLConnection
        var stage = "connect"
        try {
            c.connectTimeout = timeoutMs.toInt(); c.readTimeout = timeoutMs.toInt()
            c.instanceFollowRedirects = false
            c.setRequestProperty("Cache-Control", "no-cache")
            stage = "http response"
            check(c.responseCode in setOf(200, 204)) { "unexpected HTTP status ${c.responseCode}" }
            stage = "read body"
            if (c.responseCode == 200) c.inputStream.use { check(it.read() != -1) }
            return (System.nanoTime() - started) / 1_000_000
        } catch (t: Throwable) { Logger.d("Health", "checkTunnel failed at stage '$stage'"); throw t
        } finally { c.disconnect() }
    }
}

/** No system DNS lookup: the destination hostname is sent to the authenticated SOCKS server. */
class ProxyRequest(private val service: VpnService, private val port: Int, private val user: String, private val password: String,
                   private val timeoutMs: Long, private val cancelled: AtomicBoolean) {
    @Volatile private var socket: Socket? = null
    /** Last handshake stage reached by run(); only for diagnostics. */
    @Volatile var stage = "not started"; private set
    fun cancel() { runCatching { socket?.close() } }
    /** HTTPS request through the local proxy; returns the latency in ms (connect, TLS and the first response byte). */
    fun run(address: String): Long {
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null)
        val start = System.nanoTime()
        val deadline = start + timeoutMs * 1_000_000L
        fun timeout(): Int {
            check(!cancelled.get())
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw java.net.SocketTimeoutException()
            return left.toInt().coerceAtLeast(1)
        }
        val raw = Socket(); socket = raw
        try {
            // The loopback connection to the local SOCKS proxy never needs protect(); it returns false when no VPN is up.
            stage = "protect socket (optional)"
            if (!service.protect(raw)) Logger.d("Probe", "protect(loopback socket) returned false; continuing (not required for 127.0.0.1)")
            stage = "tcp connect to local SOCKS"
            raw.connect(InetSocketAddress("127.0.0.1", port), timeout())
            raw.soTimeout = timeout()
            val input = DataInputStream(raw.getInputStream()); val output = DataOutputStream(raw.getOutputStream())
            stage = "SOCKS greeting"
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            stage = "SOCKS auth"
            val ub = user.toByteArray(); val pb = password.toByteArray()
            output.writeByte(1); output.writeByte(ub.size); output.write(ub); output.writeByte(pb.size); output.write(pb); output.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            stage = "SOCKS connect request (remote connect through proxy)"
            val host = java.net.IDN.toASCII(url.host.removeSurrounding("[", "]")).toByteArray(Charsets.US_ASCII)
            require(host.size in 1..255)
            output.write(byteArrayOf(5, 1, 0, 3)); output.writeByte(host.size); output.write(host); output.writeShort(if (url.port < 0) 443 else url.port); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0); check(input.readUnsignedByte() == 0)
            val len = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS response") }
            input.readFully(ByteArray(len + 2))
            stage = "TLS socket setup"
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host.removeSurrounding("[", "]"), if (url.port < 0) 443 else url.port, true) as SSLSocket
            socket = tls
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = timeout()
            stage = "TLS handshake"; tls.startHandshake()
            stage = "HTTP request/status line"
            val target = url.file.ifBlank { "/" }
            require(!target.contains('\r') && !target.contains('\n'))
            tls.getOutputStream().write("GET $target HTTP/1.1\r\nHost: ${url.authority}\r\nConnection: close\r\nAccept-Encoding: identity\r\nCache-Control: no-cache\r\n\r\n".toByteArray(Charsets.US_ASCII))
            val response = BufferedInputStream(tls.getInputStream())
            var headerBytes = 0
            fun line(): String {
                val b = StringBuilder()
                while (true) {
                    tls.soTimeout = timeout()
                    val ch = response.read(); check(ch >= 0)
                    require(++headerBytes <= 32768)
                    if (ch == 10) return b.toString().trimEnd('\r')
                    b.append(ch.toChar())
                }
            }
            val status = line().split(' ').getOrNull(1)?.toIntOrNull()
            check(status in setOf(200, 204)) { "unexpected HTTP status $status" }
            stage = "HTTP headers"
            while (true) { if (line().isEmpty()) break }
            val latency = (System.nanoTime() - start) / 1_000_000
            stage = "HTTP body"
            if (status == 200) { tls.soTimeout = timeout(); check(response.read() >= 0) }
            stage = "done"
            return latency
        } finally { runCatching { socket?.close() }; socket = null }
    }
}
