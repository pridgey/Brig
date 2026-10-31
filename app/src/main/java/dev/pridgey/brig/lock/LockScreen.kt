package dev.pridgey.brig.lock

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.DoNotDisturbOff
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import dev.pridgey.brig.data.ActiveEffects
import dev.pridgey.brig.data.AppNotificationMode
import dev.pridgey.brig.data.AppsRepo
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.CalEvent
import dev.pridgey.brig.data.CalendarRepo
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.data.GestureAction
import dev.pridgey.brig.data.GestureDir
import dev.pridgey.brig.data.Gestures
import dev.pridgey.brig.data.ProfileEngine
import dev.pridgey.brig.data.Stats
import dev.pridgey.brig.data.WeatherConfig
import dev.pridgey.brig.data.WeatherNow
import dev.pridgey.brig.data.WeatherRepo
import dev.pridgey.brig.data.WidgetType
import dev.pridgey.brig.service.BrigNotification
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.service.MediaRepo
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.Device
import dev.pridgey.brig.util.MorphShape
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.Quotes
import dev.pridgey.brig.util.Torch
import dev.pridgey.brig.util.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import android.media.AudioManager

@Composable
fun LockScreen(settings: BrigSettings, controller: LockController) {
    val context = LocalContext.current
    val appearance = settings.appearance
    val now by rememberNow(appearance.showSeconds)
    val battery by rememberBattery()
    val minuteOfDay = now.hour * 60 + now.minute
    val headphones = remember(minuteOfDay) { Device.headphonesConnected(context) }
    val effects = remember(settings.profiles, minuteOfDay, battery.charging, headphones) {
        ProfileEngine.resolve(settings.profiles, minuteOfDay, battery.charging, headphones)
    }
    val accent = (effects.accent ?: appearance.accent)?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val clockStyle = effects.clockStyle ?: appearance.clockStyle
    val dim = effects.dim ?: appearance.dim

    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val latest by rememberUpdatedState(settings)

    fun fire(dir: GestureDir) {
        val g = latest.gestures
        var action = g.action(dir)
        if (dir == GestureDir.UP && !g.hasUnlock()) action = GestureAction.UNLOCK
        if (action != GestureAction.NONE) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.perform(action, g.apps[dir.name])
    }

    // Entrance: everything springs up into place when the lock screen appears.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDragEnd = {
                        val dir = when {
                            abs(total.x) > abs(total.y) && abs(total.x) > threshold ->
                                if (total.x < 0) GestureDir.LEFT else GestureDir.RIGHT
                            abs(total.y) > threshold -> if (total.y < 0) GestureDir.UP else GestureDir.DOWN
                            else -> null
                        }
                        scope.launch { dragY.animateTo(0f, spring(dampingRatio = 0.6f)) }
                        scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.6f)) }
                        dir?.let(::fire)
                    },
                    onDragCancel = {
                        scope.launch { dragY.animateTo(0f) }
                        scope.launch { dragX.animateTo(0f) }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        total += amount
                        scope.launch { dragY.snapTo(total.y) }
                        scope.launch { dragX.snapTo(total.x) }
                    },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fire(GestureDir.DOUBLE_TAP) }) },
    ) {
        if (!effects.bedside) {
            LockBackground(
                appearance, accent,
                Modifier.graphicsLayer {
                    // Background parallax while dragging.
                    translationX = dragX.value * 0.08f
                    translationY = dragY.value * 0.08f
                    val s = 1.06f - (abs(dragY.value) / (threshold * 20)).coerceAtMost(0.04f)
                    scaleX = s
                    scaleY = s
                },
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (effects.bedside) 1f else dim)))

        val contentModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = (abs(dragY.value) / (threshold * 2.5f)).coerceIn(0f, 1f)
                translationY = dragY.value * 0.4f + (1f - entrance.value) * 120f
                translationX = dragX.value * 0.25f
                val s = 1f - progress * 0.08f
                scaleX = s
                scaleY = s
                alpha = (entrance.value * (1f - progress * 0.6f)).coerceIn(0f, 1f)
            }

        if (effects.bedside) {
            BedsideContent(now, appearance.use24h, battery, accent, settings.gestures, controller, contentModifier)
        } else {
            MainContent(settings, effects, now, battery, accent, clockStyle, controller, contentModifier)
        }

        controller.gate?.let {
            UnlockGate(settings.unlock, accent, onSuccess = controller::gatePassed, onCancel = controller::gateCancelled)
        }
    }
}

