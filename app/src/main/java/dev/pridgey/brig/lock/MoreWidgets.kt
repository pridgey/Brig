package dev.pridgey.brig.lock

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pridgey.brig.data.AirNow
import dev.pridgey.brig.data.AirRepo
import dev.pridgey.brig.data.Game
import dev.pridgey.brig.data.GameState
import dev.pridgey.brig.data.HealthConfig
import dev.pridgey.brig.data.HealthRepo
import dev.pridgey.brig.data.HealthStyle
import dev.pridgey.brig.data.HealthToday
import dev.pridgey.brig.data.HourWeather
import dev.pridgey.brig.data.Memories
import dev.pridgey.brig.data.Memory
import dev.pridgey.brig.data.PackageParse
import dev.pridgey.brig.data.PackageRepo
import dev.pridgey.brig.data.PackagesConfig
import dev.pridgey.brig.data.SportsConfig
import dev.pridgey.brig.data.SportsRepo
import dev.pridgey.brig.data.TrackedPackage
import dev.pridgey.brig.data.WeatherConfig
import dev.pridgey.brig.data.WeatherDetail
import dev.pridgey.brig.data.WeatherDetailRepo
import dev.pridgey.brig.data.WeatherRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dim = Color.White.copy(alpha = 0.65f)

// ---------------------------------------------------------------- Alarm card

/** "in 7h 12m" until [t]. */
internal fun untilLabel(t: Long, now: Long = System.currentTimeMillis()): String {
    val mins = ((t - now) / 60_000).coerceAtLeast(0)
    return when {
        mins < 60 -> "in $mins min"
        mins < 24 * 60 -> "in ${mins / 60}h" + if (mins % 60 != 0L) " ${mins % 60}m" else ""
        else -> "in ${mins / (24 * 60)}d ${(mins % (24 * 60)) / 60}h"
    }
}

@Composable
fun AlarmCardView(time: Long?, accent: Color, onSkip: () -> Unit, onOpen: () -> Unit) {
    if (time == null) return
    val z = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val soon = time - System.currentTimeMillis() < 24 * 3600_000L
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Alarm, null, tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(z.format(DateTimeFormatter.ofPattern(if (soon) "h:mm a" else "EEE h:mm a")), style = MaterialTheme.typography.titleLarge)
                Text(untilLabel(time), style = MaterialTheme.typography.bodySmall, color = dim)
            }
            TextButton(onClick = onOpen) { Text("Open", color = Color.White) }
            FilledTonalButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
internal fun LiveAlarmCard(accent: Color, controller: LockController) {
    val ctx = LocalContext.current
    val info = remember { ctx.getSystemService(AlarmManager::class.java).nextAlarmClock }
    AlarmCardView(info?.triggerTime, accent, onSkip = { controller.skipNextAlarm() }, onOpen = { controller.openAlarm(info?.showIntent) })
}

// ---------------------------------------------------------------- Hourly weather

