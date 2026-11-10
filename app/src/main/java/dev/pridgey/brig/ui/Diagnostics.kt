package dev.pridgey.brig.ui

import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.DiagEvent
import dev.pridgey.brig.data.DiagLog
import dev.pridgey.brig.lock.LockActivity
import dev.pridgey.brig.service.BrigAccessibilityService
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.service.LockService
import dev.pridgey.brig.util.Device
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.ContentCopy
import dev.pridgey.brig.util.Notifications
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Check(val ok: Boolean, val title: String, val detail: String, val fixLabel: String? = null, val fix: (() -> Unit)? = null)

/** "Why didn't Brig show up?" Checks every moving part and keeps a short log of what happened. */
@Composable
fun DiagnosticsScreen(settings: BrigSettings, update: SettingsUpdate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    remember { DiagLog.load(ctx) }
    val log by DiagLog.entries.collectAsState()
    val a11yRunning by BrigAccessibilityService.running.collectAsState()
    val listener by BrigNotificationListener.connected.collectAsState()

    val checks = remember(tick, settings.enabled, a11yRunning, listener, log.size) {
        val a11yOn = Device.isAccessibilityEnabled(ctx)
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        val missed = DiagLog.missed(log).filter { it.at > dayAgo }
        buildList {
            add(
                Check(
                    settings.enabled, "Brig is switched on",
                    if (settings.enabled) "Brig opens when the screen turns off." else "Brig won't appear until it's on.",
                    "Turn on",
                ) { update { it.copy(enabled = true) }; LockService.sync(ctx, true) },
            )
            add(
                when {
                    !a11yOn -> Check(
                        false, "Lock trigger is off",
                        "Brig is in fallback mode, which is slower and can be blocked. Turn on the accessibility service.",
                        "Open settings",
                    ) { openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                    !a11yRunning -> Check(
                        false, "Lock trigger isn't running",
                        "It's switched on, but Android stopped it (often after an update or to save battery). Switch it off and on again.",
                        "Open settings",
                    ) { openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
                    else -> Check(true, "Lock trigger is running", "Brig hears the screen turn off instantly.")
                },
            )
            add(
                Check(
                    Device.ignoringBatteryOptimizations(ctx), "Unrestricted battery",
                    if (Device.ignoringBatteryOptimizations(ctx)) "Android won't put Brig to sleep." else "Android may stop Brig in the background. This is the most common reason it doesn't appear.",
                    "Allow",
                ) { openSettings(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true) },
            )
            add(
                Check(
                    listener, "Notification access",
                    if (listener) "Notifications, media and live activities work." else "Notifications and media controls won't show. If it's granted but disconnected, switch it off and on.",
                    "Open settings",
                ) { openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            )
            if (!a11yOn) {
                add(
                    Check(
                        Notifications.canPost(ctx), "Post notifications",
                        "Fallback mode opens Brig through a notification.", "Allow",
                    ) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                if (Build.VERSION.SDK_INT >= 34) {
                    val fsi = ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                    add(
                        Check(fsi, "Full-screen alerts", "Fallback mode needs this to open over the lock screen.", "Allow") {
                            openSettings(ctx, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
                        },
                    )
                }
            }
            add(
                if (missed.isEmpty()) Check(true, "No missed locks today", "Every screen-off in the last 24 hours opened Brig.")
                else Check(
                    false, "${missed.size} missed in the last 24 hours",
                    "Last at ${time(missed.last().at)}. The screen turned off but Brig didn't open. Fix anything above, then lock and unlock to test.",
                ),
            )
        }
    }

    ScreenScaffold("Diagnostics", onBack) {
        item {
            SettingsGroup("Checks") {
                checks.forEach { c ->
                    ListItem(
                        headlineContent = { Text(c.title, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(c.detail) },
                        leadingContent = {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, Modifier.size(20.dp),
                                    tint = if (c.ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        },
                        trailingContent = if (!c.ok && c.fix != null) { { FilledTonalButton(onClick = { c.fix.invoke(); tick++ }) { Text(c.fixLabel ?: "Fix") } } } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                ClickRow("Test now", "Opens the lock screen as if the screen turned off", Icons.Filled.PlayArrow) { LockActivity.show(ctx) }
            }
        }
        item { RestrictedSettingsHint() }
        item {
            val version by dev.pridgey.brig.data.CrashLog.changes.collectAsState()
            val report = remember(version) { dev.pridgey.brig.data.CrashLog.report(ctx, includeSeen = true) }
            report?.let { text -> ProblemReport(text) }
        }
        item {
            SettingsGroup("Recent activity") {
                if (log.isEmpty()) Note("Nothing yet. Lock your phone once and come back.")
                val missedAt = DiagLog.missed(log).map { it.at }.toSet()
                log.takeLast(40).asReversed().forEach { e ->
                    val bad = e.event in setOf(DiagEvent.A11Y_OFF, DiagEvent.LISTENER_OFF) || (e.event == DiagEvent.SCREEN_OFF && e.at in missedAt)
                    ListItem(
                        headlineContent = {
                            Text(
                                e.event.label + if (e.event == DiagEvent.SCREEN_OFF && e.at in missedAt) " · Brig didn't open" else "",
                                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = { Text(time(e.at)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (log.isNotEmpty()) ClickRow("Clear log", icon = Icons.Filled.DeleteSweep) { DiagLog.clear(ctx) }
            }
        }
        item { Note("This log stays on your phone. It only records events (like \"screen turned off\"), never content.") }
    }
}

private fun time(at: Long): String {
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val rel = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    return z.format(DateTimeFormatter.ofPattern("EEE h:mm:ss a")) + " · " + rel
}

/** A crash or unexpected close, with a way to send it on. */
@Composable
internal fun ProblemReport(text: String, title: String = "Recent problems", onDismiss: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    SettingsGroup(title) {
        Text(
            text.lines().let { if (expanded) it else it.take(8) }.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { expanded = !expanded },
        )
        ClickRow("Copy report", "Paste it into a message to get it fixed", Icons.Filled.ContentCopy) {
            dev.pridgey.brig.data.CrashLog.copy(ctx, text)
        }
        onDismiss?.let { ClickRow("Dismiss", icon = Icons.Filled.Close, onClick = it) }
    }
}