private sealed interface WidgetGroup {
    data class Chips(val types: List<WidgetType>) : WidgetGroup
    data class Single(val type: WidgetType) : WidgetGroup
}

private val chipTypes = setOf(WidgetType.ALARM, WidgetType.BATTERY, WidgetType.WEATHER, WidgetType.STATS)
private val bottomTypes = setOf(WidgetType.SHORTCUTS, WidgetType.TOGGLES)

private fun groupWidgets(types: List<WidgetType>): List<WidgetGroup> {
    val out = mutableListOf<WidgetGroup>()
    var chips = mutableListOf<WidgetType>()
    fun flush() {
        if (chips.isNotEmpty()) {
            out += WidgetGroup.Chips(chips)
            chips = mutableListOf()
        }
    }
    types.forEach { if (it in chipTypes) chips += it else { flush(); out += WidgetGroup.Single(it) } }
    flush()
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainContent(
    settings: BrigSettings,
    effects: ActiveEffects,
    now: LocalDateTime,
    battery: BatteryState,
    accent: Color,
    clockStyle: ClockStyle,
    controller: LockController,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    var bottomHeight by remember { mutableIntStateOf(0) }
    val enabled = settings.widgetOrder().filter { it.enabled }.map { it.type }
    val appearance = settings.appearance

    Box(modifier.systemBarsPadding().padding(horizontal = 22.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = with(density) { bottomHeight.toDp() } + 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val centered = clockStyle == ClockStyle.DIGITAL || clockStyle == ClockStyle.ANALOG
            val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
            Clock(
                clockStyle, now, appearance.use24h, appearance.showSeconds, appearance.clockScale, accent,
                Modifier.align(align),
            )
            appearance.secondaryZone?.let { z -> SecondaryZone(z, now, appearance.use24h, Modifier.align(align)) }
            if (effects.activeNames.isNotEmpty()) {
                Text(
                    effects.activeNames.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.align(align),
                )
            }

            groupWidgets(enabled.filterNot { it in bottomTypes }).forEach { group ->
                when (group) {
                    is WidgetGroup.Chips -> FlowRow(
                        Modifier.align(align),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.types.forEach { type ->
                            when (type) {
                                WidgetType.ALARM -> AlarmChip()
                                WidgetType.BATTERY -> BatteryChip(battery, accent)
                                WidgetType.WEATHER -> WeatherChip(settings.weather)
                                WidgetType.STATS -> StatsChip()
                                else -> Unit
                            }
                        }
                    }
                    is WidgetGroup.Single -> when (group.type) {
                        WidgetType.DATE -> Text(
                            now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.align(align),
                        )
                        WidgetType.CALENDAR -> CalendarWidget(now, accent)
                        WidgetType.MEDIA -> MediaWidget(accent)
                        WidgetType.QUOTE -> QuoteWidget(now)
                        WidgetType.NOTIFICATIONS -> if (!effects.hideNotifications) {
                            Box(Modifier.weight(1f, fill = false)) {
                                NotificationsWidget(settings, effects, controller)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomHeight = it.height }
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (WidgetType.SHORTCUTS in enabled && settings.shortcuts.isNotEmpty()) {
                ShortcutsRow(settings.shortcuts, controller)
            }
            if (WidgetType.TOGGLES in enabled) TogglesRow(accent, controller)
            UnlockHint(settings.gestures, controller.systemLockSecure, accent)
        }
    }
}

@Composable
private fun SecondaryZone(zone: String, now: LocalDateTime, use24h: Boolean, modifier: Modifier) {
    val id = remember(zone) { runCatching { ZoneId.of(zone) }.getOrNull() } ?: return
    val there = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(id)
    Text(
        "${zone.substringAfterLast('/').replace('_', ' ')}  ${there.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))}",
        style = MaterialTheme.typography.titleSmall,
        color = Color.White.copy(alpha = 0.75f),
        modifier = modifier,
    )
}

@Composable
private fun BedsideContent(
    now: LocalDateTime,
    use24h: Boolean,
    battery: BatteryState,
    accent: Color,
    gestures: Gestures,
    controller: LockController,
    modifier: Modifier,
) {
    val warm = Color(0xFFE0674F)
    Box(modifier.systemBarsPadding()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Clock(ClockStyle.DIGITAL, now, use24h, false, 1.25f, warm.copy(alpha = 0.8f), color = warm.copy(alpha = 0.75f))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmChip(dimmed = true)
                BatteryChip(battery, warm, dimmed = true)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).graphicsLayer { alpha = 0.45f }) {
            UnlockHint(gestures, controller.systemLockSecure, accent)
        }
    }
}

// ---------------------------------------------------------------- Glance chips

@Composable
private fun GlanceChip(text: String, icon: ImageVector? = null, emoji: String? = null, tint: Color = Color.White, dimmed: Boolean = false) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (dimmed) 0.05f else 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(16.dp), tint = tint) }
        emoji?.let { Text(it, fontSize = 15.sp) }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = if (dimmed) 0.5f else 1f))
    }
}

