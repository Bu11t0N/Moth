package org.mothvpn.core

import org.json.JSONObject

/** Developer JSON editor for a server's stored outbound. Errors name the problem only, never the content. */
object ServerEditor {
    const val MAX_LENGTH = 256 * 1024
    enum class Error { EMPTY, TOO_LARGE, NOT_OBJECT, NO_TYPE, NO_PROTOCOL }
    sealed class Result { data class Ok(val outbound: String) : Result(); data class Invalid(val error: Error) : Result() }

    fun pretty(p: Profile): String = runCatching { JSONObject(p.outbound).toString(2) }.getOrDefault(p.outbound)

    fun validate(text: String, engine: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Invalid(Error.EMPTY)
        if (t.length > MAX_LENGTH) return Result.Invalid(Error.TOO_LARGE)
        val o = (runCatching { org.json.JSONTokener(t).nextValue() }.getOrNull() as? JSONObject)?.takeIf { obj ->
            // Reject trailing garbage after the object.
            runCatching { val tk = org.json.JSONTokener(t); tk.nextValue(); !tk.more() }.getOrDefault(false) && obj.length() >= 0
        } ?: return Result.Invalid(Error.NOT_OBJECT)
        if (engine == "xray") {
            if (!o.optBoolean("group") && o.optString("protocol").isBlank()) return Result.Invalid(Error.NO_PROTOCOL)
        } else if (o.optString("type").isBlank()) return Result.Invalid(Error.NO_TYPE)
        return Result.Ok(o.toString())
    }

    /** Replaces the outbound of [profileId] in place (id, name and engine stay; favorites and selection follow the id). */
    fun apply(book: SubscriptionBook, profileId: String, outbound: String): SubscriptionBook = book.copy(sources = book.sources.map { s ->
        s.copy(profiles = s.profiles.map { if (it.id == profileId) it.copy(outbound = outbound, edited = true) else it })
    })
}