@Composable
fun HourlyView(hours: List<HourWeather>, alert: String?, accent: Color) {
    if (hours.isEmpty() && alert == null) return
    GlassCard {
        alert?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Icon(Icons.Filled.Umbrella, null, Modifier.size(18.dp), tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            hours.take(7).forEachIndexed { i, h ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = if (i == 0) "Now" else Instant.ofEpochMilli(h.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("ha")).lowercase()
                    Text(label, style = MaterialTheme.typography.labelSmall, color = dim)
                    Text(WeatherRepo.describe(h.code, h.isDay).first, style = MaterialTheme.typography.titleMedium)
                    Text("${h.temp}°", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (h.rainChance >= 20) "${h.rainChance}%" else " ",
                        style = MaterialTheme.typography.labelSmall, color = accent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveHourly(cfg: WeatherConfig, accent: Color, now: LocalDateTime) {
    val ctx = LocalContext.current
    if (cfg.lat == null) {
        GlanceChip("Set a city in Brig for hourly weather", emoji = "🌡️")
        return
    }
    val d by produceState<WeatherDetail?>(null, cfg, now.hour) { value = WeatherDetailRepo.load(ctx, cfg) }
    val detail = d ?: return
    val nowMs = System.currentTimeMillis()
    val hours = detail.hours.filter { it.time + 3600_000L > nowMs }
    val snow = hours.firstOrNull()?.code in 71..86
    val alert = if (cfg.rainAlert) WeatherDetailRepo.rainAlert(detail.precip15, detail.precipStart, nowMs, snow) else null
    HourlyView(hours, alert, accent)
}

// ---------------------------------------------------------------- Air, UV, pollen chips

@Composable
private fun rememberAir(cfg: WeatherConfig): AirNow? {
    val ctx = LocalContext.current
    val a by produceState<AirNow?>(null, cfg.lat, cfg.lon) { value = AirRepo.load(ctx, cfg) }
    return a
}

@Composable
internal fun AirChip(cfg: WeatherConfig) {
    val aqi = rememberAir(cfg)?.usAqi ?: return
    GlanceChip("AQI $aqi · ${AirRepo.aqiLabel(aqi)}", emoji = "🌬️")
}

@Composable
internal fun UvChip(cfg: WeatherConfig) {
    val uv = rememberAir(cfg)?.uv ?: return
    GlanceChip("UV ${"%.0f".format(uv)} · ${AirRepo.uvLabel(uv)}", emoji = if (uv >= 6) "🕶️" else "☀️")
}

@Composable
internal fun PollenChip(cfg: WeatherConfig) {
    val (name, level) = rememberAir(cfg)?.let { AirRepo.pollenSummary(it.pollen) } ?: return
    GlanceChip("$name pollen · $level", emoji = "🌾")
}

// ---------------------------------------------------------------- Health

@Composable
private fun Rings(values: List<Float>, colors: List<Color>, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        values.forEachIndexed { i, v ->
            val inset = stroke / 2 + i * stroke * 1.25f
            val topLeft = Offset(inset, inset)
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(colors[i].copy(alpha = 0.22f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            drawArc(colors[i], -90f, 360f * v.coerceIn(0f, 1f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun HealthView(h: HealthToday, cfg: HealthConfig, accent: Color) {
    val colors = listOf(accent, Color(0xFFFF7B54), Color(0xFF7BD88F))
    val values = listOf(
        h.steps.toFloat() / cfg.stepGoal.coerceAtLeast(1),
        h.calories.toFloat() / cfg.calorieGoal.coerceAtLeast(1),
        h.activeMinutes.toFloat() / cfg.minutesGoal.coerceAtLeast(1),
    )
    val labels = listOf("%,d steps".format(h.steps), "${h.calories} cal", "${h.activeMinutes} min")
    when (cfg.style) {
        HealthStyle.CHIP -> GlanceChip(labels[0], emoji = "👟")
        HealthStyle.RINGS -> GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Rings(values, colors, Modifier.size(76.dp))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    labels.forEachIndexed { i, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                            Spacer(Modifier.width(8.dp))
                            Text(l, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        HealthStyle.BARS -> GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    Column {
                        Text(l, style = MaterialTheme.typography.labelLarge)
                        LinearProgressIndicator(
                            progress = { values[i].coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = colors[i], trackColor = colors[i].copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveHealth(cfg: HealthConfig, accent: Color, now: LocalDateTime) {
    val ctx = LocalContext.current
    val h by produceState<HealthToday?>(null, now.minute / 5) { value = HealthRepo.today(ctx) }
    h?.let { HealthView(it, cfg, accent) }
}

// ---------------------------------------------------------------- Sports

@Composable
private fun TeamBadge(abbr: String, color: Long?) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(color?.let { Color(it) } ?: Color.White.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) { Text(abbr.take(4), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
}

@Composable
fun SportsView(games: List<Game>) {
    if (games.isEmpty()) return
    val pulse = rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            games.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamBadge(g.teamAbbr, g.teamColor)
                    Spacer(Modifier.width(8.dp))
                    if (g.state == GameState.PRE) {
                        Text(if (g.home) "vs" else "@", style = MaterialTheme.typography.labelMedium, color = dim)
                    } else {
                        Text("${g.teamScore} – ${g.oppScore}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    TeamBadge(g.oppAbbr, g.oppColor)
                    Spacer(Modifier.width(12.dp))
                    Text(g.detail, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (g.state == GameState.LIVE) {
                        Box(
                            Modifier.clip(CircleShape).background(Color(0xFFE53935).copy(alpha = a)).padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveSports(cfg: SportsConfig, now: LocalDateTime) {
    val ctx = LocalContext.current
    val games by produceState(emptyList<Game>(), cfg, now.minute) { value = SportsRepo.games(ctx, cfg) }
    SportsView(games)
}

// ---------------------------------------------------------------- Packages

@Composable
fun PackagesView(list: List<TrackedPackage>, accent: Color, onOpen: (TrackedPackage) -> Unit) {
    if (list.isEmpty()) return
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.take(4).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(p) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (p.delivered) Color(0xFF7BD88F).copy(alpha = 0.3f) else accent.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.LocalShipping, null, Modifier.size(20.dp), tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label.ifBlank { "${p.carrier} package" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(p.status, p.carrier).filter { it.isNotBlank() }.joinToString(" · ") +
                                if (p.updatedAt > 0) " · " + DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) else "",
                            style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LivePackages(cfg: PackagesConfig, accent: Color, controller: LockController) {
    val ctx = LocalContext.current
    remember { PackageRepo.load(ctx) }
    val all by PackageRepo.packages.collectAsState()
    PackagesView(PackageRepo.visible(all, cfg), accent) { p ->
        PackageParse.trackingUrl(p)?.let { controller.openUri(Uri.parse(it)) }
    }
}

// ---------------------------------------------------------------- On this day

@Composable
fun MemoriesView(thumbs: List<ImageBitmap>, caption: String, onOpen: (Int) -> Unit) {
    if (thumbs.isEmpty()) return
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = dim)
            Spacer(Modifier.width(8.dp))
            Text(caption, style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            thumbs.take(3).forEachIndexed { i, t ->
                Image(
                    t, null,
                    Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(i) }
                        .graphicsLayer { rotationZ = (i - 1) * 1.5f },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
internal fun LiveMemories(controller: LockController) {
    val ctx = LocalContext.current
    val data by produceState<Pair<List<Memory>, List<ImageBitmap>>?>(null) {
        value = withContext(Dispatchers.IO) {
            // Ask for extras in case some thumbnails fail to load, and keep each photo with its thumbnail.
            val loaded = Memories.onThisDay(ctx, 6).mapNotNull { m -> Memories.thumbnail(ctx, m)?.let { m to it } }.take(3)
            loaded.map { it.first } to loaded.map { it.second }
        }
    }
    val (memories, thumbs) = data ?: return
    if (memories.isEmpty()) return
    val years = memories.map { it.yearsAgo }.distinct()
    val caption = when {
        years.size == 1 && years[0] == 1 -> "On this day · 1 year ago"
        years.size == 1 -> "On this day · ${years[0]} years ago"
        else -> "On this day · ${years.min()}–${years.max()} years ago"
    }
    MemoriesView(thumbs, caption) { i ->
        memories.getOrNull(i)?.let { controller.openUri(it.uri, "image/*") }
    }
}

// ---------------------------------------------------------------- Mood check-in

@Composable
fun MoodView(map: Map<String, Int>, today: java.time.LocalDate, onPick: (Int) -> Unit) {
    val logged = map[today.toString()]
    var picking by androidx.compose.runtime.remember(logged) { androidx.compose.runtime.mutableStateOf(logged == null) }
    GlassCard {
        if (picking) {
            Text("How are you today?", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                dev.pridgey.brig.data.MoodStore.faces.forEachIndexed { i, f ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(if (logged == i) Color(dev.pridgey.brig.data.MoodStore.colors[i]).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f))
                            .clickable { onPick(i); picking = false },
                        contentAlignment = Alignment.Center,
                    ) { Text(f, style = MaterialTheme.typography.headlineSmall) }
                }
            }
        } else {
            val streak = dev.pridgey.brig.data.MoodStore.streak(map, today)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { picking = true }) {
                Text(dev.pridgey.brig.data.MoodStore.faces[logged ?: 2], style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Checked in", style = MaterialTheme.typography.titleSmall)
                    Text(if (streak > 1) "$streak-day streak · tap to change" else "Tap to change", style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
            // This month as a grid of colored dots.
            val first = today.withDayOfMonth(1)
            val days = today.lengthOfMonth()
            val offset = first.dayOfWeek.value % 7
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until (offset + days + 6) / 7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0 until 7).forEach { col ->
                            val day = week * 7 + col - offset + 1
                            val mood = if (day in 1..days) map[first.withDayOfMonth(day).toString()] else null
                            Box(
                                Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(
                                    when {
                                        day !in 1..days -> Color.Transparent
                                        mood != null -> Color(dev.pridgey.brig.data.MoodStore.colors[mood])
                                        day > today.dayOfMonth -> Color.White.copy(alpha = 0.04f)
                                        else -> Color.White.copy(alpha = 0.12f)
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveMood() {
    val ctx = LocalContext.current
    remember { dev.pridgey.brig.data.MoodStore.load(ctx) }
    val map by dev.pridgey.brig.data.MoodStore.moods.collectAsState()
    val today = java.time.LocalDate.now()
    MoodView(map, today) { dev.pridgey.brig.data.MoodStore.set(ctx, today, it) }
}

// ---------------------------------------------------------------- Flashcards

@Composable
fun FlashcardView(card: dev.pridgey.brig.data.Flashcard, title: String, accent: Color) {
    var flipped by androidx.compose.runtime.remember(card) { androidx.compose.runtime.mutableStateOf(false) }
    val angle by androidx.compose.animation.core.animateFloatAsState(
        if (flipped) 180f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 220f), label = "flip",
    )
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            rotationY = angle
            cameraDistance = 14f * density
        }.clip(RoundedCornerShape(26.dp)).clickable { flipped = !flipped },
    ) {
        GlassCard {
            // Undo the mirror on the back face.
            Column(Modifier.fillMaxWidth().graphicsLayer { rotationY = if (angle > 90f) 180f else 0f }) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                if (angle <= 90f) {
                    Text(card.front, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (card.hint.isNotBlank()) "${card.hint} · tap to flip" else "Tap to flip", style = MaterialTheme.typography.bodySmall, color = dim)
                } else {
                    Text(card.back, style = MaterialTheme.typography.bodyLarge)
                    Text(card.front, style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
        }
    }
}

@Composable
internal fun LiveFlashcard(cfg: dev.pridgey.brig.data.FlashcardConfig, accent: Color) {
    val ctx = LocalContext.current
    val card = remember(cfg) {
        dev.pridgey.brig.data.Flashcards.today(cfg, java.time.LocalDate.now(), dev.pridgey.brig.data.Flashcards.lockCount(ctx))
    }
    FlashcardView(card, if (cfg.source == dev.pridgey.brig.data.FlashcardSource.WORDS) "Word of the day" else "Flashcard", accent)
}

// ---------------------------------------------------------------- What's next

@Composable
internal fun SmartDateLine(now: LocalDateTime, travelBuffer: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val events by produceState(emptyList<dev.pridgey.brig.data.CalEvent>(), now.hour, now.minute) {
        value = withContext(Dispatchers.IO) { dev.pridgey.brig.data.CalendarRepo.upcoming(ctx, 4, 4, includeAllDay = false) }
    }
    val next = dev.pridgey.brig.data.NextUp.label(events, System.currentTimeMillis(), travelBuffer)
    androidx.compose.animation.AnimatedContent(next, modifier, label = "nextup") { label ->
        Column {
            Text(
                label ?: now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (label != null) {
                Text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.labelMedium, color = dim)
            }
        }
    }
}

// ---------------------------------------------------------------- Sleep nudge

@Composable
internal fun SleepNudge(minute: Int, cfg: dev.pridgey.brig.data.SleepConfig, modifier: Modifier) {
    if (!dev.pridgey.brig.data.Sleep.isLate(minute, cfg)) return
    val past = dev.pridgey.brig.data.Sleep.minutesPast(minute, cfg.bedtime)
    Row(
        modifier.clip(CircleShape).background(Color(0xFF3A3F7A).copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🌙", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(dev.pridgey.brig.data.Sleep.message(past), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
    }
}
