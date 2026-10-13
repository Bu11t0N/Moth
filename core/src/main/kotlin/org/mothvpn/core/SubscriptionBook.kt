package org.mothvpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One subscription (or the local list of servers added from links): its own link, name, servers and provider info. */
data class SubscriptionSource(val id: String, val name: String, val url: String, val profiles: List<Profile>,
    val providerName: String = "", val expires: Long = 0, val usedBytes: Long = 0, val totalBytes: Long = 0, val updatedAt: Long = 0) {
    val local get() = url == SubscriptionRecord.LOCAL_URL
    /** Name to show: the user's name, else the provider's profile-title, else the host of the link. */
    fun title(): String = name.ifBlank { providerName }.ifBlank { runCatching { java.net.URI(url).host }.getOrNull().orEmpty() }
    fun json(): JSONObject = SubscriptionRecord(url, profiles, "", providerName = providerName, expires = expires, usedBytes = usedBytes,
        totalBytes = totalBytes, updatedAt = updatedAt).json().put("id", id).put("name", name).apply { remove("selected"); remove("wifiSelected"); remove("mobileSelected") }
    companion object {
        const val LOCAL_ID = "local"
        fun from(j: JSONObject): SubscriptionSource {
            val r = SubscriptionRecord.from(JSONObject(j.toString()).put("selected", ""))
            return SubscriptionSource(j.getString("id"), j.optString("name", ""), r.url, r.profiles, r.providerName, r.expires, r.usedBytes, r.totalBytes, r.updatedAt)
        }
        fun newId(url: String, now: Long): String = MessageDigest.getInstance("SHA-256").digest("$url|$now|${System.nanoTime()}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        const val MAX_NAME = 80
        fun validName(name: String) = name.length <= MAX_NAME && name.none { it.isISOControl() }
    }
}

/**
 * All saved subscriptions plus the selected server (shared, per network). Servers of all subscriptions form one list;
 * a server id found in several subscriptions belongs to the first one.
 * Stored as JSON with "version": 2 and "sources"; the flattened [record] fields are kept at the top level as well so an
 * older app version still finds its single-subscription format.
 */
data class SubscriptionBook(val sources: List<SubscriptionSource>, val selected: String = "", val wifiSelected: String = selected, val mobileSelected: String = selected) {
    val profiles: List<Profile> get() = sources.flatMap { it.profiles }.distinctBy { it.id }
    fun source(id: String) = sources.firstOrNull { it.id == id }
    /** The subscription a server belongs to (the first one that contains it). */
    fun sourceOf(profileId: String) = sources.firstOrNull { s -> s.profiles.any { it.id == profileId } }
    /** Selection fixed up so that each choice points at an existing server. */
    fun normalized(): SubscriptionBook {
        val ids = profiles.map { it.id }.toHashSet()
        val first = profiles.firstOrNull()?.id.orEmpty()
        val sel = selected.takeIf { it in ids } ?: first
        return copy(selected = sel, wifiSelected = wifiSelected.takeIf { it in ids } ?: sel, mobileSelected = mobileSelected.takeIf { it in ids } ?: sel)
    }
    /** The flattened single-list view used by the tunnel, the tile and the selection code; null when there are no servers. */
    fun record(): SubscriptionRecord? {
        val all = profiles
        if (all.isEmpty()) return null
        val n = normalized()
        val only = sources.singleOrNull()
        return SubscriptionRecord(only?.url ?: "", all, n.selected, n.wifiSelected, n.mobileSelected,
            only?.providerName.orEmpty(), only?.expires ?: 0, only?.usedBytes ?: 0, only?.totalBytes ?: 0, sources.maxOfOrNull { it.updatedAt } ?: 0)
    }
    /** Adds [source] or replaces the one with the same id (keeping its position). */
    fun upsert(source: SubscriptionSource): SubscriptionBook {
        val i = sources.indexOfFirst { it.id == source.id }
        val next = if (i < 0) sources + source else sources.toMutableList().apply { set(i, source) }
        return copy(sources = next).normalized()
    }
    fun remove(id: String): SubscriptionBook = copy(sources = sources.filter { it.id != id }).normalized()
    fun rename(id: String, name: String): SubscriptionBook = copy(sources = sources.map { if (it.id == id) it.copy(name = name.trim()) else it })
    fun withSelection(record: SubscriptionRecord): SubscriptionBook = copy(selected = record.selected, wifiSelected = record.wifiSelected, mobileSelected = record.mobileSelected).normalized()
    fun json(): JSONObject = (record()?.json() ?: JSONObject()).put("version", 2)
        .put("selected", selected).put("wifiSelected", wifiSelected).put("mobileSelected", mobileSelected)
        .put("sources", JSONArray().apply { sources.forEach { put(it.json()) } })
    companion object {
        const val MIGRATED_ID = "main"
        /** Reads both formats: version 2 ("sources") and the single subscription saved by 0.5.1 and earlier. */
        fun from(j: JSONObject): SubscriptionBook {
            val list = j.optJSONArray("sources") ?: return migrate(SubscriptionRecord.from(j))
            val sources = (0 until list.length()).map { SubscriptionSource.from(list.getJSONObject(it)) }.distinctBy { it.id }
            val sel = j.optString("selected", "")
            return SubscriptionBook(sources, sel, j.optString("wifiSelected", sel), j.optString("mobileSelected", sel)).normalized()
        }
        /** The old single subscription becomes the first entry; servers, selections and provider info are kept. */
        fun migrate(old: SubscriptionRecord): SubscriptionBook {
            val id = if (old.local) SubscriptionSource.LOCAL_ID else MIGRATED_ID
            val name = if (old.local) old.providerName else ""
            return SubscriptionBook(listOf(SubscriptionSource(id, name, old.url, old.profiles, if (old.local) "" else old.providerName,
                old.expires, old.usedBytes, old.totalBytes, old.updatedAt)), old.selected, old.wifiSelected, old.mobileSelected).normalized()
        }
    }
}

/** Server list sections: favorites first (across all subscriptions), then one group per subscription in the saved order. */
object ServerGroups {
    data class Group(val sourceId: String?, val profiles: List<Profile>)
    /** [ordered]: the visible servers in display order. Each server appears once. Empty groups are dropped. */
    fun of(book: SubscriptionBook, ordered: List<Profile>, favorites: Collection<String>): List<Group> {
        val fav = favorites.toHashSet()
        val owner = HashMap<String, String>()
        book.sources.forEach { s -> s.profiles.forEach { owner.putIfAbsent(it.id, s.id) } }
        val out = mutableListOf<Group>()
        ordered.filter { it.id in fav }.takeIf { it.isNotEmpty() }?.let { out += Group(null, it) }
        val rest = ordered.filter { it.id !in fav }
        book.sources.forEach { s -> rest.filter { owner[it.id] == s.id }.takeIf { it.isNotEmpty() }?.let { out += Group(s.id, it) } }
        return out
    }
}
