package dev.pridgey.brig.lock

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.pridgey.brig.data.CalEvent
import dev.pridgey.brig.data.CalendarConfig
import dev.pridgey.brig.data.CalendarRepo
import dev.pridgey.brig.data.CalendarStyle
import dev.pridgey.brig.data.MediaConfig
import dev.pridgey.brig.data.MediaStyle
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.MorphShape
import dev.pridgey.brig.util.mix
import dev.pridgey.brig.util.pressScale

// ---------------------------------------------------------------- Media

/** What the media widget shows. Decoupled from MediaController so previews can use sample data. */
data class MediaUi(
    val title: String,
    val artist: String,
    val appLabel: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    /** elapsedRealtime when [positionMs] was measured; 0 to freeze the progress. */
    val measuredAt: Long,
)

class MediaActions(val prev: () -> Unit, val toggle: () -> Unit, val next: () -> Unit) {
    companion object {
        val None = MediaActions({}, {}, {})
    }
}

/** 0..1 playback progress, ticking while playing. */
@Composable
private fun rememberProgress(ui: MediaUi): Float {
    val p by produceState(0f, ui) {
        while (true) {
            val pos = if (ui.playing && ui.measuredAt > 0) {
                ui.positionMs + ((SystemClock.elapsedRealtime() - ui.measuredAt) * ui.speed).toLong()
            } else ui.positionMs
            value = if (ui.durationMs > 0) (pos.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
            if (!ui.playing) break
            kotlinx.coroutines.delay(500)
        }
    }
    return p
}

/** Average color of the artwork, for tinting. */
@Composable
private fun rememberArtColor(art: ImageBitmap?): Color? = remember(art) {
    art?.let {
        runCatching {
            val px = android.graphics.Bitmap.createScaledBitmap(it.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false), 1, 1, true).getPixel(0, 0)
            Color(px)
        }.getOrNull()
    }
}

@Composable
fun MediaView(ui: MediaUi, cfg: MediaConfig, accent: Color, actions: MediaActions) {
    val artColor = rememberArtColor(ui.art)
    val tint = if (cfg.tintFromArt) artColor else null
    val highlight = tint?.mix(Color.White, 0.35f) ?: accent
    when (cfg.style) {
        MediaStyle.CARD -> MediaCard(ui, cfg, tint, highlight, actions)
        MediaStyle.ARTWORK -> MediaArtwork(ui, cfg, tint, highlight, actions)
        MediaStyle.COMPACT -> MediaCompact(ui, tint, highlight, actions)
        MediaStyle.MINIMAL -> MediaMinimal(ui, highlight, actions)
    }
}

@Composable
private fun ArtThumb(art: ImageBitmap?, size: Dp, corner: Dp, fallback: Color) {
    if (art != null) {
        Image(art, null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(fallback.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, tint = Color.White)
        }
    }
}

/** Play/pause that morphs between a cookie (paused) and a circle (playing). */
@Composable
private fun MorphPlayButton(playing: Boolean, color: Color, size: Dp, onClick: () -> Unit) {
    val morph = remember { Morph(BrigShapes.cookie9, BrigShapes.circle) }
    val progress by animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "morph")
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressScale(source, 0.85f)
            .clip(MorphShape(morph, progress, (1f - progress) * 30f))
            .background(color)
            .clickable(source, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = Color.Black)
    }
}

@Composable
private fun ProgressLine(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(color))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun MediaCard(ui: MediaUi, cfg: MediaConfig, tint: Color?, highlight: Color, actions: MediaActions) {
    val progress = rememberProgress(ui)
    GlassCard(color = tint?.copy(alpha = 0.4f) ?: Color.White.copy(alpha = 0.11f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(ui.art, 58.dp, 16.dp, highlight)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ui.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.appLabel, style = MaterialTheme.typography.labelSmall, color = highlight)
            }
            IconButton(onClick = actions.prev) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White) }
            MorphPlayButton(ui.playing, highlight, 50.dp, actions.toggle)
            IconButton(onClick = actions.next) { Icon(Icons.Filled.SkipNext, "Next", tint = Color.White) }
        }
        if (cfg.showProgress && ui.durationMs > 0) ProgressLine(progress, highlight, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun MediaArtwork(ui: MediaUi, cfg: MediaConfig, tint: Color?, highlight: Color, actions: MediaActions) {
    val progress = rememberProgress(ui)
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(tint?.mix(Color.Black, 0.4f) ?: Color(0xFF1A1C22))) {
        // Blurred artwork as the card background.
        ui.art?.let {
            Image(it, null, Modifier.matchParentSize().blur(40.dp).graphicsLayer { alpha = 0.7f }, contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.55f)))))
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtThumb(ui.art, 96.dp, 22.dp, highlight)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.appLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = highlight)
                    Text(ui.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ui.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cfg.showProgress && ui.durationMs > 0) {
                Spacer(Modifier.height(16.dp))
                ProgressLine(progress, highlight)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(fmt((progress * ui.durationMs).toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                    Text(fmt(ui.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.prev) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp)) }
                MorphPlayButton(ui.playing, highlight, 64.dp, actions.toggle)
                IconButton(onClick = actions.next) { Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCompact(ui: MediaUi, tint: Color?, highlight: Color, actions: MediaActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(tint?.copy(alpha = 0.4f) ?: Color.White.copy(alpha = 0.11f))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.art != null) {
            Image(ui.art, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(42.dp).clip(CircleShape).background(highlight.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, null, tint = Color.White)
            }
        }
        Text(
            "${ui.title}  ·  ${ui.artist}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).basicMarquee(),
        )
        MorphPlayButton(ui.playing, highlight, 42.dp, actions.toggle)
    }
}

