package org.mothvpn.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-file backup of everything: settings (favorites, routing rules, HWID included) and all subscriptions with their servers
 * and per-network choices. JSON, versioned: {"format":"moth-backup","version":1,...}. Optionally encrypted with a password
 * (AES-256-GCM, key from PBKDF2-HMAC-SHA256); the envelope then carries only the KDF parameters and the ciphertext.
 */
object Backup {
    const val FORMAT = "moth-backup"
    const val VERSION = 1
    const val ITERATIONS = 210_000
    const val MAX_BYTES = 8 * 1024 * 1024
    data class Content(val settings: ClientSettings, val book: SubscriptionBook, val appVersion: String = "", val createdAt: Long = 0)
    data class Summary(val subscriptions: Int, val servers: Int, val localServers: Int, val favorites: Int, val rules: Int)
    class WrongPassword : Exception("Неверный пароль или повреждённый файл")
    class PasswordRequired : Exception("Файл защищён паролем")

    fun summary(c: Content) = Summary(c.book.sources.count { !it.local }, c.book.profiles.size,
        c.book.source(SubscriptionSource.LOCAL_ID)?.profiles?.size ?: 0, c.settings.favorites.size, c.settings.routingRules.size)

    private fun payload(c: Content) = JSONObject().put("appVersion", c.appVersion).put("createdAt", c.createdAt)
        .put("settings", c.settings.json()).put("subscriptions", c.book.json())

    /** [password]: null or empty for a plain file. */
    fun export(c: Content, password: String?, random: SecureRandom = SecureRandom()): String {
        val head = JSONObject().put("format", FORMAT).put("version", VERSION)
        if (password.isNullOrEmpty()) return head.put("encrypted", false).put("payload", payload(c)).toString(2)
        val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv)) }
        cipher.updateAAD(aad(VERSION))
        val data = cipher.doFinal(payload(c).toString().toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return head.put("encrypted", true).put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(data)).toString(2)
    }

    /** Whether [text] is an encrypted backup (so the screen can ask for the password first). */
    fun encrypted(text: String): Boolean = runCatching { header(text).optBoolean("encrypted") }.getOrDefault(false)

    /** Reads and validates a backup. Settings are validated, the subscription list may be in either store format. */
    fun read(text: String, password: String? = null): Content {
        val j = header(text)
        val version = j.optInt("version", -1)
        val p = if (j.optBoolean("encrypted")) {
            if (password.isNullOrEmpty()) throw PasswordRequired()
            require(j.optString("kdf") == "PBKDF2WithHmacSHA256") { "Неизвестный формат резервной копии" }
            val iterations = j.getInt("iterations").also { require(it in 10_000..5_000_000) { "Неизвестный формат резервной копии" } }
            val b64 = Base64.getDecoder()
            val salt = b64.decode(j.getString("salt")); val iv = b64.decode(j.getString("iv"))
            require(salt.size in 8..64 && iv.size == 12) { "Неизвестный формат резервной копии" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv)) }
            cipher.updateAAD(aad(version))
            val plain = try { cipher.doFinal(b64.decode(j.getString("data"))) } catch (e: javax.crypto.AEADBadTagException) { throw WrongPassword() }
            JSONObject(String(plain, Charsets.UTF_8))
        } else j.getJSONObject("payload")
        val settings = ClientSettings.from(p.getJSONObject("settings")).validate()
        val book = p.optJSONObject("subscriptions")?.takeIf { it.has("sources") || it.has("profiles") }?.let { SubscriptionBook.from(it) } ?: SubscriptionBook(emptyList())
        return Content(settings, book, p.optString("appVersion", ""), p.optLong("createdAt"))
    }

    private fun header(text: String): JSONObject {
        require(text.length <= MAX_BYTES) { "Файл слишком большой" }
        val j = runCatching { JSONObject(text.removePrefix("\uFEFF")) }.getOrNull() ?: throw IllegalArgumentException("Это не резервная копия Moth")
        require(j.optString("format") == FORMAT) { "Это не резервная копия Moth" }
        val version = j.optInt("version", -1)
        require(version in 1..VERSION) { "Резервная копия создана более новой версией Moth" }
        return j
    }
    private fun aad(version: Int) = "$FORMAT/$version".toByteArray()
    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
}
