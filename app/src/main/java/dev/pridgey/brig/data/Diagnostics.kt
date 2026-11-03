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