@Composable
private fun MediaMinimal(ui: MediaUi, highlight: Color, actions: MediaActions) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        // Little animated equalizer bars while playing.
        Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0, 180, 360).forEach { delay ->
                val h by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = delay, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar")
                Box(Modifier.width(3.dp).fillMaxHeight(if (ui.playing) h else 0.3f).clip(CircleShape).background(highlight))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            ui.title + if (ui.artist.isNotBlank()) " — ${ui.artist}" else "",
            style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = actions.toggle) {
            Icon(if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = highlight)
        }
    }
}

// ---------------------------------------------------------------- Calendar

@Composable
fun CalendarView(events: List<CalEvent>, cfg: CalendarConfig, accent: Color, nowMs: Long) {
    if (events.isEmpty()) return
    when (cfg.style) {
        CalendarStyle.LIST -> CalendarList(events, accent, cfg.useEventColors)
        CalendarStyle.TIMELINE -> CalendarTimeline(events, accent, nowMs, cfg.useEventColors)
        CalendarStyle.NEXT_UP -> CalendarNextUp(events, accent, nowMs, cfg.useEventColors)
        CalendarStyle.CHIPS -> CalendarChips(events, accent, cfg.useEventColors)
    }
}

/** Accent by default; the event's own calendar color only if the user opted in. */
private fun eventColor(e: CalEvent, accent: Color, useEventColors: Boolean) =
    if (useEventColors && e.color != 0) Color(e.color) else accent

@Composable
private fun CalendarList(events: List<CalEvent>, accent: Color, useEventColors: Boolean) {
    GlassCard {
        events.forEach { e ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(eventColor(e, accent, useEventColors)))
                Spacer(Modifier.width(10.dp))
                Text(CalendarRepo.whenLabel(e), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.widthIn(min = 84.dp))
                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CalendarTimeline(events: List<CalEvent>, accent: Color, nowMs: Long, useEventColors: Boolean) {
    val pulse = rememberInfiniteTransition(label = "now")
    val a by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    GlassCard {
        events.forEachIndexed { i, e ->
            val current = !e.allDay && e.begin <= nowMs && nowMs < e.end
            val color = eventColor(e, accent, useEventColors)
            Row {
                Text(
                    CalendarRepo.whenLabel(e),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) color else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.width(76.dp).padding(top = 2.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
                    Box(
                        Modifier.padding(top = 4.dp).size(if (current) 12.dp else 10.dp).clip(CircleShape)
                            .background(if (current) color.copy(alpha = a) else color),
                    )
                    if (i < events.lastIndex) {
                        Box(Modifier.width(2.dp).height(34.dp).background(Color.White.copy(alpha = 0.18f)))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(CalendarRepo.timeRange(e).takeIf { !e.allDay }, e.location.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CalendarNextUp(events: List<CalEvent>, accent: Color, nowMs: Long, useEventColors: Boolean) {
    val e = events.first()
    val color = eventColor(e, accent, useEventColors)
    val current = !e.allDay && e.begin <= nowMs
    GlassCard(color = color.copy(alpha = 0.22f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(if (current) "HAPPENING NOW" else "NEXT UP", style = MaterialTheme.typography.labelMedium, color = color.mix(Color.White, 0.4f))
            Spacer(Modifier.weight(1f))
            Text(CalendarRepo.countdown(e, nowMs), style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(e.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(CalendarRepo.timeRange(e), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
        if (e.location.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Filled.Place, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(4.dp))
                Text(e.location, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (events.size > 1) {
            Text(
                "Then: " + events.drop(1).joinToString(" · ") { "${CalendarRepo.whenLabel(it)} ${it.title}" },
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarChips(events: List<CalEvent>, accent: Color, useEventColors: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        events.forEach { e ->
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = LocalChipAlpha.current)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(eventColor(e, accent, useEventColors)))
                Spacer(Modifier.width(8.dp))
                Text(CalendarRepo.whenLabel(e), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Text(e.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Sample data for previews

fun sampleMedia() = MediaUi(
    title = "Midnight City", artist = "M83", appLabel = "Spotify", art = null,
    playing = true, positionMs = 96_000, durationMs = 243_000, speed = 1f, measuredAt = 0,
)

fun sampleEvents(nowMs: Long): List<CalEvent> {
    val m = 60_000L
    val base = nowMs - nowMs % (5 * m)
    return listOf(
        CalEvent("Design review", base + 40 * m, base + 85 * m, false, 0xFF7EB6FF.toInt(), "Room 4B"),
        CalEvent("Lunch with Sam", base + 3 * 60 * m, base + 4 * 60 * m, false, 0xFFFFB86B.toInt(), "Tacos Uno"),
        CalEvent("Gym", base + 6 * 60 * m, base + 7 * 60 * m, false, 0xFF7EE0A1.toInt()),
        CalEvent("Call Mom", base + 8 * 60 * m, base + 8 * 60 * m + 20 * m, false, 0xFFC9A0FF.toInt()),
        CalEvent("Read", base + 9 * 60 * m, base + 10 * 60 * m, false, 0xFFFF8A80.toInt()),
    )
}
