package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class AppReleaseTest {
    private fun release(): JSONObject {
        fun asset(name: String) = JSONObject().put("name", name).put("size", 1234)
            .put("browser_download_url", "https://github.com/Bu11t0N/Moth/releases/download/v0.4.6/$name")
        return JSONObject().put("tag_name", "v0.4.6").put("draft", false).put("prerelease", false)
            .put("assets", JSONArray().put(asset("Moth-0.4.6-arm64-v8a.apk")).put(asset("SHA256SUMS.txt")))
    }
    @Test fun versionsAreComparedNumericallyWithoutDowngrades() {
        assertTrue(AppRelease.newer("0.4.10", "0.4.9"))
        assertTrue(AppRelease.newer("0.5.0", "0.4.99"))
        assertFalse(AppRelease.newer("0.4.5", "0.4.5"))
        assertFalse(AppRelease.newer("0.4.4", "0.4.5"))
        assertFails { AppRelease.newer("0.4.6-beta.1", "0.4.5") }
        assertTrue(AppRelease.newer("0.4.6", "0.4.6-beta.1"))
        assertTrue(AppRelease.newer("0.4.7", "0.4.6-beta.1"))
        assertFalse(AppRelease.newer("0.4.5", "0.4.6-beta.1"))
    }
    @Test fun selectsMatchingArchitectureAndStableNewRelease() {
        val result = AppRelease.parse(release().toString(), "0.4.5", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("Moth-0.4.6-arm64-v8a.apk", result.apkName)
        assertNull(AppRelease.parse(release().toString(), "0.4.6", listOf("arm64-v8a")))
        assertFails { AppRelease.parse(release().toString(), "0.4.5", listOf("x86")) }
        assertFails { AppRelease.parse(release().put("prerelease", true).toString(), "0.4.5", listOf("arm64-v8a")) }
    }
    @Test fun refusesForeignUrlsMissingChecksumsAndOversizedAssets() {
        val foreign = release().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk") }
        assertFails { AppRelease.parse(foreign.toString(), "0.4.5", listOf("arm64-v8a")) }
        val missing = release().apply { getJSONArray("assets").remove(1) }
        assertFails { AppRelease.parse(missing.toString(), "0.4.5", listOf("arm64-v8a")) }
        val huge = release().apply { getJSONArray("assets").getJSONObject(0).put("size", Long.MAX_VALUE) }
        assertFails { AppRelease.parse(huge.toString(), "0.4.5", listOf("arm64-v8a")) }
    }
    @Test fun checksumsMustMatchOneExactFilename() {
        val hash = "a".repeat(64)
        assertEquals(hash, AppRelease.checksum("$hash  Moth-0.4.6-arm64-v8a.apk", "Moth-0.4.6-arm64-v8a.apk"))
        assertFails { AppRelease.checksum("$hash  other.apk", "Moth-0.4.6-arm64-v8a.apk") }
        assertFails { AppRelease.checksum("$hash  app.apk\n$hash  app.apk", "app.apk") }
        assertFails { AppRelease.checksum("short app.apk", "app.apk") }
    }
}
