package org.mothvpn.core

/** Order of the servers list: by name (default, case-insensitive, natural: "Server 2" before "Server 10") or by measured ping. */
object ServerOrder {
    const val NAME = "name"
    const val LATENCY = "latency"
    val all = setOf(NAME, LATENCY)

    /** Case-insensitive natural comparison: digit runs compare as numbers. */
    val naturalName: Comparator<String> = Comparator { a, b ->
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

    /** Name order, or ascending ping with unmeasured servers last; ties keep name order. */
    fun <T> sort(items: List<T>, mode: String, name: (T) -> String, ping: (T) -> Long?): List<T> {
        val byName = items.sortedWith(compareBy(naturalName, name))
        return if (mode == LATENCY) byName.sortedBy { ping(it) ?: Long.MAX_VALUE } else byName // sortedBy is stable
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
