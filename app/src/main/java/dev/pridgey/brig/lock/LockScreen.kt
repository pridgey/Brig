package dev.pridgey.brig.lock

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import dev.pridgey.brig.data.WidgetPriority
import dev.pridgey.brig.data.spaceOrder
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
import dev.pridgey.brig.ui.resolveAccent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import dev.pridgey.brig.data.WidgetZone
import androidx.compose.ui.unit.Dp
import dev.pridgey.brig.service.BrigNotification
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.service.NotificationGroup
import dev.pridgey.brig.service.groupNotifications
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
import androidx.compose.material.icons.filled.Person
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.PhotoRotator
import dev.pridgey.brig.data.PhotoSource
import dev.pridgey.brig.data.withLookFor
import dev.pridgey.brig.data.Sun
import dev.pridgey.brig.data.Sleep

/**
 * The depth effect's cut-out for [photoPath]. Starts from the cache, so a cut-out that's already
 * made shows on the first frame instead of popping in a moment later.
 */
@Composable
internal fun rememberCutout(photoPath: String?, on: Boolean): String? {
    val ctx = LocalContext.current
    val cached = remember(photoPath, on) { if (on && photoPath != null) dev.pridgey.brig.util.Segmenter.cached(ctx, photoPath) else null }
    val made by produceState(cached, photoPath, on) {
        value = cached ?: if (on && photoPath != null) dev.pridgey.brig.util.Segmenter.cutout(ctx, photoPath) else null
    }
    return made
}

/** Background opacity for glance chips, from Appearance. */
val LocalChipAlpha = compositionLocalOf { 0.12f }

@Composable
fun LockScreen(settings: BrigSettings, controller: LockController) {
    CompositionLocalProvider(LocalChipAlpha provides settings.appearance.chipOpacity) {
        LockScreenContent(settings, controller)
    }
}

/** Widgets hidden while guest mode is on. */
private val personalWidgets = setOf(
    WidgetType.NOTIFICATIONS, WidgetType.MEDIA, WidgetType.CALENDAR, WidgetType.STATS, WidgetType.HEALTH,
    WidgetType.PACKAGES, WidgetType.MEMORIES, WidgetType.LIVE, WidgetType.ALARM_CARD, WidgetType.TOGGLES, WidgetType.MOOD,
)

/** What the lock screen shows in guest mode: no personal widgets, and only the guest apps. */
private fun BrigSettings.forGuest(): BrigSettings = if (!guest.active) this else copy(
    widgets = widgetOrder().map { if (it.type in personalWidgets) it.copy(enabled = false) else it }
        .map { if (it.type == WidgetType.SHORTCUTS) it.copy(enabled = true) else it },
    shortcuts = guest.apps,
)

/** Which parts of the layout to draw; the depth effect draws the clock and the widgets as separate layers. */
private enum class ContentPart { ALL, CLOCK_ONLY, NO_CLOCK }

