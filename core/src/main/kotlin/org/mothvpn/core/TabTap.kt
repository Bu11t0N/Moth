package org.mothvpn.core

/**
 * A tap on the bottom navigation bar. Tapping Settings always shows the Settings main list: from a subpage of Settings (any depth)
 * it goes back to the root, and coming from another tab never restores an old subpage. [rootRequest] is a counter the Settings
 * screen watches: each increment resets it to the root and closes its sheets and dialogs.
 */
object TabTap {
    const val HOME = 0
    const val SERVERS = 1
    const val SETTINGS = 2

    data class Result(val tab: Int, val rootRequest: Int)

    fun tap(tapped: Int, rootRequest: Int): Result = Result(tapped, if (tapped == SETTINGS) rootRequest + 1 else rootRequest)
}
