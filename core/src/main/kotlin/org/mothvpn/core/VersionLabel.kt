package org.mothvpn.core

object VersionLabel {
    /** "0.4.6-beta.1" is shown as "0.4.6b1"; a release name such as "0.4.6" is shown as is. */
    fun of(versionName: String): String {
        val beta = Regex("^([0-9]+\\.[0-9]+\\.[0-9]+)-beta\\.([0-9]+)$").matchEntire(versionName)
        return if (beta != null) beta.groupValues[1] + "b" + beta.groupValues[2] else versionName
    }
}
