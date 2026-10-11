package org.mothvpn.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.mothvpn.core.LogRedactor
import org.mothvpn.core.LogText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Crash log: written regardless of the selected log level, always redacted, rotated at about 256 KB in total. */
object CrashLog {
    private const val MAX_CURRENT = 128 * 1024L
    private const val TRACE_LIMIT = 16 * 1024
    private val lock = Any()
    private val app get() = MothApplication.instance
    private val current get() = File(app.filesDir, "moth-crash.txt")
    private val previous get() = File(app.filesDir, "moth-crash.1.txt")
    private fun now(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(t))

    fun install(context: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val before = LogText.lastLines(Logger.read(), 30).joinToString("\n") { "    $it" }
                append("${now()} UNCAUGHT EXCEPTION in thread '${thread.name}'\n  ${Logger.describe(t, 25)}\n" +
                    "  last 30 log lines before the crash" + (if (Logger.level == LogLevel.OFF) " (log level OFF: usually empty)" else "") + ":\n" +
                    (if (before.isBlank()) "    <none>" else before) + "\n")
            }
            old?.uncaughtException(thread, t)
        }
        Thread { runCatching { recordExitReasons(context) } }.start()
    }

    private fun append(text: String) = synchronized(lock) {
        if (current.length() > MAX_CURRENT) { previous.delete(); current.renameTo(previous) }
        java.io.FileOutputStream(current, true).use { it.write(text.toByteArray()); it.fd.sync() }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "OTHER($r)"
    }

    private fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences("logging", Context.MODE_PRIVATE)
        val last = prefs.getLong("exit_last", 0L)
        val manager = context.getSystemService(ActivityManager::class.java)
        val fresh = LogText.unprocessed(manager.getHistoricalProcessExitReasons(null, 0, 0), last) { it.timestamp }
        for (info in fresh) {
            val sb = StringBuilder("${now(info.timestamp)} PROCESS EXIT reason=${reasonName(info.reason)} status=${info.status} importance=${info.importance} " +
                "pss=${info.pss}KB rss=${info.rss}KB process=${info.processName}\n")
            if (!info.description.isNullOrBlank()) sb.append("  description: ").append(LogRedactor.redact(info.description)).append('\n')
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR) {
                val trace = runCatching { info.traceInputStream?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
                    while (out.size() < TRACE_LIMIT * 4) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                } }.getOrNull()
                if (trace != null) {
                    // Redact line by line so multi-line traces stay readable.
                    val text = LogText.truncate(LogText.printable(trace), TRACE_LIMIT)
                    sb.append("  trace:\n").append(text.lineSequence().joinToString("\n") { "    " + LogRedactor.redact(it) }).append('\n')
                }
            }
            append(sb.toString())
        }
        if (fresh.isNotEmpty()) prefs.edit().putLong("exit_last", fresh.maxOf { it.timestamp }).apply()
    }

    fun read(): String = synchronized(lock) { (if (previous.exists()) previous.readText() else "") + (if (current.exists()) current.readText() else "") }
    fun clear() = synchronized(lock) { previous.delete(); current.delete() }
    fun shareFile(): File {
        val dir = File(app.cacheDir, "logs").apply { mkdirs() }
        return File(dir, "moth-crash-log.txt").also { it.writeText(Logger.header() + read()) }
    }
}
