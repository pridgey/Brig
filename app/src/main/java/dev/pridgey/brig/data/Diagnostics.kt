package dev.pridgey.brig.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class DiagEvent(val label: String) {
    SCREEN_OFF("Screen turned off"),
    LOCK_SHOWN("Lock screen shown"),
    LOCK_SKIPPED("Brig is off, skipped"),
    A11Y_ON("Lock trigger connected"),
    A11Y_OFF("Lock trigger stopped"),
    LISTENER_ON("Notification access connected"),
    LISTENER_OFF("Notification access lost"),
    FALLBACK("Fallback notification posted"),
    BOOT("Phone restarted"),
    UPDATED("Brig updated"),
}

data class DiagEntry(val event: DiagEvent, val at: Long)

/** A small on-device log of what Brig saw, so "why didn't it show up?" has an answer. */
object DiagLog {
    private const val MAX = 80
    val entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        entries.value = prefs(ctx).getString("log", null).orEmpty().lines().mapNotNull { line ->
            val (name, at) = line.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            val e = runCatching { DiagEvent.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            DiagEntry(e, at.toLongOrNull() ?: return@mapNotNull null)
        }
    }

    fun record(ctx: Context, event: DiagEvent, at: Long = System.currentTimeMillis()) {
        load(ctx)
        val next = (entries.value + DiagEntry(event, at)).takeLast(MAX)
        entries.value = next
        prefs(ctx).edit().putString("log", next.joinToString("\n") { "${it.event.name},${it.at}" }).apply()
    }

    fun clear(ctx: Context) {
        entries.value = emptyList()
        prefs(ctx).edit().remove("log").apply()
    }

    /**
     * Screen-offs (while Brig was on) with no lock screen within [windowMs] afterwards. Pure for tests.
     */
    fun missed(log: List<DiagEntry>, windowMs: Long = 4000): List<DiagEntry> =
        log.filter { it.event == DiagEvent.SCREEN_OFF }.filter { off ->
            log.none { it.event == DiagEvent.LOCK_SHOWN && it.at in off.at..(off.at + windowMs) }
        }
}

/**
 * Records why Brig last closed unexpectedly, so it can be reported and fixed: Java crashes (caught
 * here), plus native crashes, freezes and low-memory kills (read from Android's own exit records,
 * Android 11+). Only the error and where it happened, never the user's content.
 */
object CrashLog {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "last_crash.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("crashes", Context.MODE_PRIVATE)

    /** Bumps when a report is dismissed, so screens showing it update. */
    val changes = MutableStateFlow(0)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                file(app).writeText(
                    "${java.time.ZonedDateTime.now()} · Java crash · thread ${thread.name}\n\n" + trace.take(16_000),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private class Entry(val at: Long, val text: String)

    private fun javaCrash(ctx: Context): Entry? = runCatching {
        file(ctx).takeIf { it.exists() }?.let { Entry(it.lastModified(), it.readText()) }
    }.getOrNull()

    /** Unexpected exits Android recorded for Brig: crashes, freezes, low-memory kills. */
    private fun systemExits(ctx: Context): List<Entry> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return emptyList()
        val infos = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 12) }.getOrDefault(emptyList())
        val visible = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return infos.mapNotNull { info ->
            // Being closed while in the background is normal housekeeping, not a problem.
            val kill = info.reason in setOf(
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
                android.app.ApplicationExitInfo.REASON_SIGNALED,
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            )
            if (kill && info.importance > visible) return@mapNotNull null
            val reason = when (info.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                android.app.ApplicationExitInfo.REASON_CRASH -> "Crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "Froze (not responding)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: phone low on memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: used too much"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
                android.app.ApplicationExitInfo.REASON_SIGNALED ->
                    if (info.status == 9) "Killed by Android (signal 9), often for memory" else "Stopped by signal ${info.status}"
                else -> null
            } ?: return@mapNotNull null
            val text = buildString {
                append("${java.time.Instant.ofEpochMilli(info.timestamp).atZone(java.time.ZoneId.systemDefault())} · $reason\n")
                append("Process ${info.processName} · was ${importance(info.importance)}")
                append(" · memory ${info.pss / 1024} MB PSS, ${info.rss / 1024} MB RSS\n")
                info.description?.takeIf { it.isNotBlank() }?.let { append("Details: $it\n") }
                if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        info.traceInputStream?.use { input ->
                            val bytes = input.readBytes()
                            dev.pridgey.brig.util.Tombstone.parse(bytes)?.let(dev.pridgey.brig.util.Tombstone::describe) ?: readable(bytes)
                        }
                    }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { append("\nTrace:\n").append(it) }
                }
            }
            Entry(info.timestamp, text)
        }
    }

    private fun importance(i: Int) = when {
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "running in the background"
        else -> "in the background"
    }

    /**
     * The readable parts of a trace. Native traces are binary; their library and function names
     * are plain text inside, so this keeps runs of printable text that look like stack frames.
     */
    private fun readable(bytes: ByteArray): String {
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text.count { it.code in 32..126 || it == '\n' } > text.length * 0.9) return text.take(6_000) // Already text (ANR).
        val runs = Regex("[\\x20-\\x7E]{6,}").findAll(text).map { it.value.trim() }
        val useful = runs.filter { r ->
            ".so" in r || "signal" in r.lowercase() || "abort" in r.lowercase() || "Cause" in r ||
                r.startsWith("_Z") || "::" in r || "dev.pridgey" in r || "Exception" in r || "Error" in r
        }.distinct().take(60)
        return useful.joinToString("\n").take(6_000)
    }

    /** Everything worth reporting, newest first, or null if nothing new since the last dismiss. */
    fun report(ctx: Context, includeSeen: Boolean = false): String? {
        val seen = if (includeSeen) 0L else prefs(ctx).getLong("seen", 0L)
        val entries = (listOfNotNull(javaCrash(ctx)) + systemExits(ctx)).filter { it.at > seen }.sortedByDescending { it.at }.take(4)
        if (entries.isEmpty()) return null
        val header = "Brig ${dev.pridgey.brig.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n"
        return header + entries.joinToString("\n----\n\n") { it.text }
    }

    /** Hides everything reported so far; only newer problems will show. */
    fun dismiss(ctx: Context) {
        prefs(ctx).edit().putLong("seen", System.currentTimeMillis()).apply()
        changes.value++
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Brig problem report", text))
        android.widget.Toast.makeText(ctx, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
    }
}
