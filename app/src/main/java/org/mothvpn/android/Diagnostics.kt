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
    /**
     * The last finished result per "network page:server" (see PingMemory): kept until that server is checked again, with no expiry,
     * across network changes, VPN on/off and app restarts (saved to a private file). A running or interrupted check never blanks it.
     */
    private val finished = HashMap<String, ProbeResult>()
    private val saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ping-memory").apply { isDaemon = true } }
    private var saveQueued = false
    private var loaded = false
    private fun file() = java.io.File(MothApplication.instance.filesDir, "pings.txt")

    /** Reads the saved results once per process (called from the Application, before any screen or check). */
    @Synchronized fun load() {
        if (loaded) return; loaded = true
        val entries = runCatching { file().takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("").let { org.mothvpn.core.PingMemory.decode(it) }
        for (e in entries) {
            val kind = e.kind?.let { k -> runCatching { ProbeErrorKind.valueOf(k) }.getOrNull() ?: ProbeErrorKind.FAILED }
            finished[e.key] = ProbeResult(latencyMs = e.latencyMs, error = kind?.let { kindText(it) }, checkedAt = e.checkedAt, method = e.method, networkHandle = e.networkHandle, kind = kind)
        }
        // Live entries (a check that already started) win over the saved ones.
        resultsMutable.value = finished + resultsMutable.value
        Logger.i("Probe", "restored ${entries.size} saved ping results")
    }

    private fun scheduleSave() {
        if (saveQueued) return; saveQueued = true
        saver.schedule({
            val snapshot = synchronized(this) { saveQueued = false; finished.map { (k, r) -> org.mothvpn.core.PingMemory.Entry(k, r.latencyMs, r.kind?.name ?: if (r.latencyMs == null) ProbeErrorKind.FAILED.name else null, r.method, r.checkedAt, r.networkHandle) } }
            runCatching {
                val target = file(); val tmp = java.io.File(target.parentFile, "pings.txt.tmp")
                tmp.writeText(org.mothvpn.core.PingMemory.encode(snapshot)); if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            }.onFailure { Logger.w("Probe", "saving ping results failed", it) }
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * Ends a check that did not finish (cancelled, network changed, crashed): shows [previous] again, or else the last finished
     * result of this server on this page. Only a server that was never checked shows nothing.
     */
    @Synchronized fun restore(id: String, previous: ProbeResult?) {
        val back = previous ?: finished[id]
        resultsMutable.value = if (back != null) resultsMutable.value + (id to back) else resultsMutable.value - id
    }
    @Synchronized fun result(id: String, value: ProbeResult) {
        resultsMutable.value = resultsMutable.value + (id to value)
        if (org.mothvpn.core.PingMemory.keeps(value.checking, value.cancelled || value.kind == ProbeErrorKind.CANCELLED, value.latencyMs, value.error != null)) {
            finished[id] = value; scheduleSave()
        }
    }
    /** The last finished result of [id] (any network handle), for putting it back after an interrupted check. */
    @Synchronized fun lastFinished(id: String): ProbeResult? = finished[id]
    /** Check URLs are never logged: the app's default address is reported as "default", anything else as "custom". */
    fun describeUrl(url: String): String = if (url == org.mothvpn.core.ClientSettings().probeUrl) "default" else "custom"
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
    /** Time of the warm second request when [warm] measured one (null: it was not possible and the first request's time is used). */
    @Volatile var warmMs: Long? = null; private set
    @Volatile var firstMs: Long? = null; private set
    /**
     * HTTPS request through the local proxy. The first request (local SOCKS, the server, remote TCP + TLS, status and headers) must
     * succeed. With [warm] (the "Latency" method) a second request then goes over the same kept-alive TLS connection and its time
     * (request sent → status and headers) is returned; if the connection cannot be reused or the second request fails, the first
     * request's time is returned. Without [warm] ("Full latency") the first request's time is returned.
     */
    fun run(address: String, warm: Boolean = false): Long {
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null)
        warmMs = null; firstMs = null
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
            val target = url.file.ifBlank { "/" }
            val out = tls.getOutputStream()
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
            /** Sends one request and reads its status line and headers. */
            fun exchange(keepAlive: Boolean): Triple<String, Int, Map<String, String>> {
                headerBytes = 0
                out.write(org.mothvpn.core.ProbeHttp.request(target, url.authority, keepAlive).toByteArray(Charsets.US_ASCII)); out.flush()
                val statusLine = line()
                val status = org.mothvpn.core.ProbeHttp.status(statusLine)
                check(status in setOf(200, 204)) { "unexpected HTTP status $status" }
                stage = "HTTP headers"
                val headers = mutableListOf<String>()
                while (true) { val l = line(); if (l.isEmpty()) break; headers += l }
                return Triple(statusLine, status!!, org.mothvpn.core.ProbeHttp.headers(headers))
            }
            stage = "HTTP request/status line"
            val (statusLine, status, headers) = exchange(keepAlive = warm)
            val first = (System.nanoTime() - start) / 1_000_000
            firstMs = first
            val body = org.mothvpn.core.ProbeHttp.bodyLength(status, headers)
            if (!warm || !org.mothvpn.core.ProbeHttp.reusable(statusLine, headers, body)) {
                stage = "HTTP body"
                if (status == 200) { tls.soTimeout = timeout(); check(response.read() >= 0) }
                if (warm) Logger.d("Probe", "warm request not possible (connection not reusable); using the first request")
                stage = "done"
                return first
            }
            // Warm-up done: skip its body (generate_204 has none), then time the second request on the same connection.
            val second = try {
                stage = "warm-up body"
                fun skip(n: Long) { var left = n; while (left > 0) { tls.soTimeout = timeout(); val k = response.skip(left); if (k <= 0) { check(response.read() >= 0); left-- } else left -= k } }
                if (body == org.mothvpn.core.ProbeHttp.CHUNKED) {
                    var total = 0L
                    while (true) { val size = org.mothvpn.core.ProbeHttp.chunkSize(line()); total += size; require(total <= 1_048_576); if (size == 0L) { while (line().isNotEmpty()) {}; break }; skip(size); line() }
                } else { require(body!! <= 1_048_576); skip(body) }
                stage = "second request"
                val t = System.nanoTime()
                exchange(keepAlive = false)
                (System.nanoTime() - t) / 1_000_000
            } catch (e: Exception) {
                if (cancelled.get()) throw e
                Logger.d("Probe", "second request failed at '$stage' (${e.javaClass.simpleName}); using the first request")
                null
            }
            warmMs = second
            stage = "done"
            return org.mothvpn.core.ProbeHttp.shown(first, second)
        } finally { runCatching { socket?.close() }; socket = null }
    }
}
