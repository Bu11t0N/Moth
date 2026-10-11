package org.mothvpn.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mothvpn.core.AppRelease
import org.mothvpn.core.UpdateChannel
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

object AppUpdater {
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    private fun connection(address: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.host in hosts && url.userInfo == null && url.port in listOf(-1, 443))
            val c = url.openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Moth/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (c.responseCode in listOf(301, 302, 303, 307, 308)) {
                    val target = c.getHeaderField("Location") ?: error("Missing redirect")
                    url = URL(url, target); c.disconnect()
                } else {
                    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                    return c
                }
            } catch (e: Exception) { c.disconnect(); throw e }
        }
        error("Too many redirects")
    }

    private suspend fun text(url: String, limit: Int): String {
        val c = connection(url)
        return try {
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= limit)
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }

    /** Release channel reads releases/latest (never a prerelease); Beta reads the releases list and takes the newest non-draft version. */
    suspend fun check(channel: UpdateChannel): AppRelease? {
        val abis = Build.SUPPORTED_ABIS.toList()
        Logger.i("Update", "check started: channel=${channel.id} installed=${BuildConfig.VERSION_NAME} source=${if (channel == UpdateChannel.BETA) "releases list" else "latest release"}")
        val found = when (channel) {
            UpdateChannel.RELEASE -> AppRelease.parse(text("https://api.github.com/repos/Bu11t0N/Moth/releases/latest", 2 * 1024 * 1024), BuildConfig.VERSION_NAME, abis)
            UpdateChannel.BETA -> AppRelease.parseList(text("https://api.github.com/repos/Bu11t0N/Moth/releases?per_page=30", 4 * 1024 * 1024), BuildConfig.VERSION_NAME, abis)
        }
        Logger.i("Update", "check finished: channel=${channel.id} result=${found?.let { "${it.version} available" } ?: "up to date"}")
        return found
    }

    suspend fun download(context: Context, release: AppRelease): File {
        val expected = AppRelease.checksum(text(release.sumsUrl, 64 * 1024), release.apkName)
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(dir, "${UUID.randomUUID()}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val c = connection(release.apkUrl)
            try {
                c.inputStream.use { input -> temporary.outputStream().use { output ->
                    var size = 0L; val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= release.size)
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                    require(size == release.size)
                } }
            } finally { c.disconnect() }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) Logger.e("Update", "downloaded APK checksum mismatch")
            check(actual == expected) { "APK checksum mismatch" }
            verify(context, temporary, release.version)
            currentCoroutineContext().ensureActive()
            val file = File(dir, release.apkName)
            check(temporary.renameTo(file)) { "Cannot save APK" }
            return file
        } finally { temporary.delete() }
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, version: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == version)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code > BuildConfig.VERSION_CODE)
        fun certs(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val trusted = certs(installed)
        require(trusted.isNotEmpty() && certs(archive) == trusted) { "APK signing certificate mismatch" }
    }
}
