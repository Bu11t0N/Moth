package org.routevpn.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
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

data class ProbeResult(val checking: Boolean = false, val latencyMs: Long? = null, val error: String? = null, val checkedAt: Long = System.currentTimeMillis())
data class HealthResult(val checking: Boolean = false, val latencyMs: Long? = null, val message: String = "Соединение ещё не проверено", val failures: Int = 0)
object Diagnostics {
    internal val resultsMutable = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val results = resultsMutable.asStateFlow()
    internal val healthMutable = MutableStateFlow(HealthResult())
    val health = healthMutable.asStateFlow()
    internal val runningMutable = MutableStateFlow(false)
    val running = runningMutable.asStateFlow()
    internal val speedMutable = MutableStateFlow("")
    val speed = speedMutable.asStateFlow()
    @Synchronized fun result(id: String, value: ProbeResult) { resultsMutable.value = resultsMutable.value + (id to value) }
    fun friendly(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> "Не ответил вовремя"
        is javax.net.ssl.SSLException -> "Не удалось проверить HTTPS"
        else -> "Проверка не прошла"
    }
    fun checkTunnel(manager: ConnectivityManager, address: String, timeout: Int): Long {
        val network = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            ?: error("VPN-сеть ещё не появилась")
        val started = System.nanoTime()
        val c = network.openConnection(URL(address)) as HttpsURLConnection
        try {
            c.connectTimeout = timeout * 1000; c.readTimeout = timeout * 1000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Cache-Control", "no-cache")
            check(c.responseCode in setOf(200, 204))
            if (c.responseCode == 200) c.inputStream.use { check(it.read() != -1) }
            return (System.nanoTime() - started) / 1_000_000
        } finally { c.disconnect() }
    }
}

/** No system DNS lookup: the destination hostname is sent to the authenticated SOCKS server. */
class ProxyRequest(private val service: VpnService, private val port: Int, private val user: String, private val password: String,
                   private val timeoutSeconds: Int, private val cancelled: AtomicBoolean) {
    @Volatile private var socket: Socket? = null
    fun cancel() { runCatching { socket?.close() } }
    fun run(address: String, downloadLimit: Int = 0): Pair<Long, Long> {
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null)
        val start = System.nanoTime()
        val deadline = start + timeoutSeconds * 1_000_000_000L
        fun timeout(): Int {
            check(!cancelled.get())
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw java.net.SocketTimeoutException()
            return left.toInt().coerceAtLeast(1)
        }
        val raw = Socket(); socket = raw
        try {
            check(service.protect(raw))
            raw.connect(InetSocketAddress("127.0.0.1", port), timeout())
            raw.soTimeout = timeout()
            val input = DataInputStream(raw.getInputStream()); val output = DataOutputStream(raw.getOutputStream())
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            val ub = user.toByteArray(); val pb = password.toByteArray()
            output.writeByte(1); output.writeByte(ub.size); output.write(ub); output.writeByte(pb.size); output.write(pb); output.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            val host = java.net.IDN.toASCII(url.host.removeSurrounding("[", "]")).toByteArray(Charsets.US_ASCII)
            require(host.size in 1..255)
            output.write(byteArrayOf(5, 1, 0, 3)); output.writeByte(host.size); output.write(host); output.writeShort(if (url.port < 0) 443 else url.port); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0); check(input.readUnsignedByte() == 0)
            val len = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS response") }
            input.readFully(ByteArray(len + 2))
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host.removeSurrounding("[", "]"), if (url.port < 0) 443 else url.port, true) as SSLSocket
            socket = tls
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = timeout(); tls.startHandshake()
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
            check(status in setOf(200, 204))
            val headers = mutableMapOf<String, String>()
            while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            val latency = (System.nanoTime() - start) / 1_000_000
            if (downloadLimit == 0) {
                if (status == 200) { tls.soTimeout = timeout(); check(response.read() >= 0) }
                return latency to 0L
            }
            check(status == 200)
            var bytes = 0L
            val buffer = ByteArray(8192)
            var remaining = headers["content-length"]?.toLongOrNull() ?: Long.MAX_VALUE
            val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
            val downloadStart = System.nanoTime()
            while (bytes < downloadLimit && remaining > 0) {
                if (chunked) { headerBytes = 0; remaining = line().substringBefore(';').trim().toLong(16); if (remaining == 0L) break }
                while (remaining > 0 && bytes < downloadLimit) {
                    tls.soTimeout = timeout()
                    val n = response.read(buffer, 0, minOf(buffer.size.toLong(), remaining, downloadLimit - bytes).toInt())
                    if (n < 0) { if (headers["content-length"] != null || chunked) error("Incomplete download"); remaining = 0; break }
                    remaining -= n; bytes += n
                }
                if (!chunked) break
                if (remaining == 0L) { headerBytes = 0; check(line().isEmpty()) }
            }
            check(bytes > 0)
            return bytes to (System.nanoTime() - downloadStart).coerceAtLeast(1L)
        } finally { runCatching { socket?.close() }; socket = null }
    }
}
