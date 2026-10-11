package org.mothvpn.android

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
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

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return hex(digest.digest())
    }
    private fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Forgets the saved download and deletes every file in the update folder (keeping [keep]). */
    private fun forget(context: Context, keep: File? = null) {
        dir(context).listFiles()?.forEach { if (it != keep) { Logger.i("Update", "deleted stale update file (${it.length()} bytes)"); it.delete() } }
        if (keep == null) prefs(context).edit().clear().apply()
    }

    /** Deletes the saved download when the installed version already reaches it, and files nobody refers to. */
    fun cleanup(context: Context) {
        try {
            val p = prefs(context)
            val name = p.getString("file", null)
            val code = p.getLong("code", 0)
            val file = name?.let { File(dir(context), it) }
            if (file == null || !file.exists() || code <= installedCode(context)) {
                if (name != null) Logger.i("Update", "saved update dropped: code=$code installed=${installedCode(context)} exists=${file?.exists()}")
                forget(context)
            } else forget(context, file)
        } catch (e: Exception) { Logger.w("Update", "cleanup failed", e) }
    }

    /** The already downloaded APK of [release] when it is still intact (size, SHA-256 against SHA256SUMS, version code, signature); otherwise null. */
    suspend fun saved(context: Context, release: AppRelease): File? {
        val p = prefs(context)
        val name = p.getString("file", null) ?: return null
        val file = File(dir(context), name)
        try {
            if (p.getString("version", null) != release.version || name != release.apkName || !file.exists()) { Logger.i("Update", "no saved download for ${release.version}"); return null }
            val expected = try { AppRelease.checksum(text(release.sumsUrl, 64 * 1024), release.apkName) } catch (e: Exception) { Logger.w("Update", "checksum list unavailable, using the saved one", e); p.getString("sha256", "")!! }
            val ok = file.length() == release.size && file.length() == p.getLong("size", -1) && sha256(file) == expected
            Logger.i("Update", "saved download check: ${release.version} size=${file.length()}/${release.size} checksum=${if (ok) "ok" else "BAD"}")
            if (!ok) { forget(context); return null }
            verify(context, file, release.version)
            Logger.i("Update", "saved download verified: ${release.version}")
            return file
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Logger.w("Update", "saved download rejected", e); forget(context); return null }
    }

    suspend fun download(context: Context, release: AppRelease, progress: (Float) -> Unit = {}): File {
        Logger.i("Update", "download started: ${release.version} asset=${release.apkName} expected=${release.size} bytes")
        val expected = AppRelease.checksum(text(release.sumsUrl, 64 * 1024), release.apkName)
        forget(context)
        val dir = dir(context)
        val temporary = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val c = connection(release.apkUrl)
            try {
                Logger.i("Update", "download response: length=${c.contentLengthLong} type=${c.contentType}")
                java.io.FileOutputStream(temporary).use { output -> c.inputStream.use { input ->
                    var size = 0L; var mark = 0; val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= release.size)
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        val percent = (size * 100 / release.size).toInt()
                        progress(size.toFloat() / release.size)
                        if (percent >= mark + 25) { mark = percent / 25 * 25; Logger.d("Update", "download progress: $mark% ($size bytes)") }
                    }
                    require(size == release.size) { "Truncated download: $size of ${release.size}" }
                    output.flush(); output.fd.sync()
                    Logger.i("Update", "download finished: $size bytes written and synced")
                } }
            } finally { c.disconnect() }
            val actual = hex(digest.digest())
            if (actual != expected) Logger.e("Update", "downloaded APK checksum mismatch")
            check(actual == expected) { "APK checksum mismatch" }
            Logger.i("Update", "checksum verified")
            val code = verify(context, temporary, release.version)
            currentCoroutineContext().ensureActive()
            val file = File(dir, release.apkName)
            check(temporary.renameTo(file)) { "Cannot save APK" }
            prefs(context).edit().putString("file", file.name).putString("version", release.version).putLong("code", code)
                .putLong("size", file.length()).putString("sha256", actual).apply()
            Logger.i("Update", "update saved: ${release.version} code=$code")
            return file
        } finally { temporary.delete() }
    }

    /** Result of a PackageInstaller session, delivered to [InstallReceiver]. */
    const val INSTALL_ACTION = "org.mothvpn.android.INSTALL_RESULT"

    /** Streams [file] into a PackageInstaller session and commits it; the system answers through [InstallReceiver]. */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        Logger.i("Update", "install session created: id=$id size=${file.length()}")
        val session = installer.openSession(id)
        try {
            session.openWrite("moth.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            Logger.i("Update", "install session written: ${file.length()} bytes")
            val intent = Intent(context, InstallReceiver::class.java).setAction(INSTALL_ACTION).setPackage(context.packageName)
            val pending = android.app.PendingIntent.getBroadcast(context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0))
            session.commit(pending.intentSender)
            Logger.i("Update", "install session committed: id=$id")
        } catch (e: Exception) {
            Logger.e("Update", "install session failed: ${e.javaClass.simpleName}: ${e.message}")
            session.abandon(); throw e
        } finally { session.close() }
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, version: String): Long {
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
        Logger.i("Update", "APK verified: version=$version code=$code signature=same as installed")
        return code
    }
}

/** Receives the PackageInstaller result: asks the user to confirm when the system needs it, logs everything else. */
class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
        Logger.i("Update", "install result: status=$status message=$message other=${intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)}")
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm == null) { Logger.e("Update", "installer asked for confirmation without an intent"); return }
            try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Logger.i("Update", "install confirmation shown") }
            catch (e: Exception) { Logger.e("Update", "cannot show install confirmation: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }
}
