package org.mothvpn.core

/**
 * Order of the servers list: as in the subscription (default for new installs: the provider's order, subscriptions in
 * their saved order), by name (case-insensitive, natural: "Server 2" before "Server 10") or by measured ping.
 */
object ServerOrder {
    const val SUBSCRIPTION = "subscription"
    const val NAME = "name"
    const val LATENCY = "latency"
    val all = setOf(SUBSCRIPTION, NAME, LATENCY)

    /** Drops everything before the first letter or digit: flags (regional indicator pairs, the EU flag), emoji with ZWJ and variation selectors, spaces, punctuation. */
    fun sortKey(name: String): String {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) break
            i += Character.charCount(cp)
        }
        return name.substring(i)
    }

    /** Natural, case-insensitive comparison of the names without leading emoji/flags; equal keys are ordered by the full name. */
    val naturalName: Comparator<String> = Comparator { a, b ->
        val c = natural.compare(sortKey(a), sortKey(b))
        if (c != 0) c else natural.compare(a, b).takeIf { it != 0 } ?: a.compareTo(b)
    }

    /** Case-insensitive natural comparison: digit runs compare as numbers. */
    private val natural: Comparator<String> = Comparator { a, b ->
        val x = chunks(a); val y = chunks(b)
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val s = p.trimStart('0'); val t = q.trimStart('0')
                if (s.length != t.length) s.length.compareTo(t.length) else s.compareTo(t)
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.size.compareTo(y.size)
    }
    private fun chunks(s: String): List<String> = Regex("[0-9]+|[^0-9]+").findAll(s.lowercase()).map { it.value }.toList()

    /**
     * [items] come in subscription order. SUBSCRIPTION keeps it; NAME sorts by [naturalName];
     * LATENCY sorts by ascending ping with unmeasured servers last, ties keep the subscription order.
     */
    fun <T> sort(items: List<T>, mode: String, name: (T) -> String, ping: (T) -> Long?): List<T> = when (mode) {
        NAME -> items.sortedWith(compareBy(naturalName, name))
        LATENCY -> items.sortedBy { ping(it) ?: Long.MAX_VALUE } // sortedBy is stable
        else -> items
    }
}

/** Probe queue order: the servers as shown in the list (top first), then any servers not shown (hidden or filtered out) in their saved order. */
object ProbeOrder {
    fun <T> arrange(items: List<T>, shownIds: List<String>, id: (T) -> String): List<T> {
        if (shownIds.isEmpty()) return items
        val position = HashMap<String, Int>()
        shownIds.forEachIndexed { i, s -> position.putIfAbsent(s, i) }
        return items.withIndex().sortedWith(compareBy({ position[id(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }
}

/**
 * Which servers a check measures, fixed once when it starts. A check of all servers or of one subscription covers only the
 * servers the page's keyword filter shows (hidden servers are never measured); [only], when given (a subscription, or the
 * list the screen showed when Check was tapped), narrows that set further. [id] is a single server. Changing the filter
 * while a check runs never adds servers to it: callers compute this list once and keep it.
 */
object ProbePlan {
    fun targets(profiles: List<Profile>, settings: ClientSettings, kind: String, id: String? = null, only: Set<String> = emptySet()): List<Profile> {
        if (id != null) return profiles.filter { it.id == id }
        val pool = ServerFilter.pool(profiles, settings, kind)
        return if (only.isEmpty()) pool else pool.filter { it.id in only }
    }

    /** Candidates for auto-select and failover: the servers the filter shows, never balancer groups (they can only be picked by hand). */
    fun autoPool(profiles: List<Profile>, settings: ClientSettings, kind: String): List<Profile> =
        ServerFilter.pool(profiles, settings, kind).filterNot(BalancerPing::isGroup)
}
