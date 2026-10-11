package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject

/** Update channel: RELEASE = stable versions only, BETA = stable and test builds (newest wins). */
enum class UpdateChannel(val id: String) {
    RELEASE("release"), BETA("beta");
    companion object {
        /** [stored] is the saved choice ("" = never chosen): then a test build defaults to BETA, everything else to RELEASE. */
        fun effective(stored: String, installed: String): UpdateChannel =
            values().firstOrNull { it.id == stored } ?: if (AppVersion.parse(installed)?.beta != null) BETA else RELEASE
    }
}

/** major.minor.patch with an optional "-beta.N" suffix; a beta is older than the final of the same version. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val a = listOf(major, minor, patch, beta ?: Int.MAX_VALUE)
        val b = listOf(other.major, other.minor, other.patch, other.beta ?: Int.MAX_VALUE)
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
    companion object {
        private val format = Regex("([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})(?:-beta\\.([0-9]{1,4}))?")
        fun parse(value: String): AppVersion? = format.matchEntire(value)?.let {
            AppVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].takeIf(String::isNotEmpty)?.toInt())
        }
    }
}

data class AppRelease(val version: String, val apkUrl: String, val apkName: String, val size: Long, val sumsUrl: String) {
    companion object {
        /** True when [candidate] is strictly newer than [current] (0.4.6-beta.1 < 0.4.6-beta.2 < 0.4.6). Both must be well-formed. */
        fun newer(candidate: String, current: String): Boolean {
            val a = requireNotNull(AppVersion.parse(candidate)) { "Bad version" }
            val b = requireNotNull(AppVersion.parse(current)) { "Bad version" }
            return a > b
        }

        /** Release channel: the body of releases/latest. Prereleases and test tags are refused. */
        fun parse(text: String, current: String, abis: List<String>): AppRelease? {
            val json = JSONObject(text)
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
            val tag = json.getString("tag_name")
            require(tag.startsWith("v"))
            val v = requireNotNull(AppVersion.parse(tag.removePrefix("v"))) { "Bad tag" }
            require(v.beta == null) { "Test build on the stable channel" }
            if (!newer(tag.removePrefix("v"), current)) return null
            return build(json, tag, abis)
        }

        /**
         * Beta channel: the body of the releases list. Drafts and tags of another format (e.g. v0.4.4-rc1) are skipped;
         * the newest remaining version wins. It is offered only when newer than [current]; its assets are checked exactly as for a stable release.
         */
        fun parseList(text: String, current: String, abis: List<String>): AppRelease? {
            val array = JSONArray(text)
            val candidates = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { json ->
                if (json.optBoolean("draft")) return@mapNotNull null
                val tag = json.optString("tag_name")
                if (!tag.startsWith("v")) return@mapNotNull null
                AppVersion.parse(tag.removePrefix("v"))?.let { Triple(it, tag, json) }
            }
            val best = candidates.maxByOrNull { it.first } ?: return null
            if (!newer(best.second.removePrefix("v"), current)) return null
            return build(best.third, best.second, abis)
        }

        private fun build(json: JSONObject, tag: String, abis: List<String>): AppRelease {
            val v = tag.removePrefix("v")
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
            val name = abis.map { "Moth-$v-$it.apk" }.firstOrNull { byName.containsKey(it) } ?: error("No compatible APK")
            fun url(asset: JSONObject, expected: String): String {
                val url = asset.getString("browser_download_url")
                require(url == "https://github.com/Bu11t0N/Moth/releases/download/$tag/$expected")
                return url
            }
            val apk = byName.getValue(name)
            val size = apk.getLong("size")
            require(size in 1..200L * 1024 * 1024)
            return AppRelease(v, url(apk, name), name, size, url(byName.getValue("SHA256SUMS.txt"), "SHA256SUMS.txt"))
        }
        fun checksum(text: String, name: String): String {
            val matches = text.lineSequence().mapNotNull { line ->
                Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
            }.filter { it.groupValues[2] == name }.toList()
            require(matches.size == 1) { "Missing or ambiguous APK checksum" }
            return matches.single().groupValues[1].lowercase()
        }
    }
}
