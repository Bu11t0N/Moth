package org.mothvpn.core

/**
 * The home screen's moth button while the VPN is off and a server check (ping) runs: the moth flaps in the off colours, the status says
 * servers are being checked, and a tap stops the check instead of connecting. While connected, a check makes the moth flap in the
 * connected (lit) colours; the status and the tap (disconnect) stay. Connecting, disconnecting or blocked: a check does not change the button.
 */
object HomeButton {
    enum class Tap { CONNECT, DISCONNECT, STOP_CHECKS }

    /** [running]: connected or blocked by the kill switch; [transition]: connecting or disconnecting. */
    fun pinging(probing: Boolean, running: Boolean, transition: Boolean): Boolean = probing && !running && !transition

    /** [on]: connected (not blocked). A check runs while connected: the connecting wing beat in the lit colours. */
    fun checkingOn(probing: Boolean, on: Boolean, transition: Boolean): Boolean = probing && on && !transition

    /** What a tap does, decided from the live state at the moment of the tap (a check never turns into a connection). */
    fun tap(probing: Boolean, running: Boolean, transition: Boolean): Tap = when {
        running -> Tap.DISCONNECT
        pinging(probing, running, transition) -> Tap.STOP_CHECKS
        else -> Tap.CONNECT
    }
}
