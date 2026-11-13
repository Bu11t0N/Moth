package org.mothvpn.core

/**
 * "Hide unavailable" on the Servers tab: a server whose last check failed stays hidden until the next
 * check updates that result. There is no time limit (earlier builds hid only for 15 minutes).
 * Unchecked servers, checks still running, and cancelled checks are never hidden, so they can be measured
 * and can reappear. This is a display filter only: a refresh still measures these hidden servers
 * (unlike the keyword filter and the search box, which also drop servers from the check).
 */
object HideUnavailable {
    fun failed(checking: Boolean, cancelled: Boolean, error: Boolean): Boolean =
        !checking && !cancelled && error
}
