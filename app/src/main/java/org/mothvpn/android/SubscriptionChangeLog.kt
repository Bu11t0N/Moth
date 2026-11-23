package org.mothvpn.android

import android.content.Context
import org.mothvpn.core.SubscriptionChanges

/**
 * Stored change history of every subscription ([SubscriptionChanges]), in the app's encrypted storage. Recorded on each update that
 * changed provider content; read only by the developer option "Subscription changes". Its content is never logged.
 */
object SubscriptionChangeLog {
    private val lock = Any()
    private fun store(context: Context) = SubscriptionStore(context, "subscription-changes.enc")
    fun all(context: Context): Map<String, List<SubscriptionChanges.Entry>> = synchronized(lock) {
        runCatching { store(context).readJson()?.let(SubscriptionChanges::fromJson) }.onFailure { Logger.w("Changes", "history unreadable, starting over") }.getOrNull().orEmpty()
    }
    fun of(context: Context, sourceId: String): List<SubscriptionChanges.Entry> = all(context)[sourceId].orEmpty()
    fun record(context: Context, sourceId: String, entry: SubscriptionChanges.Entry) = synchronized(lock) {
        runCatching {
            val all = all(context)
            store(context).writeJson(SubscriptionChanges.toJson(all + (sourceId to SubscriptionChanges.append(all[sourceId].orEmpty(), entry))))
        }.onFailure { Logger.w("Changes", "history not saved", it) }
    }
    fun remove(context: Context, sourceId: String) = synchronized(lock) {
        runCatching { val all = all(context); if (sourceId in all) store(context).writeJson(SubscriptionChanges.toJson(all - sourceId)) }
    }
}
