package org.routevpn.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.routevpn.core.Profile
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedSubscription(val url: String, val profiles: List<Profile>, val selected: String)

class SubscriptionStore(context: Context) {
    private val file = AtomicFile(java.io.File(context.noBackupFilesDir, "subscription.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("routevpn-subscription", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("routevpn-subscription", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(saved: SavedSubscription) {
        val json = JSONObject().put("url", saved.url).put("selected", saved.selected).put("profiles", JSONArray().apply {
            saved.profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("protocol", it.protocol).put("outbound", it.outbound)) }
        })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(byteArrayOf(cipher.iv.size.toByte())); stream.write(cipher.iv); stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun load(): SavedSubscription? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.isNotEmpty() && bytes[0].toInt() == 12 && bytes.size > 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13))) }
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
        val list = json.getJSONArray("profiles")
        return SavedSubscription(json.getString("url"), (0 until list.length()).map { i -> list.getJSONObject(i).let {
            Profile(it.getString("id"), it.getString("name"), it.getString("protocol"), it.getString("outbound"))
        } }, json.getString("selected"))
    }
    @Synchronized fun clear() = file.delete()
}

object SubscriptionDownload {
    fun fetch(address: String): String {
        var url = URL(address.trim())
        repeat(6) {
            require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "Нужна HTTPS-ссылка без логина и пароля в адресе" }
            val connection = url.openConnection() as HttpsURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Moth/0.1")
            connection.setRequestProperty("Accept", "text/plain, application/octet-stream")
            try {
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> { val next = connection.getHeaderField("Location") ?: error("Нет адреса перенаправления"); url = URL(url, next) }
                    200 -> {
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = input.read(chunk)
                                if (count == -1) break
                                require(out.size() + count <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
                                out.write(chunk, 0, count)
                            }
                            out.toByteArray()
                        }
                        require(bytes.size <= 2 * 1024 * 1024) { "Список серверов слишком большой" }
                        return String(bytes, Charsets.UTF_8)
                    }
                    401, 403 -> error("Подписка недоступна. Проверь ссылку и срок действия.")
                    else -> error("Сервис подписки не ответил успешно. Попробуй позже.")
                }
            } finally { connection.disconnect() }
        }
        error("Слишком много перенаправлений")
    }
}