@Composable
private fun LockScreenContent(rawSettings: BrigSettings, controller: LockController) {
    val context = LocalContext.current
    val settings = rawSettings.forGuest()
    val base = settings.appearance
    val now by rememberNow(base.showSeconds)
    val battery by rememberBattery()
    val minuteOfDay = now.hour * 60 + now.minute
    val headphones = remember(minuteOfDay) { Device.headphonesConnected(context) }
    val sun = remember(settings.weather.lat, settings.weather.lon, now.toLocalDate()) {
        val lat = settings.weather.lat
        val lon = settings.weather.lon
        if (lat != null && lon != null) Sun.times(now.toLocalDate(), lat, lon) else null
    }
    val effects = remember(settings.profiles, minuteOfDay, battery.charging, headphones, sun) {
        ProfileEngine.resolve(settings.profiles, minuteOfDay, battery.charging, headphones, sun)
    }
    // The photo: a profile's photo wins, then the rotating source, then the one picked in settings.
    val rotated by PhotoRotator.current.collectAsState()
    val rotating = settings.photos.source != PhotoSource.SINGLE
    val photoPath = effects.imagePath ?: (if (rotating) rotated?.path else null) ?: base.imagePath
    val appearance = base.copy(
        background = effects.background ?: if (effects.imagePath != null) BackgroundType.IMAGE else base.background,
        imagePath = photoPath,
    ).let { it.withLookFor(settings.photos, photoPath) }
    // Album art takes over the background while music plays.
    val media by MediaRepo.state.collectAsState()
    val albumArt = media?.takeIf { appearance.albumArtBackground && it.playing && !effects.bedside }?.art
    // Accent from the photo (or album art) when that's on; a profile's accent still wins.
    val photoAccent by produceState<Long?>(null, appearance.accentFromPhoto, albumArt, photoPath, appearance.background) {
        value = if (!appearance.accentFromPhoto) null else withContext(Dispatchers.Default) {
            when {
                albumArt != null -> accentOf(albumArt)
                appearance.background == BackgroundType.IMAGE && photoPath != null -> loadPhoto(photoPath, 160)?.let(::accentOf)
                else -> null
            }
        }
    }
    val accent = resolveAccent(effects.accent ?: photoAccent ?: appearance.accent)
    val clockStyle = effects.clockStyle ?: appearance.clockStyle
    val lux by rememberLux(appearance.ambient && !effects.bedside)
    val ambientTuning = Ambient.tune(effects.dim ?: appearance.dim, lux)
    // Past bedtime: a little dimmer and warmer, on top of whatever else applies.
    val late = Sleep.isLate(minuteOfDay, settings.sleep) && settings.sleep.dimAfter
    val tuning = if (!late) ambientTuning else ambientTuning.copy(
        dim = (ambientTuning.dim + 0.2f).coerceAtMost(0.85f),
        warmth = (ambientTuning.warmth + 0.15f).coerceAtMost(0.3f),
    )
    val dim = tuning.dim
    val tilt by rememberTilt(appearance.tiltParallax && !effects.bedside)

    val depthOn = appearance.depth && appearance.background == BackgroundType.IMAGE && photoPath != null && !effects.bedside && albumArt == null
    val cutout = rememberCutout(photoPath, depthOn)
    val clockTracker = remember { dev.pridgey.brig.util.ClockTracker() }

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
        if (latest.guest.active && action !in setOf(GestureAction.CAMERA, GestureAction.FLASHLIGHT, GestureAction.SLEEP, GestureAction.NONE)) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            controller.toast("Guest mode is on. Tap Exit to leave it.")
            return
        }
        if (action != GestureAction.NONE) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.perform(action, g.apps[dir.name])
    }

    // Entrance: everything springs up into place when the lock screen appears.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }

    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { clockTracker.root = it }
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
        // Background parallax while dragging, plus a gentle shift as the phone tilts.
        val backgroundModifier = Modifier.graphicsLayer {
            translationX = dragX.value * 0.08f + tilt.x * 22.dp.toPx()
            translationY = dragY.value * 0.08f + tilt.y * 22.dp.toPx()
            val s = 1.06f - (abs(dragY.value) / (threshold * 20)).coerceAtMost(0.04f)
            scaleX = s
            scaleY = s
        }
        if (!effects.bedside) {
            LockBackground(appearance, accent, backgroundModifier)
            AlbumArtBackground(albumArt, backgroundModifier)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (effects.bedside) 1f else dim)))
        if (tuning.warmth > 0f && !effects.bedside) Box(Modifier.fillMaxSize().background(Color(0xFFFF8A3D).copy(alpha = tuning.warmth)))

        val contentModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = (abs(dragY.value) / (threshold * 2.5f)).coerceIn(0f, 1f)
                translationY = dragY.value * 0.4f + (1f - entrance.value) * 120f - tilt.y * 6.dp.toPx()
                translationX = dragX.value * 0.25f - tilt.x * 6.dp.toPx()
                val s = 1f - progress * 0.08f
                scaleX = s
                scaleY = s
                alpha = (entrance.value * (1f - progress * 0.6f)).coerceIn(0f, 1f)
            }

        CompositionLocalProvider(LocalChipAlpha provides (LocalChipAlpha.current + tuning.chipBoost).coerceAtMost(0.8f)) {
            if (effects.bedside) {
                BedsideContent(now, appearance.use24h, battery, accent, settings.gestures, controller, contentModifier)
            } else {
                val shown = settings.copy(appearance = appearance)
                val cut = cutout
                if (cut != null) {
                    // Depth: clock, then the photo's subject over it, then everything else on top.
                    MainContent(shown, effects, now, battery, accent, clockStyle, controller, contentModifier, ContentPart.CLOCK_ONLY, clockTracker)
                    DepthForeground(appearance, cut, accent, dim, backgroundModifier, clockTracker)
                    if (appearance.weatherFx) LiveWeatherParticles(settings.weather)
                    MainContent(shown, effects, now, battery, accent, clockStyle, controller, contentModifier, ContentPart.NO_CLOCK)
                } else {
                    if (appearance.weatherFx) LiveWeatherParticles(settings.weather)
                    MainContent(shown, effects, now, battery, accent, clockStyle, controller, contentModifier)
                }
            }
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

private val chipTypes = setOf(
    WidgetType.ALARM, WidgetType.BATTERY, WidgetType.WEATHER, WidgetType.STATS,
    WidgetType.AIR, WidgetType.UV, WidgetType.POLLEN,
)

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

/** One widget (or row of chips) in the layout, with what it needs to compete for space. */
private class ZoneItem(
    val group: WidgetGroup,
    val bottom: Boolean,
    val priority: WidgetPriority,
    /** May shrink to fit what's left instead of hiding (the notification list in the top zone). */
    val flexible: Boolean,
)

/**
 * The two-zone lock screen layout shared by the real lock screen and the settings preview.
 * Top-zone widgets stack under the clock; bottom-zone widgets stack above the unlock hint.
 *
 * Both zones share the space between the clock and the hint. When it runs out, widgets get space
 * by priority (then list position), and anything that doesn't fit is hidden whole rather than cut off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZonedLayout(
    settings: BrigSettings,
    align: Alignment.Horizontal,
    modifier: Modifier,
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
    showHeader: Boolean = true,
    showWidgets: Boolean = true,
    header: @Composable ColumnScope.() -> Unit,
    chip: @Composable (WidgetType) -> Unit,
    single: @Composable ColumnScope.(type: WidgetType, inBottom: Boolean) -> Unit,
    hint: @Composable () -> Unit,
) {
    val layout = settings.appearance.layout
    val enabled = if (showWidgets) settings.widgetOrder().filter { it.enabled } else emptyList()
    val configs = enabled.associateBy { it.type }

    fun items(zone: WidgetZone): List<ZoneItem> {
        val bottom = zone == WidgetZone.BOTTOM
        return groupWidgets(enabled.filter { it.zoneOrDefault() == zone }.map { it.type }).map { g ->
            val types = when (g) {
                is WidgetGroup.Chips -> g.types
                is WidgetGroup.Single -> listOf(g.type)
            }
            ZoneItem(
                group = g,
                bottom = bottom,
                priority = types.minOf { configs.getValue(it).priorityOrDefault() },
                flexible = !bottom && g == WidgetGroup.Single(WidgetType.NOTIFICATIONS),
            )
        }
    }
    val all = items(WidgetZone.TOP) + items(WidgetZone.BOTTOM)
    val order = remember(all.map { it.priority }) { spaceOrder(all.map { it.priority }) }

    val headerContent: @Composable () -> Unit = {
        // The header keeps its space when hidden so the widgets below don't move.
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = if (showHeader) 1f else 0f },
            verticalArrangement = Arrangement.spacedBy(layout.spacing.dp),
            content = header,
        )
    }
    val itemContents: List<@Composable () -> Unit> = all.map { item ->
        {
            val groupAlign = if (item.bottom) Alignment.CenterHorizontally else align
            Column(Modifier.fillMaxWidth()) {
                when (val g = item.group) {
                    is WidgetGroup.Chips -> FlowRow(
                        Modifier.align(groupAlign),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { g.types.forEach { chip(it) } }
                    is WidgetGroup.Single -> single(g.type, item.bottom)
                }
            }
        }
    }
    val hintContent: @Composable () -> Unit = { if (showWidgets) hint() }

    Layout(
        contents = listOf(headerContent, hintContent) + itemContents,
        modifier = modifier.fillMaxSize().padding(horizontal = layout.side.dp),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val loose = Constraints(maxWidth = w)
        val headerP = measurables[0].firstOrNull()?.measure(loose.copy(maxHeight = h))
        val hintP = measurables[1].firstOrNull()?.measure(loose.copy(maxHeight = h))
        val topPad = (extraTop + layout.top.dp).roundToPx()
        val bottomPad = (extraBottom + layout.bottom.dp).roundToPx()
        val topGap = layout.spacing.dp.roundToPx()
        val bottomGap = maxOf(layout.spacing, 12).dp.roundToPx()
        val headerH = headerP?.height ?: 0
        val hintH = hintP?.height ?: 0

        // Space for widgets: between the header and the hint, with a small gap between the zones.
        var remaining = h - topPad - headerH - bottomPad - hintH - 8.dp.roundToPx()
        val minFlexible = 48.dp.roundToPx()
        val placed = arrayOfNulls<Placeable>(all.size)
        for (i in order) {
            val item = all[i]
            val m = measurables[i + 2].firstOrNull() ?: continue
            val gap = if (item.bottom) bottomGap else topGap
            val room = remaining - gap
            if (item.flexible) {
                if (room < minFlexible) continue
                val p = m.measure(loose.copy(maxHeight = room))
                if (p.height > 0) { placed[i] = p; remaining -= p.height + gap }
            } else {
                val p = m.measure(loose.copy(maxHeight = h))
                if (p.height == 0) continue
                if (p.height <= room) { placed[i] = p; remaining -= p.height + gap }
            }
        }

        layout(w, h) {
            var y = topPad
            headerP?.placeRelative(0, y)
            y += headerH
            all.indices.filter { !all[it].bottom }.forEach { i ->
                placed[i]?.let { y += topGap; it.placeRelative(0, y); y += it.height }
            }
            val shownBottom = all.indices.filter { all[it].bottom }.mapNotNull { placed[it] }
            val hintTop = h - bottomPad - hintH
            var by = hintTop - shownBottom.sumOf { it.height + bottomGap }
            shownBottom.forEach { it.placeRelative(0, by); by += it.height + bottomGap }
            hintP?.placeRelative((w - hintP.width) / 2, hintTop)
        }
    }
}

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
    part: ContentPart = ContentPart.ALL,
    clockTracker: dev.pridgey.brig.util.ClockTracker? = null,
) {
    val appearance = settings.appearance
    val align = appearance.clockAlignment(clockStyle)
    val rotated by PhotoRotator.current.collectAsState()
    ZonedLayout(
        settings, align, modifier.systemBarsPadding(),
        showHeader = part != ContentPart.NO_CLOCK,
        showWidgets = part != ContentPart.CLOCK_ONLY,
        header = {
            if (settings.guest.active) GuestBanner(controller, Modifier.align(align))
            Clock(clockStyle, now, ClockSpec.from(appearance, accent), Modifier.align(align).onGloballyPositioned { clockTracker?.report(it) })
            SleepNudge(now.hour * 60 + now.minute, settings.sleep, Modifier.align(align))
            appearance.secondaryZone?.let { z -> SecondaryZone(z, now, appearance.use24h, Modifier.align(align)) }
            val caption = rotated?.caption
            if (caption != null && settings.photos.source != PhotoSource.SINGLE && appearance.background == BackgroundType.IMAGE) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(align), maxLines = 1)
            }
            if (effects.activeNames.isNotEmpty()) {
                Text(
                    effects.activeNames.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.align(align),
                )
            }
        },
        chip = { type ->
            when (type) {
                WidgetType.ALARM -> AlarmChip()
                WidgetType.BATTERY -> BatteryChip(battery, accent)
                WidgetType.WEATHER -> WeatherChip(settings.weather)
                WidgetType.STATS -> StatsChip()
                WidgetType.AIR -> AirChip(settings.weather)
                WidgetType.UV -> UvChip(settings.weather)
                WidgetType.POLLEN -> PollenChip(settings.weather)
                else -> Unit
            }
        },
        single = { type, inBottom ->
            val a = if (inBottom) Alignment.CenterHorizontally else align
            when (type) {
                WidgetType.DATE -> if (appearance.smartDate && !settings.guest.active) {
                    SmartDateLine(now, appearance.travelBuffer, Modifier.align(a))
                } else Text(
                    now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(a),
                )
                WidgetType.MOOD -> LiveMood()
                WidgetType.FLASHCARD -> LiveFlashcard(settings.flashcards, accent)
                WidgetType.CALENDAR -> LiveCalendar(now, settings.calendar, accent)
                WidgetType.MEDIA -> LiveMedia(settings.media, accent, now)
                WidgetType.QUOTE -> QuoteWidget(now)
                WidgetType.NOTIFICATIONS -> if (!effects.hideNotifications) {
                    // In the top zone the list may shrink to make room; at the bottom it's capped by "max shown".
                    Box(if (inBottom) Modifier else Modifier.weight(1f, fill = false)) {
                        NotificationsWidget(settings, effects, controller)
                    }
                }
                WidgetType.SHORTCUTS -> if (settings.shortcuts.isNotEmpty()) {
                    Box(Modifier.align(a)) { ShortcutsRow(settings.shortcuts, controller, guest = settings.guest.active) }
                }
                WidgetType.TOGGLES -> Box(Modifier.align(a)) { TogglesRow(accent, controller, guestToggle = settings.guest.toggle) }
                WidgetType.LIVE -> if (!effects.hideNotifications) LiveActivitiesWidget(settings, effects, controller)
                WidgetType.ALARM_CARD -> LiveAlarmCard(accent, controller)
                WidgetType.HOURLY -> LiveHourly(settings.weather, accent, now)
                WidgetType.HEALTH -> if (settings.health.style == dev.pridgey.brig.data.HealthStyle.CHIP) {
                    Box(Modifier.align(a)) { LiveHealth(settings.health, accent, now) }
                } else LiveHealth(settings.health, accent, now)
                WidgetType.SPORTS -> LiveSports(settings.sports, now)
                WidgetType.PACKAGES -> LivePackages(settings.packages, accent, controller)
                WidgetType.MEMORIES -> LiveMemories(controller)
                else -> Unit
            }
        },
        hint = {
            if (settings.guest.active) {
                Text("Guest mode", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            } else UnlockHint(settings.gestures, controller.systemLockSecure, accent)
        },
    )
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
            Clock(
                ClockStyle.DIGITAL, now,
                ClockSpec(use24h, false, false, 1.25f, warm.copy(alpha = 0.8f), warm.copy(alpha = 0.75f), dev.pridgey.brig.util.BrigFonts.clock, null),
            )
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
internal fun GlanceChip(text: String, icon: ImageVector? = null, emoji: String? = null, tint: Color = Color.White, dimmed: Boolean = false) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LocalChipAlpha.current * if (dimmed) 0.4f else 1f))
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
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Color.White.copy(alpha = 0.11f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
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
private fun LiveCalendar(now: LocalDateTime, cfg: dev.pridgey.brig.data.CalendarConfig, accent: Color) {
    val ctx = LocalContext.current
    val events by produceState(emptyList<CalEvent>(), now.hour, now.minute / 5, cfg) {
        value = withContext(Dispatchers.IO) { CalendarRepo.upcoming(ctx, cfg.count, cfg.lookaheadHours, cfg.showAllDay) }
    }
    CalendarView(events, cfg, accent, System.currentTimeMillis())
}

@Composable
private fun LiveMedia(cfg: dev.pridgey.brig.data.MediaConfig, accent: Color, now: LocalDateTime) {
    val media by MediaRepo.state.collectAsState()
    // Re-check each minute so a long-paused session drops off.
    val m = remember(media, cfg.visibility, now.minute) { media?.takeIf { it.isVisible(cfg.visibility) } } ?: return
    val ui = MediaUi(m.title, m.artist, m.appLabel, m.art, m.playing, m.positionMs, m.durationMs, m.speed, m.updatedAt)
    val tc = m.controller.transportControls
    MediaView(ui, cfg, accent, MediaActions(prev = { tc.skipToPrevious() }, toggle = { if (m.playing) tc.pause() else tc.play() }, next = { tc.skipToNext() }))
}

@Composable
internal fun QuoteWidget(now: LocalDateTime) {
    val (quote, author) = Quotes.forDay(now.dayOfYear)
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("“$quote”", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.9f))
        Text("— $author", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- Bottom bar

@Composable
private fun ShortcutsRow(shortcuts: List<String>, controller: LockController, guest: Boolean = false) {
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
                    .clickable(source, null) { if (guest) controller.launchGuestApp(pkg) else controller.launchApp(pkg) },
                contentAlignment = Alignment.Center,
            ) {
                icon?.let { Image(it, AppsRepo.label(ctx, pkg), Modifier.size(34.dp)) }
            }
        }
    }
}

@Composable
private fun TogglesRow(accent: Color, controller: LockController, guestToggle: Boolean = false) {
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
        if (guestToggle) MorphToggle(Icons.Filled.Person, "Guest mode", false, accent) { controller.enterGuest() }
    }
}

@Composable
private fun GuestBanner(controller: LockController, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, null, Modifier.size(16.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text("Guest mode", style = MaterialTheme.typography.labelLarge, color = Color.White)
        TextButton(onClick = { controller.exitGuest() }) { Text("Exit", color = MaterialTheme.colorScheme.primary) }
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
internal fun UnlockHint(gestures: Gestures, fingerprint: Boolean, accent: Color) {
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

// ---------------------------------------------------------------- Preview mock

/**
 * A stand-in for the lock screen used by the settings previews. Same layout as the real thing,
 * filled with sample data, at true phone size (callers scale it down).
 */
@Composable
fun LockMock(settings: BrigSettings) {
    CompositionLocalProvider(LocalChipAlpha provides settings.appearance.chipOpacity) { LockMockContent(settings) }
}

private fun sampleHours(now: Long) = List(7) { i ->
    dev.pridgey.brig.data.HourWeather(now + i * 3600_000L, 72 - i, if (i in 2..4) 61 else 2, if (i in 2..4) 70 else 5, true)
}

private fun sampleGames(now: Long) = listOf(
    dev.pridgey.brig.data.Game("SEA", "SF", true, "21", "17", dev.pridgey.brig.data.GameState.LIVE, "Q3 5:12", now, 0xFF002244, 0xFFAA0000),
    dev.pridgey.brig.data.Game("BOS", "NYY", false, "", "", dev.pridgey.brig.data.GameState.PRE, "Tue 7:10 PM", now, 0xFFBD3039, 0xFF0C2340),
)

@Composable
private fun LockMockContent(settings: BrigSettings) {
    val ctx = LocalContext.current
    // Show the photo the lock screen would: the current rotating one when a source is set.
    val rotated by PhotoRotator.current.collectAsState()
    val rotatedPath = rotated?.path?.takeIf { settings.photos.source != PhotoSource.SINGLE }
    val a = settings.appearance.let { if (rotatedPath != null) it.copy(imagePath = rotatedPath) else it }
        .let { it.withLookFor(settings.photos, it.imagePath) }
    val cut = rememberCutout(a.imagePath, a.depth && a.background == BackgroundType.IMAGE && a.imagePath != null)
    val clockTracker = remember { dev.pridgey.brig.util.ClockTracker() }
    val now by rememberNow(a.showSeconds)
    val accent = resolveAccent(a.accent)
    val align = a.clockAlignment()
    val nowMs = remember(now.minute) { System.currentTimeMillis() }
    val events = remember(nowMs, settings.calendar.count) { sampleEvents(nowMs).take(settings.calendar.count) }
    @Composable
    fun Layer(showHeader: Boolean, showWidgets: Boolean) {
        ZonedLayout(
            settings, align, Modifier.fillMaxSize(), extraTop = 44.dp, extraBottom = 24.dp,
            showHeader = showHeader, showWidgets = showWidgets,
            header = {
                Clock(a.clockStyle, now, ClockSpec.from(a, accent), Modifier.align(align).onGloballyPositioned { clockTracker.report(it) })
                a.secondaryZone?.let { SecondaryZone(it, now, a.use24h, Modifier.align(align)) }
            },
            chip = { type ->
                when (type) {
                    WidgetType.ALARM -> GlanceChip("7:00 AM", icon = Icons.Filled.Alarm)
                    WidgetType.BATTERY -> GlanceChip("82%", icon = Icons.Filled.BatteryStd)
                    WidgetType.WEATHER -> GlanceChip("72°  75°/60°", emoji = "🌤️")
                    WidgetType.STATS -> GlanceChip("12 unlocks · 30 wakes", emoji = "📊")
                    WidgetType.AIR -> GlanceChip("AQI 38 · Good", emoji = "🌬️")
                    WidgetType.UV -> GlanceChip("UV 4 · Moderate", emoji = "☀️")
                    WidgetType.POLLEN -> GlanceChip("Grass pollen · Low", emoji = "🌾")
                    else -> Unit
                }
            },
            single = { type, inBottom ->
                val al = if (inBottom) Alignment.CenterHorizontally else align
                when (type) {
                    WidgetType.DATE -> Text(
                        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.align(al),
                    )
                    WidgetType.MEDIA -> MediaView(sampleMedia(), settings.media, accent, MediaActions.None)
                    WidgetType.CALENDAR -> CalendarView(events, settings.calendar, accent, nowMs)
                    WidgetType.QUOTE -> QuoteWidget(now)
                    WidgetType.LIVE -> GlassCard {
                        Text("Timer · Pasta", style = MaterialTheme.typography.titleSmall)
                        Text("7:42", style = MaterialTheme.typography.headlineSmall)
                        androidx.compose.material3.LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = accent, trackColor = Color.White.copy(alpha = 0.15f))
                    }
                    WidgetType.MOOD -> MoodView(mapOf(java.time.LocalDate.now().minusDays(1).toString() to 3, java.time.LocalDate.now().minusDays(2).toString() to 4), java.time.LocalDate.now()) {}
                    WidgetType.FLASHCARD -> FlashcardView(dev.pridgey.brig.data.Flashcards.today(settings.flashcards, java.time.LocalDate.now(), 0), "Word of the day", accent)
                    WidgetType.ALARM_CARD -> AlarmCardView(nowMs + 8 * 3600_000L, accent, {}, {})
                    WidgetType.HOURLY -> HourlyView(sampleHours(nowMs), if (settings.weather.rainAlert) "Rain in 25 min" else null, accent)
                    WidgetType.HEALTH -> if (settings.health.style == dev.pridgey.brig.data.HealthStyle.CHIP) {
                        Box(Modifier.align(al)) { HealthView(dev.pridgey.brig.data.HealthToday(6231, 310, 22), settings.health, accent) }
                    } else HealthView(dev.pridgey.brig.data.HealthToday(6231, 310, 22), settings.health, accent)
                    WidgetType.SPORTS -> SportsView(sampleGames(nowMs))
                    WidgetType.PACKAGES -> PackagesView(
                        listOf(dev.pridgey.brig.data.TrackedPackage("1Z999", "UPS", "Running shoes", "Out for delivery", nowMs - 40 * 60_000L)),
                        accent,
                    ) {}
                    WidgetType.MEMORIES -> GlassCard {
                        Text("On this day · 3 years ago", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { i ->
                                Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.25f + i * 0.15f)))
                            }
                        }
                    }
                    WidgetType.NOTIFICATIONS -> if (settings.notifications.glanceMode) GlanceChip("Messages · Mail · Slack", emoji = "🔔") else GlassCard {
                        Text("Messages · 2m", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        Text(if (settings.notifications.hideAllContent) "Content hidden" else "Sam", style = MaterialTheme.typography.titleSmall)
                        if (!settings.notifications.hideAllContent) {
                            Text("Running 5 min late! Grab us a table?", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    WidgetType.SHORTCUTS -> if (settings.shortcuts.isNotEmpty()) {
                        Row(Modifier.align(al), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            settings.shortcuts.forEachIndexed { i, pkg ->
                                Box(
                                    Modifier.size(52.dp).clip(PolygonShape(BrigShapes.badges[i % BrigShapes.badges.size])).background(Color.White.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) { remember(pkg) { AppsRepo.icon(ctx, pkg) }?.let { Image(it, null, Modifier.size(34.dp)) } }
                            }
                        }
                    }
                    WidgetType.TOGGLES -> Row(Modifier.align(al), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        listOf(Icons.Filled.FlashlightOff, Icons.Filled.DoNotDisturbOff, Icons.AutoMirrored.Filled.VolumeUp).forEach {
                            Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                Icon(it, null, tint = Color.White)
                            }
                        }
                    }
                    else -> Unit
                }
            },
            hint = { UnlockHint(settings.gestures, false, accent) },
        )
    }

    Box(Modifier.fillMaxSize().onPlaced { clockTracker.root = it }) {
        LockBackground(a, accent)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a.dim)))
        if (cut != null) {
            // Depth: clock, then the subject, then the widgets, like the real lock screen.
            Layer(showHeader = true, showWidgets = false)
            DepthForeground(a, cut, accent, a.dim, clock = clockTracker)
            Layer(showHeader = false, showWidgets = true)
        } else {
            Layer(showHeader = true, showWidgets = true)
        }
    }
}
