package org.mothvpn.core

/**
 * Picking another server while the VPN is connected (failover after failed health checks, auto-select after a network change).
 * The measurement always runs with the tunnel UP on the current server, even a dead one: the probes protect their sockets, so
 * they bypass the tunnel, and nothing is torn down (no leak, no "VPN off" gap) until a live server is known.
 */
object Reselect {
    enum class Reason { FAILOVER, RESELECT }
    sealed class Decision {
        /** Restart on [id], a server that just answered (for [Reason.FAILOVER] it may be the current server: its core is restarted). */
        data class Switch(val id: String) : Decision()
        /** The current server is fine (it is the best live one, or it recovered meanwhile): nothing to do. */
        object Keep : Decision()
        /** No server answered: stay on the current server with the tunnel up and measure again later. */
        object Stay : Decision()
    }

    /**
     * [alive]: servers that answered this measurement, fastest first (never stale or failed results).
     * [recovered]: the current server passed a health check while the measurement ran.
     */
    fun decide(reason: Reason, current: String, alive: List<String>, recovered: Boolean = false): Decision {
        if (reason == Reason.FAILOVER && recovered) return Decision.Keep
        return when (reason) {
            // The current server failed its checks through the tunnel: prefer another live server; if only the current one
            // answers the probe, its core is stale (restart it). Nothing alive: stay.
            Reason.FAILOVER -> alive.firstOrNull { it != current }?.let { Decision.Switch(it) }
                ?: if (current in alive) Decision.Switch(current) else Decision.Stay
            Reason.RESELECT -> when (val best = alive.firstOrNull()) { null -> Decision.Stay; current -> Decision.Keep; else -> Decision.Switch(best) }
        }
    }

    /**
     * Whether failed health checks start a failover. The check goes through the tunnel, so it is the same for a plain server and a
     * balancer group (a group whose members all stopped answering fails it like a dead server). A server picked by hand is kept
     * while it answers (no reshuffle, see [NetworkServerChoice.decide]), but once it is dead failover runs as for any server.
     * Auto-select off: nothing ever switches.
     */
    fun failoverDue(autoSelect: Boolean, failures: Int, after: Int): Boolean = autoSelect && failures >= after

    /**
     * Failover first re-checks a few servers that ranked best last time (one batch, a few seconds) before measuring all of them.
     * A switch still needs a fresh answer: an old ranking alone never decides.
     */
    fun quickCandidates(ranking: List<String>, current: String, tried: Set<String>, pool: Collection<String>, max: Int): List<String> =
        ranking.filter { it != current && it !in tried && it in pool }.take(max.coerceAtLeast(1))

    /** Pause before measuring again after a round found no live server: 30 s, 60 s, 2 min, 4 min, then every 5 min. */
    fun backoffMs(emptyRounds: Int): Long = if (emptyRounds <= 0) 0 else minOf(30_000L shl (emptyRounds - 1).coerceAtMost(4), 300_000L)
    fun mayMeasure(now: Long, notBefore: Long): Boolean = now >= notBefore

    enum class StartPlan {
        /** No measurement: (re)build the tunnel on the requested server. */
        CONNECT,
        /** No working tunnel to keep (off, error, kill-switch block): measure, then build the tunnel on the fastest live server. */
        MEASURE_THEN_CONNECT,
        /** A tunnel is up: measure while it keeps running; replace it only with a live server, otherwise keep it. */
        MEASURE_WHILE_UP,
    }
    /** How a start runs. With a tunnel up it is never closed before the measurement. */
    fun startPlan(tunnelUp: Boolean, autoSelect: Boolean, pickBest: Boolean, servers: Int): StartPlan = when {
        !autoSelect || !pickBest || servers <= 1 -> StartPlan.CONNECT
        tunnelUp -> StartPlan.MEASURE_WHILE_UP
        else -> StartPlan.MEASURE_THEN_CONNECT
    }

    /** Only a fresh, finished, successful result on the current network counts as alive. */
    fun alive(latencyMs: Long?, checking: Boolean, cancelled: Boolean, sameNetwork: Boolean, checkedAt: Long, measuredFrom: Long): Boolean =
        latencyMs != null && !checking && !cancelled && sameNetwork && checkedAt >= measuredFrom
}
