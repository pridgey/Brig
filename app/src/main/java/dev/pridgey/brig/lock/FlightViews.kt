package dev.pridgey.brig.lock

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.FlightMapStyle
import dev.pridgey.brig.data.FlightMapView
import dev.pridgey.brig.data.FlightMath
import dev.pridgey.brig.data.FlightPhase
import dev.pridgey.brig.data.FlightRepo
import dev.pridgey.brig.data.FlightView
import dev.pridgey.brig.data.FlightsConfig
import dev.pridgey.brig.util.MapShot
import dev.pridgey.brig.util.TileMap
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dim = Color.White.copy(alpha = 0.65f)
private val landedGreen = Color(0xFF7BD88F)

// ---------------------------------------------------------------- Map

/** The coordinates a flight's map should frame, and the zoom range to frame them in. */
internal fun mapFrame(v: FlightView, view: FlightMapView): Triple<List<Pair<Double, Double>>, Int, Int>? {
    val plane = v.pos?.let { it.lat to it.lon }
    v.home?.let { home -> return Triple(listOfNotNull(home, plane), 7, 11) }
    if (view == FlightMapView.CLOSE && plane != null) return Triple(listOf(plane), 7, 7)
    val pts = listOfNotNull(v.route?.origin?.let { it.lat to it.lon }, v.route?.dest?.let { it.lat to it.lon }, plane)
    return when {
        pts.size >= 2 -> Triple(pts, 2, 9)
        pts.size == 1 -> Triple(pts, 7, 7)
        else -> null
    }
}

internal suspend fun flightMap(ctx: Context, v: FlightView, cfg: FlightsConfig): MapShot? {
    val (pts, minZ, maxZ) = mapFrame(v, cfg.mapView) ?: return null
    return TileMap.render(ctx, cfg.mapStyle.path, cfg.mapStyle.background.toInt(), pts, minZ, maxZ)
}

