package dev.pridgey.brig.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pridgey.brig.BrigApp
import dev.pridgey.brig.BuildConfig
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.CalendarRepo
import dev.pridgey.brig.lock.LockActivity
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.service.LockService
import dev.pridgey.brig.update.UpdateState
import dev.pridgey.brig.update.Updater
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.Notifications
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.pressScale
import kotlinx.coroutines.launch

enum class Screen(val title: String, val subtitle: String, val icon: ImageVector) {
    HOME("Brig", "", Icons.Filled.Home),
    APPEARANCE("Appearance", "Clock, background, color", Icons.Filled.Palette),
    WIDGETS("Widgets", "What shows at a glance", Icons.Filled.Widgets),
    NOTIFICATIONS("Notifications", "Privacy & replies", Icons.Filled.Notifications),
    UNLOCK("Unlock & gestures", "PIN, math, swipes", Icons.Filled.Gesture),
    SHORTCUTS("Shortcuts", "Apps on the lock screen", Icons.Filled.Apps),
    PROFILES("Profiles", "Bedside & automation", Icons.Filled.Schedule),
    UPDATES("Updates & backup", "Self-update, export", Icons.Filled.SystemUpdate),
    PERMISSIONS("Permissions", "What Brig can access", Icons.Filled.Security),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_SCREEN)?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
        setContent {
            val settings by BrigApp.settings.flow.collectAsStateWithLifecycle()
            BrigTheme(accent = settings.appearance.accent) {
                Surface(color = MaterialTheme.colorScheme.background) { BrigSettingsApp(settings, start ?: Screen.HOME) }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
    }
}

@Composable
private fun BrigSettingsApp(settings: BrigSettings, start: Screen) {
    var screen by rememberSaveable { mutableStateOf(start) }
    val scope = rememberCoroutineScope()
    val update: SettingsUpdate = { f -> scope.launch { BrigApp.settings.update(f) } }
    val back = { screen = Screen.HOME }
    BackHandler(enabled = screen != Screen.HOME) { back() }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.HOME
            val spec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
            (slideInHorizontally(spec) { if (forward) it / 3 else -it / 3 } + fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith
                (slideOutHorizontally(spec) { if (forward) -it / 3 else it / 3 } + fadeOut() + scaleOut(targetScale = 0.96f))
        },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.HOME -> HomeScreen(settings, update) { screen = it }
            Screen.APPEARANCE -> AppearanceScreen(settings, update, back)
            Screen.WIDGETS -> WidgetsScreen(settings, update, back)
            Screen.NOTIFICATIONS -> NotificationsScreen(settings, update, back)
            Screen.UNLOCK -> UnlockScreen(settings, update, back)
            Screen.SHORTCUTS -> ShortcutsScreen(settings, update, back)
            Screen.PROFILES -> ProfilesScreen(settings, update, back)
            Screen.UPDATES -> UpdatesScreen(settings, update, back)
            Screen.PERMISSIONS -> PermissionsScreen(back)
        }
    }
}

private data class SetupStatus(
    val a11y: Boolean,
    val listener: Boolean,
    val postNotif: Boolean,
    val calendar: Boolean,
    val dnd: Boolean,
    val install: Boolean,
    val battery: Boolean,
) {
    val essentialsPending get() = listOf(a11y, listener, postNotif).count { !it }
    val granted get() = listOf(a11y, listener, postNotif, calendar, dnd, install, battery).count { it }
    val total get() = 7
}

/** Current permission state, re-read whenever the app comes back to the foreground. */
@Composable
private fun rememberSetupStatus(): Pair<SetupStatus, () -> Unit> {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val listenerConnected by BrigNotificationListener.connected.collectAsStateWithLifecycle()
    val status = remember(tick, listenerConnected) {
        SetupStatus(
            a11y = Device.isAccessibilityEnabled(ctx),
            listener = Device.isNotificationListenerEnabled(ctx),
            postNotif = Notifications.canPost(ctx),
            calendar = CalendarRepo.hasPermission(ctx),
            dnd = Device.hasDndAccess(ctx),
            install = Device.canInstallPackages(ctx),
            battery = Device.ignoringBatteryOptimizations(ctx),
        )
    }
    return status to { tick++ }
}

private fun openSettings(ctx: android.content.Context, action: String, withPackage: Boolean = false) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
    runCatching { ctx.startActivity(i) }
}

