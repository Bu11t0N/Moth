package org.mothvpn.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.mothvpn.core.ProviderLinks
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Provider avatars: the logo header, else the profile picture on the provider's public Telegram page (bot, channel or user),
 * else icons from the provider's web page (apple-touch-icon, favicon, og:image). Downloaded over https only (redirects too), at
 * most [MAX_BYTES], decoded as a raster image (PNG, JPEG, WebP, ICO; SVG is not supported), scaled to [SIZE] px and cached as
 * PNG per subscription in no-backup storage. When the VPN is up, the request goes through the tunnel so a blocked site (t.me in
 * some networks) is still reachable. URLs are never logged; they can be personal.
 */
object ProviderLogos {
    private const val MAX_BYTES = 1024 * 1024
    private const val SIZE = 192
    private const val PAGE_LIMIT = 256 * 1024
    /** A generic browser UA: t.me and some sites return a poorer page (or none) for an app user-agent. */
    private const val PAGE_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private fun dir(context: Context) = File(context.noBackupFilesDir, "provider-logos").apply { mkdirs() }
    fun file(context: Context, sourceId: String) = File(dir(context), "${sourceId.filter { it.isLetterOrDigit() }.take(32)}.png")
    private fun marker(context: Context, sourceId: String) = File(dir(context), "${sourceId.filter { it.isLetterOrDigit() }.take(32)}.url")

    /**
     * Keeps the cached logo in step with [links]. A miss is retried on the next refresh (failures are not stored forever).
     * [allowPhysical]: when false (VPN-only updates) the download is skipped unless the tunnel is up, so a cosmetic request
     * never goes out on the physical network.
     */
    fun refresh(context: Context, sourceId: String, links: ProviderLinks, userAgent: String, allowPhysical: Boolean) {
        if (!allowPhysical && !TunnelDownloads.ready()) return
        val f = file(context, sourceId); val m = marker(context, sourceId)
        val official = links.logo
        val telegram = ProviderLinks.telegramPage(links)
        val web = if (links.web.isNotEmpty() && !ProviderLinks.telegram(links.web)) links.web else ""
        val key = listOf(official, telegram.orEmpty(), web).joinToString("\n")
        if (key.isBlank()) { f.delete(); m.delete(); return }
        val hash = sha(key)
        val mark = runCatching { m.readText() }.getOrNull()
        if (f.exists() && mark == hash) return
        runCatching {
            val bitmap = official.takeIf { it.isNotEmpty() }?.let {
                runCatching { download(it, userAgent, 1) }.onFailure { e -> Logger.w("Logo", "provider logo header not used: ${brief(e)}") }.getOrNull()
            } ?: fallback(telegram, web, userAgent)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (!tmp.renameTo(f)) { tmp.delete(); error("rename failed") }
            m.writeText(hash)
            Logger.i("Logo", "provider logo cached (${f.length()} bytes)")
        }.onFailure {
            Logger.w("Logo", "provider logo not used: ${brief(it)}")
        }
    }
    fun remove(context: Context, sourceId: String) { file(context, sourceId).delete(); marker(context, sourceId).delete() }

    private fun fallback(telegram: String?, web: String, userAgent: String): Bitmap {
        if (telegram != null) {
            runCatching {
                val photo = ProviderLinks.telegramPhoto(page(telegram, PAGE_UA)) ?: error("no profile picture")
                download(photo, PAGE_UA, 32)
            }.onSuccess { return it }.onFailure { Logger.w("Logo", "Telegram page photo not used: ${brief(it)}") }
        }
        if (web.isNotEmpty()) {
            val fromPage = runCatching { ProviderLinks.pageIcons(page(web, PAGE_UA), web) }.onFailure { Logger.w("Logo", "web page not read for icons: ${brief(it)}") }.getOrDefault(emptyList())
            val tries = (fromPage + ProviderLinks.webIconGuesses(web)).distinct()
            var last: Throwable? = null
            for (address in tries) {
                runCatching { download(address, userAgent, 32) }.onSuccess { return it }.onFailure { last = it }
            }
            last?.let { Logger.w("Logo", "web page icon not used: ${brief(it)}") }
        }
        error("no icon")
    }

    /** HTML over https (redirects followed, at most [PAGE_LIMIT]). */
    private fun page(address: String, userAgent: String): String {
        var url = URL(address)
        repeat(4) {
            val c = open(url)
            c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent); c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            try {
                when (val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> url = next(url, c.getHeaderField("Location"))
                    200 -> {
                        val bytes = c.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                            while (out.size() < PAGE_LIMIT) { val n = input.read(chunk); if (n < 0) break; out.write(chunk, 0, n) }
                            out.toByteArray()
                        }
                        return String(bytes, Charsets.UTF_8)
                    }
                    else -> error("HTTP $code")
                }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    /** [minSide]: smaller images are rejected (a 16 px favicon would look blurry as an avatar; 32 px is the floor for fallbacks). */
    private fun download(address: String, userAgent: String, minSide: Int = 1): Bitmap {
        var url = URL(address)
        repeat(4) {
            val c = open(url)
            c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent); c.setRequestProperty("Accept", "image/png, image/jpeg, image/webp, image/x-icon, image/vnd.microsoft.icon")
            try {
                when (val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> url = next(url, c.getHeaderField("Location"))
                    200 -> {
                        val type = c.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                        if (type.isNotEmpty()) require(type.startsWith("image/") || type == "application/octet-stream") { "not an image" }
                        require(c.contentLengthLong <= MAX_BYTES) { "too large" }
                        val bytes = c.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                            while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= MAX_BYTES) { "too large" }; out.write(chunk, 0, n) }
                            out.toByteArray()
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "not an image" }
                        require(minOf(bounds.outWidth, bounds.outHeight) >= minSide) { "too small" }
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("not an image")
                        val side = minOf(decoded.width, decoded.height)
                        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
                        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
                        if (square !== decoded) decoded.recycle(); if (scaled !== square) square.recycle()
                        return scaled
                    }
                    else -> error("HTTP $code")
                }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    /** Through the tunnel when it is up, otherwise the default network. */
    private fun open(url: URL): HttpsURLConnection {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "https only" }
        val via = TunnelDownloads.session?.takeIf { TunnelDownloads.ready() }
        return if (via != null) TunnelDownloads.open(url, via) else url.openConnection() as HttpsURLConnection
    }
    private fun next(from: URL, location: String?): URL {
        val url = URL(from, location ?: error("redirect without location"))
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "https only" }
        return url
    }
    private fun brief(t: Throwable): String {
        val msg = t.message?.replace(Regex("https?://\\S+"), "https://…").orEmpty()
        return t.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
    }
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
