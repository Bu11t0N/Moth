package org.mothvpn.android

import android.content.Context
import java.io.File

/**
 * Certificate authorities trusted by the cores: Android's built-in CAs plus the ones the user installed
 * (Settings → Security → Encryption & credentials), like the official sing-box app.
 * - sing-box gets them through PlatformInterface.systemCertificates().
 * - Xray (Go's standard TLS roots) reads them from the PEM file named by SSL_CERT_FILE, which must be set
 *   before the Go runtime starts (Application.onCreate); Go also keeps reading /system/etc/security/cacerts.
 */
object TrustedCertificates {
    private const val FILE = "trusted-ca.pem"
    fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    /** PEM blocks of every enabled system CA ("system:" aliases) and user CA ("user:" aliases) from AndroidCAStore. */
    fun pems(): List<String> = runCatching {
        val store = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        store.aliases().toList().filter { it.startsWith("system:") || it.startsWith("user:") }.mapNotNull { alias ->
            (store.getCertificate(alias) as? java.security.cert.X509Certificate)?.encoded?.let { der ->
                "-----BEGIN CERTIFICATE-----\n" + android.util.Base64.encodeToString(der, android.util.Base64.DEFAULT) + "-----END CERTIFICATE-----\n"
            }
        }
    }.onFailure { Logger.w("Core", "reading trusted certificates failed", it) }.getOrDefault(emptyList())

    fun userCount(): Int = runCatching {
        java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }.aliases().toList().count { it.startsWith("user:") }
    }.getOrDefault(0)

    /** Points Go's TLS roots at the bundle. Call before anything loads libbox. */
    fun exportPath(context: Context) {
        runCatching { android.system.Os.setenv("SSL_CERT_FILE", file(context).absolutePath, true) }.onFailure { Logger.w("Core", "setting SSL_CERT_FILE failed", it) }
    }

    /** (Re)writes the bundle; Go reads it on the first TLS handshake of the process. */
    fun write(context: Context) {
        val pems = pems()
        if (pems.isEmpty()) return
        runCatching {
            val target = file(context); val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeText(pems.joinToString(""))
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            Logger.d("Core", "trusted certificates: ${pems.size} (user-installed: ${userCount()})")
        }.onFailure { Logger.w("Core", "writing trusted certificates failed", it) }
    }
}
