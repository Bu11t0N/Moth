package org.mothvpn.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Provider avatars ([org.mothvpn.core.ProviderLinks.logo]): downloaded over https only (redirects too), at most [MAX_BYTES],
 * decoded as a raster image (PNG, JPEG, WebP; SVG is not supported and falls back to the tile icon), scaled to [SIZE] px and cached as
 * PNG per subscription in no-backup storage. The URL is never logged; it can be personal.
 */
object ProviderLogos {
    private const val MAX_BYTES = 1024 * 1024
    private const val SIZE = 192
    private fun dir(context: Context) = File(context.noBackupFilesDir, "provider-logos").apply { mkdirs() }
    fun file(context: Context, sourceId: String) = File(dir(context), "${sourceId.filter { it.isLetterOrDigit() }.take(32)}.png")
    private fun marker(context: Context, sourceId: String) = File(dir(context), "${sourceId.filter { it.isLetterOrDigit() }.take(32)}.url")

    /** Keeps the cached logo in step with [url]: downloads when it changed or is missing, removes it when the provider stopped sending one. */
    fun refresh(context: Context, sourceId: String, url: String, userAgent: String) {
        val f = file(context, sourceId); val m = marker(context, sourceId)
        if (url.isEmpty()) { f.delete(); m.delete(); return }
        val hash = sha(url)
        if (f.exists() && m.exists() && runCatching { m.readText() }.getOrNull() == hash) return
        runCatching {
            val bitmap = download(url, userAgent)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (!tmp.renameTo(f)) { tmp.delete(); error("rename failed") }
            m.writeText(hash)
            Logger.i("Logo", "provider logo cached (${f.length()} bytes)")
        }.onFailure { Logger.w("Logo", "provider logo not used: ${it.javaClass.simpleName}") }
    }
    fun remove(context: Context, sourceId: String) { file(context, sourceId).delete(); marker(context, sourceId).delete() }

    private fun download(address: String, userAgent: String): Bitmap {
        var url = URL(address)
        repeat(4) {
            require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "https only" }
            val c = url.openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent); c.setRequestProperty("Accept", "image/png, image/jpeg, image/webp")
            try {
                when (val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> url = URL(url, c.getHeaderField("Location") ?: error("redirect without location"))
                    200 -> {
                        require(c.contentLengthLong <= MAX_BYTES) { "too large" }
                        val bytes = c.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                            while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= MAX_BYTES) { "too large" }; out.write(chunk, 0, n) }
                            out.toByteArray()
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "not an image" }
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
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