@Composable
private fun AlarmChip(dimmed: Boolean = false) {
    val ctx = LocalContext.current
    val t = remember { Device.nextAlarm(ctx) } ?: return
    val time = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    val soon = t - System.currentTimeMillis() < 24 * 3600_000L
    GlanceChip(time.format(DateTimeFormatter.ofPattern(if (soon) "h:mm a" else "EEE h:mm a")), icon = Icons.Filled.Alarm, dimmed = dimmed)
}

@Composable
private fun BatteryChip(b: BatteryState, accent: Color, dimmed: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "charge")
    val a by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    GlanceChip(
        if (b.charging) "${b.level}% · charging" else "${b.level}%",
        icon = if (b.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
        tint = if (b.charging) accent.copy(alpha = a) else Color.White,
        dimmed = dimmed,
    )
}

@Composable
private fun WeatherChip(cfg: WeatherConfig) {
    val ctx = LocalContext.current
    if (cfg.lat == null) {
        GlanceChip("Set a city in Brig", emoji = "🌡️")
        return
    }
    val w by produceState<WeatherNow?>(null, cfg) { value = WeatherRepo.current(ctx, cfg) }
    val now = w ?: return
    val (emoji, _) = WeatherRepo.describe(now.code, now.isDay)
    GlanceChip("${now.temp}°  ${now.hi}°/${now.lo}°", emoji = emoji)
}

@Composable
private fun StatsChip() {
    val ctx = LocalContext.current
    val unlocks = remember { Stats.unlocks(ctx) }
    val wakes = remember { Stats.wakes(ctx) }
    GlanceChip("$unlocks unlocks · $wakes wakes", emoji = "📊")
}

// ---------------------------------------------------------------- Cards

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val color = Color.White.copy(alpha = 0.11f)
    val inner: @Composable () -> Unit = { Column(Modifier.padding(14.dp), content = content) }
    if (onClick != null) {
        val source = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick, modifier = modifier.fillMaxWidth().pressScale(source, 0.97f), shape = shape,
            color = color, contentColor = Color.White, interactionSource = source, content = inner,
        )
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, contentColor = Color.White, content = inner)
    }
}

@Composable
private fun CalendarWidget(now: LocalDateTime, accent: Color) {
    val ctx = LocalContext.current
    val events by produceState(emptyList<CalEvent>(), now.hour, now.minute / 15) {
        value = withContext(Dispatchers.IO) { CalendarRepo.upcoming(ctx) }
    }
    if (events.isEmpty()) return
    GlassCard {
        events.forEach { e ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (e.color == 0) accent else Color(e.color)))
                Spacer(Modifier.width(10.dp))
                Text(CalendarRepo.whenLabel(e), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.widthIn(min = 84.dp))
                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MediaWidget(accent: Color) {
    val media by MediaRepo.state.collectAsState()
    val m = media ?: return
    // The play button morphs between a cookie (paused) and a circle (playing).
    val morph = remember { Morph(BrigShapes.cookie9, BrigShapes.circle) }
    val progress by animateFloatAsState(if (m.playing) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "morph")
    val spin = rememberInfiniteTransition(label = "spin")
    val rotation by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = androidx.compose.animation.core.LinearEasing)), label = "rot")
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.art != null) {
                Image(m.art, null, Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.appLabel, style = MaterialTheme.typography.labelSmall, color = accent)
            }
            IconButton(onClick = { m.controller.transportControls.skipToPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White)
            }
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(52.dp)
                    .pressScale(source, 0.85f)
                    .clip(MorphShape(morph, progress, if (m.playing) 0f else rotation))
                    .background(accent)
                    .clickable(source, null) {
                        if (m.playing) m.controller.transportControls.pause() else m.controller.transportControls.play()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = Color.Black)
            }
            IconButton(onClick = { m.controller.transportControls.skipToNext() }) {
                Icon(Icons.Filled.SkipNext, "Next", tint = Color.White)
            }
        }
    }
}

