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
        assertTrue(AppRelease.newer("0.4.6-beta.1", "0.4.5"))
        assertTrue(AppRelease.newer("0.4.6-beta.2", "0.4.6-beta.1"))
        assertTrue(AppRelease.newer("0.4.6-beta.10", "0.4.6-beta.9"))
        assertFalse(AppRelease.newer("0.4.6-beta.2", "0.4.6"))
        assertFalse(AppRelease.newer("0.4.6-beta.1", "0.4.6-beta.1"))
        assertTrue(AppRelease.newer("0.4.7-beta.1", "0.4.6"))
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

    private fun entry(tag: String, prerelease: Boolean, draft: Boolean = false, name: String = "Moth-${tag.removePrefix("v")}-arm64-v8a.apk"): JSONObject {
        fun asset(n: String) = JSONObject().put("name", n).put("size", 1234)
            .put("browser_download_url", "https://github.com/Bu11t0N/Moth/releases/download/$tag/$n")
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("assets", JSONArray().put(asset(name)).put(asset("SHA256SUMS.txt")))
    }
    private fun list(vararg e: JSONObject) = JSONArray(e.toList()).toString()
    private val abis = listOf("arm64-v8a")

    @Test fun releaseChannelOnBetaBuildGetsOnlyNewerOrFinalOfSameVersion() {
        assertEquals("0.4.6", AppRelease.parse(release().toString(), "0.4.6-beta.2", abis)!!.version)
        assertNull(AppRelease.parse(release().put("tag_name", "v0.4.5").toString(), "0.4.6-beta.1", abis))
        assertFails { AppRelease.parse(entry("v0.4.7-beta.1", false).toString(), "0.4.5", abis) }
    }
    @Test fun betaChannelPicksNewestOfEitherKind() {
        val all = list(entry("v0.4.6-beta.1", true), entry("v0.4.5", false), entry("v0.4.6-beta.2", true), entry("v0.4.4-rc1", true), entry("v0.4.4", false))
        assertEquals("0.4.6-beta.2", AppRelease.parseList(all, "0.4.5", abis)!!.version)
        assertEquals("0.4.6-beta.2", AppRelease.parseList(all, "0.4.6-beta.1", abis)!!.version)
        assertNull(AppRelease.parseList(all, "0.4.6-beta.2", abis))
        assertNull(AppRelease.parseList(all, "0.4.6", abis))
        val withFinal = list(entry("v0.4.6-beta.2", true), entry("v0.4.6", false), entry("v0.4.5", false))
        assertEquals("0.4.6", AppRelease.parseList(withFinal, "0.4.6-beta.2", abis)!!.version)
        assertEquals("0.4.6", AppRelease.parseList(withFinal, "0.4.5", abis)!!.version)
    }
    @Test fun betaChannelSkipsDraftsAndBadTagsButKeepsSafetyChecks() {
        assertNull(AppRelease.parseList(list(entry("v0.9.0", false, draft = true), entry("vfoo", false), entry("v0.4.5", false)), "0.4.5", abis))
        assertEquals("0.4.5", AppRelease.parseList(list(entry("v0.9.0", false, draft = true), entry("v0.4.5", false)), "0.4.4", abis)!!.version)
        assertNull(AppRelease.parseList("[]", "0.4.5", abis))
        val foreign = entry("v0.4.6-beta.2", true).apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/a.apk") }
        assertFails { AppRelease.parseList(list(foreign), "0.4.5", abis) }
        assertFails { AppRelease.parseList(list(entry("v0.4.6-beta.2", true)), "0.4.5", listOf("x86")) }
        assertFails { AppRelease.parseList(list(entry("v0.4.6-beta.2", true, name = "Moth-0.4.6-beta.2-arm64-v8a.apk").apply { getJSONArray("assets").remove(1) }), "0.4.5", abis) }
    }
    @Test fun channelDefaultsFollowTheInstalledBuild() {
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.effective("", "0.4.6"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.effective("", "0.4.6-beta.1"))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.effective("release", "0.4.6-beta.1"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.effective("beta", "0.4.6"))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.effective("junk", "0.4.6"))
    }
}
