package org.mothvpn.core

/**
 * Tracks the underlying (non-VPN) default network from ConnectivityManager callbacks and decides when the tunnel
 * must react. Callbacks arrive in any order (new network available before the old one is lost, lost without a
 * replacement, link changes on the same handle); the result only depends on the latest state, so a debounce
 * can never drop a switch: [settle] compares the current state with the one applied last.
 */
class DefaultNetworkState {
    data class Snapshot(val handle: Long, val iface: String)
    @Volatile var current: Snapshot? = null; private set
    private var applied: Snapshot? = null
    private var appliedOnce = false

    /** The system picked [handle] as the best non-VPN network (requestNetwork / best-matching callback). */
    @Synchronized fun available(handle: Long, iface: String) { current = Snapshot(handle, iface) }
    /** Only the current network going away matters; losing an older one after a switch is ignored. */
    @Synchronized fun lost(handle: Long) { if (current?.handle == handle) current = null }
    /** Interface renamed or re-addressed on the current network (e.g. mobile data reattached). */
    @Synchronized fun linkChanged(handle: Long, iface: String) { if (current?.handle == handle) current = Snapshot(handle, iface) }

    enum class Action { NONE, LOST, SWITCHED }
    /** Called after the debounce: what changed since the last applied state. Marks the current state as applied. */
    @Synchronized fun settle(): Action {
        val now = current
        val before = applied
        val first = !appliedOnce
        applied = now; appliedOnce = true
        return when {
            now == before && !first -> Action.NONE
            now == null -> if (first) Action.NONE else Action.LOST
            first -> Action.NONE // initial state: the core was started on it
            else -> Action.SWITCHED
        }
    }
}

/** What to do with the running tunnel after the default network switched. */
object NetworkRecovery {
    enum class Step { RESET, RESTART }
    /** Xray keeps its own upstream connections that sing-box's reset does not close: restart the core. */
    fun afterSwitch(engine: String): Step = if (engine == "xray") Step.RESTART else Step.RESET
    /** Seconds to wait after a reset before checking that the internet works through the tunnel. */
    const val verifyAfterSeconds = 5L
    /** A failed check after the reset means stale connections or routes: restart the core on the same server. */
    fun afterVerify(healthOk: Boolean): Step? = if (healthOk) null else Step.RESTART
}

/** Wi-Fi and mobile data have separate server selections: which server to use after the network type changed while connected. */
object NetworkServerChoice {
    sealed class Decision {
        /** Stay on the active server (same type, or nothing selected for the new type); only reset connections. */
        object Keep : Decision()
        /** Switch the core to the server selected for the new network type. */
        data class Switch(val id: String) : Decision()
        /** Auto-select is on: measure the new network's list and use the fastest server. */
        object AutoSelect : Decision()
    }
    /**
     * [previousKind]/[newKind]: "wifi" or "mobile" of the real underlying network (not the VPN).
     * [manualPick]: the user picked a server by hand in this session, which pauses auto-select.
     */
    fun decide(previousKind: String, newKind: String, activeId: String, selectedForNew: String?, available: Set<String>,
               autoSelect: Boolean, manualPick: Boolean): Decision {
        if (previousKind == newKind) return Decision.Keep
        if (autoSelect && !manualPick && available.size > 1) return Decision.AutoSelect
        val target = selectedForNew?.takeIf { it.isNotBlank() && it in available } ?: return Decision.Keep
        return if (target == activeId) Decision.Keep else Decision.Switch(target)
    }
}
