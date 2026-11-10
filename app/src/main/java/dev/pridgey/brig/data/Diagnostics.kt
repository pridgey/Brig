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
 * Keeps the last crash's details on the phone, so a crash can be reported and fixed. Records only
 * the error and where it happened in Brig's code, never any of the user's content.
 */
object CrashLog {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                file(app).writeText(
                    "Brig ${dev.pridgey.brig.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                        "${java.time.ZonedDateTime.now()} · thread ${thread.name}\n\n" + trace.take(16_000),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
