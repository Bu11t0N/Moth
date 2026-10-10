package org.routevpn.core

/**
 * VpnService.protect() returns false when no VPN is established, which is the normal state while probing servers.
 * A failed protect is fatal only while a tunnel is active (otherwise traffic could loop into the VPN).
 */
object ProtectPolicy {
    fun allows(protectSucceeded: Boolean, tunnelActive: Boolean): Boolean = protectSucceeded || !tunnelActive
}