@Composable
private fun FlightMap(v: FlightView, shot: MapShot, style: FlightMapStyle, accent: Color) {
    val dark = style == FlightMapStyle.DARK
    val ink = if (dark) Color.White else Color(0xFF1B1B1F)
    val plane = rememberVectorPainter(Icons.Filled.Flight)
    val measurer = rememberTextMeasurer()
    val pulse = rememberInfiniteTransition(label = "home")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p")
    Box(Modifier.fillMaxWidth().aspectRatio(shot.w.toFloat() / shot.h).clip(RoundedCornerShape(18.dp)).background(Color(style.background))) {
        shot.bitmap?.let { bmp ->
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / shot.w
            fun at(lat: Double, lon: Double) = shot.project(lat, lon).let { Offset(it.first * s, it.second * s) }
            if (shot.bitmap == null) graticule(ink.copy(alpha = 0.07f))
            val o = v.route?.origin
            val d = v.route?.dest
            val pos = v.pos
            fun line(a: Pair<Double, Double>, b: Pair<Double, Double>, color: Color, width: Float, dashed: Boolean) {
                val pts = FlightMath.greatCircle(a.first, a.second, b.first, b.second).map { at(it.first, it.second) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path, color,
                    style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())) else null),
                )
            }
            if (o != null && d != null && v.home == null) {
                if (pos != null && v.phase != FlightPhase.LANDED) {
                    line(o.lat to o.lon, pos.lat to pos.lon, accent, 3.dp.toPx(), false)
                    line(pos.lat to pos.lon, d.lat to d.lon, ink.copy(alpha = 0.55f), 2.dp.toPx(), true)
                } else line(o.lat to o.lon, d.lat to d.lon, if (v.phase == FlightPhase.LANDED) accent else ink.copy(alpha = 0.55f), 2.5.dp.toPx(), v.phase != FlightPhase.LANDED)
            }
            if (o != null && d == null && pos != null && v.home == null) line(o.lat to o.lon, pos.lat to pos.lon, accent, 3.dp.toPx(), false)
            if (v.home == null) {
                listOfNotNull(o, d).forEach { ap ->
                    val c = at(ap.lat, ap.lon)
                    drawCircle(ink, 4.5.dp.toPx(), c)
                    drawCircle(accent, 2.5.dp.toPx(), c)
                    val label = measurer.measure(ap.iata, TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    val tx = (c.x - label.size.width / 2).coerceIn(4f, size.width - label.size.width - 4f)
                    val ty = (c.y + 7.dp.toPx()).let { if (it + label.size.height > size.height - 2f) c.y - 7.dp.toPx() - label.size.height else it }
                    drawText(label, topLeft = Offset(tx, ty))
                }
            }
            v.home?.let { (la, lo) ->
                val c = at(la, lo)
                drawCircle(accent.copy(alpha = 0.4f * (1 - p)), (6 + 18 * p).dp.toPx(), c)
                drawCircle(Color.White, 6.dp.toPx(), c)
                drawCircle(accent, 4.dp.toPx(), c)
                pos?.let { drawLine(ink.copy(alpha = 0.4f), c, at(it.lat, it.lon), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))) }
            }
            if (pos != null) {
                val c = at(pos.lat, pos.lon)
                val sz = 26.dp.toPx()
                drawCircle(accent.copy(alpha = 0.35f), 17.dp.toPx(), c)
                translate(c.x - sz / 2, c.y - sz / 2) {
                    rotate(pos.track?.toFloat() ?: 0f, pivot = Offset(sz / 2, sz / 2)) {
                        with(plane) { draw(Size(sz, sz), colorFilter = ColorFilter.tint(ink)) }
                    }
                }
            }
        }
        if (shot.bitmap != null) {
            Text(
                "© OpenStreetMap © CARTO", fontSize = 8.sp, color = ink.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Faint latitude/longitude-ish lines for a map with no tiles. */
private fun DrawScope.graticule(color: Color) {
    val step = 28.dp.toPx()
    var x = step / 2
    while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = step / 2
    while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

// ---------------------------------------------------------------- Card

private fun timeText(t: Long, h24: Boolean) =
    Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a"))

/** The status line under the map: altitude, speed, time left, or why there's no live data. */
internal fun flightFacts(v: FlightView, imperial: Boolean, h24: Boolean, now: Long = System.currentTimeMillis()): String {
    val p = v.pos
    val parts = mutableListOf<String>()
    when (v.phase) {
        FlightPhase.AIRBORNE, FlightPhase.OVERHEAD -> {
            p?.altFt?.let { alt ->
                val arrow = when {
                    (p.vRate ?: 0) > 400 -> " ↑"
                    (p.vRate ?: 0) < -400 -> " ↓"
                    else -> ""
                }
                parts += FlightMath.altitude(alt, imperial) + arrow
            }
            p?.speedKt?.let { parts += FlightMath.speed(it, imperial) }
            if (v.phase == FlightPhase.OVERHEAD) {
                p?.distNm?.let { nm -> parts += FlightMath.distance(nm, imperial) + (p.dirDeg?.let { " " + FlightMath.compass(it) } ?: "") + " of you" }
            } else FlightMath.minutesLeft(v.route, p)?.let { m ->
                parts += "≈${FlightMath.duration(m)} left · lands ${timeText(now + m * 60_000L, h24)}"
            }
        }
        FlightPhase.SCHEDULED -> parts += v.departs?.let { t ->
            if (t > now) "Departs ${timeText(t, h24)} · ${untilLabel(t, now)}" else "Not in the air yet"
        } ?: "Not in the air right now"
        FlightPhase.TAXIING -> parts += "Taxiing" + (v.route?.origin?.iata?.let { " at $it" } ?: "")
        FlightPhase.OUT_OF_RANGE -> p?.let {
            parts += "No signal · last seen ${((now - it.at) / 60_000).coerceAtLeast(1)} min ago"
        } ?: run { parts += "No signal" }
        FlightPhase.LANDED -> parts += "Landed" + (v.route?.dest?.let { " in ${it.city.ifBlank { it.iata }}" } ?: "")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun PhasePill(phase: FlightPhase, accent: Color) {
    val bg = when (phase) {
        FlightPhase.AIRBORNE, FlightPhase.OVERHEAD -> accent.copy(alpha = 0.35f)
        FlightPhase.LANDED -> landedGreen.copy(alpha = 0.3f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(phase.label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun RouteProgress(v: FlightView, accent: Color) {
    val o = v.route?.origin ?: return
    val d = v.route.dest
    val f = when (v.phase) {
        FlightPhase.LANDED -> 1f
        FlightPhase.SCHEDULED, FlightPhase.TAXIING -> 0f
        else -> FlightMath.progress(v.route, v.pos) ?: if (d == null) 0.5f else 0f
    }
    val plane = rememberVectorPainter(Icons.Filled.Flight)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(o.iata, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(o.city, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp))
        }
        Canvas(Modifier.weight(1f).height(24.dp).padding(horizontal = 8.dp)) {
            val y = size.height / 2
            val x = size.width * f
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
            if (d != null) drawLine(accent, Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            val sz = 18.dp.toPx()
            translate((x - sz / 2).coerceIn(0f, size.width - sz), y - sz / 2) {
                rotate(90f, pivot = Offset(sz / 2, sz / 2)) {
                    with(plane) { draw(Size(sz, sz), colorFilter = ColorFilter.tint(Color.White)) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(d?.iata ?: "?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(d?.city ?: "Destination unknown", style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp), textAlign = TextAlign.End)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FlightsView(
    views: List<FlightView>,
    shot: MapShot?,
    cfg: FlightsConfig,
    accent: Color,
    imperial: Boolean,
    onManage: (() -> Unit)? = null,
    onOpen: (FlightView) -> Unit,
) {
    val v = views.firstOrNull() ?: return
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    GlassCard {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { onOpen(v) }, onLongClick = onManage, onLongClickLabel = "Manage flights"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Flight, null, Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    val sub = listOf(v.label, v.route?.airline.orEmpty()).firstOrNull { it.isNotBlank() }
                        ?: v.route?.let { r -> listOfNotNull(r.origin?.iata, r.dest?.iata).joinToString(" → ") }.orEmpty()
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PhasePill(v.phase, accent)
            }
            if (cfg.showMap && shot != null) FlightMap(v, shot, cfg.mapStyle, accent)
            if (v.home == null) RouteProgress(v, accent)
            else v.route?.let { r ->
                if (r.origin != null && r.dest != null) {
                    Text("${r.origin.city.ifBlank { r.origin.iata }} → ${r.dest.city.ifBlank { r.dest.iata }}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column {
                Text(flightFacts(v, imperial, h24), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                val plane = listOfNotNull(v.aircraft ?: v.pos?.type?.takeIf { it.isNotBlank() }, v.pos?.reg?.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (plane.isNotBlank()) Text(plane, style = MaterialTheme.typography.labelSmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Any other flights get one line each.
            views.drop(1).take(2).forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(o) }, onLongClick = onManage),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Flight, null, Modifier.size(16.dp), tint = dim)
                    Spacer(Modifier.width(8.dp))
                    Text(o.title, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    val route = o.route?.let { r -> listOfNotNull(r.origin?.iata, r.dest?.iata).joinToString("→") }.orEmpty()
                    Text(
                        listOf(route, flightFacts(o, imperial, h24)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveFlights(settings: BrigSettings, accent: Color, now: LocalDateTime, controller: LockController) {
    val ctx = LocalContext.current
    remember { FlightRepo.load(ctx); runCatching { TileMap.prune(ctx) } }
    val tracked by FlightRepo.flights.collectAsState()
    val cfg = settings.flights
    // Every two minutes; the repo also caches positions for 90 seconds.
    val views by produceState(FlightRepo.lastViews.value, tracked, cfg, settings.weather.lat, settings.weather.lon, now.minute / 2) {
        value = runCatching { FlightRepo.snapshot(ctx, settings) }.getOrDefault(value)
    }
    val primary = views.firstOrNull()
    val shot by produceState<MapShot?>(null, primary?.code, primary?.pos?.lat, primary?.pos?.lon, primary?.route?.dest, cfg.mapStyle, cfg.mapView, cfg.showMap) {
        if (primary == null || !cfg.showMap) {
            value = null
            return@produceState
        }
        // Until tiles arrive, frame the flight on a plain background so the plane shows right away.
        if (value == null) value = mapFrame(primary, cfg.mapView)?.let { (pts, minZ, maxZ) -> MapShot.blank(pts, minZ = minZ, maxZ = maxZ) }
        value = runCatching { flightMap(ctx, primary, cfg) }.getOrNull() ?: value
    }
    FlightsView(views, shot, cfg, accent, settings.weather.fahrenheit, onManage = controller::openFlightSettings) { v ->
        controller.openUri(Uri.parse(FlightRepo.trackerUrl(v, cfg.tracker)))
    }
}
