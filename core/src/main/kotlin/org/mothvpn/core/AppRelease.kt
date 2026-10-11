package org.mothvpn.core

import org.json.JSONObject

data class AppRelease(val version: String, val apkUrl: String, val apkName: String, val size: Long, val sumsUrl: String) {
    companion object {
        private fun version(value: String): List<Int> {
            require(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(value))
            return value.split('.').map(String::toInt)
        }
        /** [current] may be a test build such as 0.4.6-beta.1: it is older than the stable 0.4.6 and newer than 0.4.5. */
        fun newer(candidate: String, current: String): Boolean {
            val a = version(candidate); val b = version(current.substringBefore('-'))
            for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
            return current.contains('-')
        }
        fun parse(text: String, current: String, abis: List<String>): AppRelease? {
            val json = JSONObject(text)
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
            val tag = json.getString("tag_name")
            require(tag.startsWith("v"))
            val v = tag.removePrefix("v")
            if (!newer(v, current)) return null
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
