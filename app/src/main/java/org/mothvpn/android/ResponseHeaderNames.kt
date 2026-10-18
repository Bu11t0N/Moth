package org.mothvpn.android

import android.content.Context
import java.io.File

/**
 * Developer diagnostic: the NAMES of the response headers (and of `#name:` body lines) the provider sent with the last update of each
 * subscription, so a missing feature (for example the logo) can be traced to what the provider actually sends.
 * Names only: values and URLs are never stored, shown or logged. Kept in noBackupFilesDir, removed with the subscription.
 */
object ResponseHeaderNames {
    data class Names(val headers: List<String>, val body: List<String>)
    private const val MAX = 80

    private fun dir(context: Context) = File(context.noBackupFilesDir, "sub-headers").apply { mkdirs() }
    private fun file(context: Context, sourceId: String) = File(dir(context), "${sourceId.filter { it.isLetterOrDigit() }.take(32)}.txt")

    /** Header names as a name list: lowercase, letters/digits/'-'/'_' only, without the status line (null key). */
    fun clean(names: Collection<String?>): List<String> = names.filterNotNull().map { it.trim().lowercase() }
        .filter { n -> n.isNotEmpty() && n.length <= 64 && n.all { it.isLetterOrDigit() || it == '-' || it == '_' } }.distinct().sorted().take(MAX)

    fun save(context: Context, sourceId: String, names: Names) {
        runCatching {
            file(context, sourceId).writeText("h:" + names.headers.joinToString(",") + "\nb:" + names.body.joinToString(","))
        }.onFailure { Logger.w("Headers", "saving header names failed: ${it.javaClass.simpleName}") }
    }

    fun load(context: Context, sourceId: String): Names? = runCatching {
        val f = file(context, sourceId); if (!f.exists()) return null
        val lines = f.readLines()
        fun part(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        Names(clean(part("h:")), clean(part("b:")))
    }.getOrNull()

    fun remove(context: Context, sourceId: String) { file(context, sourceId).delete() }
}
