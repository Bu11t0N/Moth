package org.mothvpn.core

object VersionLabel {
    /** The text of Settings → About ("Moth 0.4.6-beta.1 (11)"); the home screen shows the same without the build number. */
    fun about(appName: String, versionName: String, build: Int? = null): String =
        "$appName $versionName" + (build?.let { " ($it)" } ?: "")
}
