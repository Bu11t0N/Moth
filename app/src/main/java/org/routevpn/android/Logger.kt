package org.routevpn.android

import android.content.Context
import org.routevpn.core.LogRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) { OFF("OFF"), ERROR("E"), WARN("W"), INFO("I"), DEBUG("D"), VERBOSE("V") }

/**
 * Developer log. Rotating plain-text file in filesDir (about 512 KB in total).
 * Callers must pass only non-sensitive facts; every message and exception text is additionally redacted.
 * Never log subscription URLs, keys, UUIDs, passwords or server addresses.
 */
object Logger {
    private const val MAX_CURRENT = 256 * 1024L
    private val lock = Any()
    @Volatile private var cached: LogLevel? = null
    private val app get() = MothApplication.instance
    private val prefs get() = app.getSharedPreferences("logging", Context.MODE_PRIVATE)
    private val current get() = File(app.filesDir, "moth-log.txt")
    private val previous get() = File(app.filesDir, "moth-log.1.txt")
    private val time = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    var level: LogLevel
        get() = cached ?: (runCatching { LogLevel.valueOf(prefs.getString("level", "OFF") ?: "OFF") }.getOrDefault(LogLevel.OFF)).also { cached = it }
        set(value) { cached = value; prefs.edit().putString("level", value.name).apply(); i("Logger", "log level set to ${value.name}", force = true) }

    fun enabled(l: LogLevel) = level != LogLevel.OFF && l != LogLevel.OFF && l.ordinal <= level.ordinal
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null, force: Boolean = false) = log(LogLevel.INFO, tag, msg, t, force)
    fun d(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.DEBUG, tag, msg, t)
    fun v(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.VERBOSE, tag, msg, t)

    /** Class name, redacted message, top stack frames and the cause chain (also for Error/UnsatisfiedLinkError). */
    fun describe(t: Throwable, frames: Int = 6): String {
        val sb = StringBuilder()
        var c: Throwable? = t; var depth = 0
        while (c != null && depth < 4) {
            if (depth > 0) sb.append(" | caused by ")
            sb.append(c.javaClass.name).append(": ").append(LogRedactor.redact(c.message))
            c.stackTrace.take(frames).forEach { sb.append(" @ ").append(it.className).append('.').append(it.methodName).append('(').append(it.fileName ?: "?").append(':').append(it.lineNumber).append(')') }
            c = c.cause?.takeIf { it !== c }; depth++
        }
        return sb.toString()
    }

    private fun log(l: LogLevel, tag: String, msg: String, t: Throwable?, force: Boolean = false) {
        if (!force && !enabled(l)) return
        runCatching {
            val line = "${time.get()!!.format(Date())} ${l.label}/$tag [${Thread.currentThread().name}] ${LogRedactor.redact(msg)}" +
                (t?.let { " :: " + describe(it) } ?: "") + "\n"
            synchronized(lock) {
                if (current.length() > MAX_CURRENT) { previous.delete(); current.renameTo(previous) }
                current.appendText(line)
            }
        }
    }

    fun read(): String = synchronized(lock) {
        (if (previous.exists()) previous.readText() else "") + (if (current.exists()) current.readText() else "")
    }
    fun clear() = synchronized(lock) { previous.delete(); current.delete() }
    fun header() = "Moth ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.RELEASE}/API ${android.os.Build.VERSION.SDK_INT} " +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")} level=${level.name}\n"

    /** File for the share sheet (served by FileProvider from cacheDir/logs). */
    fun shareFile(): File {
        val dir = File(app.cacheDir, "logs").apply { mkdirs() }
        return File(dir, "moth-log.txt").also { it.writeText(header() + read()) }
    }
}