@Composable
private fun SetupGroup(status: SetupStatus, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val title = if (status.essentialsPending == 0) "Essentials done · ${status.granted} of ${status.total} granted"
    else "Setup · ${status.essentialsPending} essential left"
    SettingsGroup(title) {
        SetupRow("Lock trigger", "Accessibility service. Opens Brig when the screen turns off.", Icons.Filled.AccessibilityNew, status.a11y) {
            openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        SetupRow("Notification access", "Notifications, media controls and replies", Icons.Filled.NotificationsActive, status.listener) {
            openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            SetupRow("Post notifications", "Update alerts and fallback mode", Icons.Filled.Notifications, status.postNotif) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SetupRow("Calendar", "Upcoming events widget", Icons.Filled.CalendarMonth, status.calendar, optional = true) {
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
        SetupRow("Do Not Disturb access", "DND toggle and silent mode", Icons.Filled.DoNotDisturbOn, status.dnd, optional = true) {
            openSettings(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        SetupRow("Install updates", "Lets the built-in updater install new versions", Icons.Filled.SystemUpdate, status.install, optional = true) {
            openSettings(ctx, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, withPackage = true)
        }
        SetupRow("Unrestricted battery", "Keeps Android from killing Brig", Icons.Filled.BatterySaver, status.battery, optional = true) {
            openSettings(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
        }
    }
}

@Composable
private fun RestrictedSettingsHint() {
    val ctx = LocalContext.current
    Banner(
        "Toggle greyed out?",
        "Android blocks these for sideloaded apps. Open App info → ⋮ → Allow restricted settings, then try again.",
        Icons.Filled.Info, MaterialTheme.colorScheme.secondaryContainer,
    ) { openSettings(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }
}

@Composable
private fun PermissionsScreen(onBack: () -> Unit) {
    val (status, refresh) = rememberSetupStatus()
    ScreenScaffold("Permissions", onBack) {
        item { SetupGroup(status, refresh) }
        item { RestrictedSettingsHint() }
    }
}

@Composable
private fun HomeScreen(settings: BrigSettings, update: SettingsUpdate, navigate: (Screen) -> Unit) {
    val updateState by Updater.state.collectAsStateWithLifecycle()
    val (status, refresh) = rememberSetupStatus()
    val setupDone = status.essentialsPending == 0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Hero(settings, status.a11y, update) }

        if (updateState is UpdateState.Available) {
            item {
                val r = (updateState as UpdateState.Available).release
                Banner(
                    "Update ready: ${r.tag}", "Tap to download and install",
                    Icons.Filled.Download, MaterialTheme.colorScheme.tertiaryContainer,
                ) { navigate(Screen.UPDATES) }
            }
        }

        // Setup sits up top only until the essentials are granted; then it moves to its own page.
        if (!setupDone) {
            item { SetupGroup(status, refresh) }
            item { RestrictedSettingsHint() }
        }

        item {
            Text(
                "CUSTOMIZE",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.4f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        val sections = Screen.entries.filter { it != Screen.HOME && it != Screen.PERMISSIONS }
        sections.chunked(2).forEachIndexed { row, pair ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { col, s ->
                        SectionTile(s, row * 2 + col, Modifier.weight(1f)) { navigate(s) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (setupDone) {
            item {
                Surface(
                    onClick = { navigate(Screen.PERMISSIONS) },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(PolygonShape(BrigShapes.cookie9)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Security, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Permissions", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${status.granted} of ${status.total} granted · essentials all set",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Text(
                "Brig ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Hero(settings: BrigSettings, a11y: Boolean, update: SettingsUpdate) {
    val ctx = LocalContext.current
    Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpinningBadge(52.dp, MaterialTheme.colorScheme.primary) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Text("Brig", style = HeroTitle, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your lock screen, your rules.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = if (settings.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                LockPreview(settings, Modifier.height(300.dp)) { LockActivity.show(ctx) }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (settings.enabled) "Brig is on" else "Brig is off", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            when {
                                !settings.enabled -> "Turn on to replace your lock screen"
                                a11y -> "Shows the moment your screen turns off"
                                else -> "Fallback mode. Enable the lock trigger below."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = { on ->
                        update { it.copy(enabled = on) }
                        LockService.sync(ctx, on)
                    })
                }
                Spacer(Modifier.height(12.dp))
                val source = remember { MutableInteractionSource() }
                Button(
                    onClick = { LockActivity.show(ctx) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(source, 0.95f),
                    interactionSource = source,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Preview lock screen", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionTile(s: Screen, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val palettes = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary,
    )
    val (container, badge) = palettes[index % palettes.size]
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 148.dp).pressScale(source, 0.95f),
        shape = RoundedCornerShape(32.dp),
        color = container,
        interactionSource = source,
    ) {
        Column(Modifier.padding(18.dp)) {
            Box(
                Modifier.size(48.dp).clip(PolygonShape(BrigShapes.badges[index % BrigShapes.badges.size])).background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(s.icon, null, tint = MaterialTheme.colorScheme.surface)
            }
            Spacer(Modifier.height(14.dp))
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupRow(title: String, desc: String, icon: ImageVector, done: Boolean, optional: Boolean = false, onAction: () -> Unit) {
    ListItem(
        headlineContent = { Text(title + if (optional) "  · optional" else "", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(desc) },
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(if (done) PolygonShape(BrigShapes.cookie9) else CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else icon, null,
                    tint = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        trailingContent = if (!done) { { FilledTonalButton(onClick = onAction) { Text("Grant") } } } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !done, onClick = onAction),
    )
}

@Composable
fun Banner(title: String, body: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, shape = RoundedCornerShape(28.dp), color = color,
        interactionSource = source, modifier = Modifier.pressScale(source, 0.97f),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(PolygonShape(BrigShapes.cookie6)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(icon, null)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