@Composable
private fun QuoteWidget(now: LocalDateTime) {
    val (quote, author) = Quotes.forDay(now.dayOfYear)
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("“$quote”", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.9f))
        Text("— $author", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Notifications

@Composable
private fun NotificationsWidget(settings: BrigSettings, effects: ActiveEffects, controller: LockController) {
    val all by BrigNotificationListener.notifications.collectAsState()
    val connected by BrigNotificationListener.connected.collectAsState()
    val rules = settings.notifications
    if (!connected) {
        Text("Grant notification access in Brig to see notifications here", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
        return
    }
    val visible = all.filter { n ->
        rules.appModes[n.pkg] != AppNotificationMode.BLOCK && (rules.showSilent || !n.silent)
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = visible.take(rules.maxShown)
        shown.forEachIndexed { i, n ->
            androidx.compose.runtime.key(n.key) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(300, delayMillis = i * 60)) + slideInVertically(spring(dampingRatio = 0.7f)) { it / 2 } + scaleIn(initialScale = 0.9f),
                ) {
                    NotificationCard(
                        n,
                        hideContent = rules.hideAllContent || effects.hideContent || rules.appModes[n.pkg] == AppNotificationMode.HIDE_CONTENT,
                        allowReply = rules.allowReply,
                        expanded = expanded == n.key,
                        onToggle = { expanded = if (expanded == n.key) null else n.key },
                        controller = controller,
                    )
                }
            }
        }
        if (visible.size > shown.size) {
            Text(
                "+${visible.size - shown.size} more",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationCard(
    n: BrigNotification,
    hideContent: Boolean,
    allowReply: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    controller: LockController,
) {
    val content: @Composable () -> Unit = {
        GlassCard(onClick = onToggle) {
            Row(verticalAlignment = Alignment.Top) {
                val avatar = if (!hideContent) n.largeIcon ?: n.appIcon else n.appIcon
                avatar?.let {
                    Image(it, null, Modifier.size(38.dp).clip(PolygonShape(BrigShapes.cookie12)), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.appLabel, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            DateUtils.getRelativeTimeSpanString(n.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                    if (hideContent) {
                        Text("Content hidden", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                    } else {
                        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            AnimatedVisibility(expanded) { NotificationActions(n, allowReply && !hideContent, controller) }
        }
    }
    if (n.clearable) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                BrigNotificationListener.dismiss(n.key)
                true
            } else false
        })
        SwipeToDismissBox(state = state, backgroundContent = {}) { content() }
    } else {
        content()
    }
}

@Composable
private fun NotificationActions(n: BrigNotification, allowReply: Boolean, controller: LockController) {
    val ctx = LocalContext.current
    var replying by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (n.contentIntent != null) FilledTonalButton(onClick = { controller.openNotification(n) }) { Text("Open") }
            if (allowReply && n.replyAction != null) FilledTonalButton(onClick = { replying = !replying }) { Text("Reply") }
            if (n.clearable) TextButton(onClick = { BrigNotificationListener.dismiss(n.key) }) { Text("Dismiss", color = Color.White) }
        }
        if (replying) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Reply…") }, maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                IconButton(onClick = {
                    val action = n.replyAction ?: return@IconButton
                    if (text.isNotBlank() && BrigNotificationListener.reply(ctx, action, text)) {
                        text = ""
                        replying = false
                        Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- Bottom bar

@Composable
private fun ShortcutsRow(shortcuts: List<String>, controller: LockController) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        shortcuts.forEachIndexed { i, pkg ->
            val icon = remember(pkg) { AppsRepo.icon(ctx, pkg) }
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(52.dp)
                    .pressScale(source, 0.8f)
                    .clip(PolygonShape(BrigShapes.badges[i % BrigShapes.badges.size]))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(source, null) { controller.launchApp(pkg) },
                contentAlignment = Alignment.Center,
            ) {
                icon?.let { Image(it, AppsRepo.label(ctx, pkg), Modifier.size(34.dp)) }
            }
        }
    }
}

@Composable
private fun TogglesRow(accent: Color, controller: LockController) {
    val ctx = LocalContext.current
    val torch by Torch.on.collectAsState()
    LaunchedEffect(Unit) { Torch.init(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    val dnd = remember(refresh) { Device.isDndOn(ctx) }
    val ringer = remember(refresh) { Device.ringerMode(ctx) }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        MorphToggle(if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, "Flashlight", torch, accent) { Torch.toggle(ctx) }
        MorphToggle(if (dnd) Icons.Filled.DoNotDisturbOn else Icons.Filled.DoNotDisturbOff, "Do Not Disturb", dnd, accent) {
            controller.toggleDnd()
            refresh++
        }
        MorphToggle(
            when (ringer) {
                AudioManager.RINGER_MODE_SILENT -> Icons.AutoMirrored.Filled.VolumeOff
                AudioManager.RINGER_MODE_VIBRATE -> Icons.Filled.Vibration
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            "Ringer", ringer != AudioManager.RINGER_MODE_NORMAL, accent,
        ) {
            controller.cycleRinger()
            refresh++
        }
    }
}

/** A round toggle that morphs into a "cookie" with a bouncy spring when switched on. */
@Composable
private fun MorphToggle(icon: ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val morph = remember { Morph(BrigShapes.circle, BrigShapes.cookie4) }
    val progress by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "toggle")
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(58.dp)
            .pressScale(source, 0.82f)
            .clip(MorphShape(morph, progress, progress * 45f))
            .background(androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.14f), accent, progress))
            .clickable(source, null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = androidx.compose.ui.graphics.lerp(Color.White, Color.Black, progress))
    }
}

@Composable
private fun UnlockHint(gestures: Gestures, fingerprint: Boolean, accent: Color) {
    val dir = gestures.unlockDir()
    val icon = when (dir) {
        GestureDir.UP -> Icons.Filled.KeyboardArrowUp
        GestureDir.DOWN -> Icons.Filled.KeyboardArrowDown
        GestureDir.LEFT -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        GestureDir.RIGHT -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        GestureDir.DOUBLE_TAP -> Icons.Filled.TouchApp
    }
    val t = rememberInfiniteTransition(label = "hint")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon, null,
            Modifier.size(28.dp).graphicsLayer {
                val d = bob * 8.dp.toPx()
                when (dir) {
                    GestureDir.UP -> translationY = -d
                    GestureDir.DOWN -> translationY = d
                    GestureDir.LEFT -> translationX = -d
                    GestureDir.RIGHT -> translationX = d
                    GestureDir.DOUBLE_TAP -> { scaleX = 1f + bob * 0.15f; scaleY = 1f + bob * 0.15f }
                }
                alpha = 0.5f + bob * 0.5f
            },
            tint = accent,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LockOpen, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.width(6.dp))
            Text(
                "${dir.label} to unlock" + if (fingerprint) " · or use fingerprint" else "",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

// ---------------------------------------------------------------- Unlock gate

@Composable
private fun UnlockGate(
    config: dev.pridgey.brig.data.UnlockConfig,
    accent: Color,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler { onCancel() }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.82f))
            .pointerInput(Unit) {
                // Swallow every touch so gestures don't reach the lock screen underneath.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { val s = 0.9f + 0.1f * appear.value; scaleX = s; scaleY = s }) {
            when (config.method) {
                dev.pridgey.brig.data.UnlockMethod.PIN -> PinGate(config, accent, onSuccess, onCancel)
                dev.pridgey.brig.data.UnlockMethod.MATH -> MathGate(config.mathDifficulty, accent, onSuccess, onCancel)
                dev.pridgey.brig.data.UnlockMethod.SWIPE -> LaunchedEffect(Unit) { onSuccess() }
            }
        }
    }
}
