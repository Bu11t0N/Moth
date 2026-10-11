package org.mothvpn.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.mothvpn.core.ClientSettings
import org.mothvpn.core.SubscriptionHeaders
import android.os.Build
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

typealias SavedSubscription = org.mothvpn.core.SubscriptionRecord

class SubscriptionStore(context: Context, filename: String = "subscription.enc") {
    // Activity and VPN service create different store instances. AtomicFile alone
    // does not synchronize them; a reader must not interrupt an in-flight write.
    private companion object { val storageLock = Any() }
    private val file = AtomicFile(java.io.File(context.noBackupFilesDir, filename))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mothvpn-subscription", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mothvpn-subscription", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(saved: SavedSubscription) {
        val json = saved.json()
        writeJson(json)
    }
    fun writeJson(json: JSONObject) = synchronized(storageLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(byteArrayOf(cipher.iv.size.toByte())); stream.write(cipher.iv); stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun readJson(): JSONObject? = synchronized(storageLock) {
        if (!file.baseFile.exists()) return@synchronized null
        val bytes = file.readFully()
        require(bytes.isNotEmpty() && bytes[0].toInt() == 12 && bytes.size > 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13))) }
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    fun readOrInitialize(default: () -> JSONObject): JSONObject = synchronized(storageLock) {
        readJson() ?: default().also { writeJson(it) }
    }
    @Synchronized fun load(): SavedSubscription? {
        val json = readJson() ?: return null
        return SavedSubscription.from(json)
    }
    fun clear() = synchronized(storageLock) { file.delete() }
}

class SettingsStore(context: Context) {
    private val store = SubscriptionStore(context, "settings.enc")
    fun load(): ClientSettings = ClientSettings.from(store.readOrInitialize { ClientSettings().json() }).withAppVersion(BuildConfig.VERSION_NAME)
    fun save(value: ClientSettings) = store.writeJson(value.validate().json())
}

object SubscriptionDownload {
    data class Response(val body: String, val name: String, val expires: Long, val used: Long, val total: Long)
    fun fetch(address: String, settings: ClientSettings = ClientSettings()): String = fetchDetails(address, settings).body
    fun fetchDetails(address: String, settings: ClientSettings = ClientSettings()): Response {
        var url = URL(address.trim())
        val original = url.toURI()
        repeat(6) {
            require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { tr(R.string.msg_use_an_https_link_without_a_username_or_passw_112) }
            val connection = url.openConnection() as HttpsURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            SubscriptionHeaders.create(settings, original, url.toURI(), Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}")
                .forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                val response = connection.responseCode
                Logger.d("Download", "HTTP $response")
                if (connection.getHeaderField("x-hwid-max-devices-reached") == "true" || connection.getHeaderField("x-hwid-limit") == "true")
                    { Logger.w("Download", "provider reports the device limit"); error(tr(R.string.msg_subscription_device_limit_reached_contact_the_113)) }
                if (connection.getHeaderField("x-hwid-not-supported") == "true") { Logger.w("Download", "provider requires HWID"); error(tr(R.string.msg_the_provider_requires_hwid_enable_it_in_setti_114)) }
                when (response) {
                    301, 302, 303, 307, 308 -> { val next = connection.getHeaderField("Location") ?: error(tr(R.string.msg_redirect_destination_is_missing_115)); url = URL(url, next) }
                    200 -> {
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = input.read(chunk)
                                if (count == -1) break
                                require(out.size() + count <= 2 * 1024 * 1024) { tr(R.string.msg_the_server_list_is_too_large_116) }
                                out.write(chunk, 0, count)
                            }
                            out.toByteArray()
                        }
                        require(bytes.size <= 2 * 1024 * 1024) { tr(R.string.msg_the_server_list_is_too_large_116) }
                        val title = connection.getHeaderField("profile-title").orEmpty().let { text ->
                            if (text.startsWith("base64:")) runCatching { String(android.util.Base64.decode(text.removePrefix("base64:"), android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("") else text
                        }.take(120)
                        val info = connection.getHeaderField("subscription-userinfo").orEmpty().split(';').mapNotNull {
                            val pair = it.trim().split('=', limit = 2)
                            if (pair.size == 2) pair[1].toLongOrNull()?.coerceAtLeast(0)?.let { n -> pair[0] to n } else null
                        }.toMap()
                        val used = (info["upload"] ?: 0).let { u -> val d = info["download"] ?: 0; if (Long.MAX_VALUE - u < d) Long.MAX_VALUE else u + d }
                        return Response(String(bytes, Charsets.UTF_8), title, info["expire"] ?: 0, used, info["total"] ?: 0)
                    }
                    401, 403 -> { Logger.w("Download", "access denied (HTTP $response)"); error(tr(R.string.msg_subscription_is_unavailable_check_the_link_an_117)) }
                    else -> { Logger.w("Download", "unexpected HTTP $response"); error(tr(R.string.msg_subscription_service_returned_an_error_try_ag_118)) }
                }
            } finally { connection.disconnect() }
        }
        error(tr(R.string.msg_too_many_redirects_119))
    }
}
