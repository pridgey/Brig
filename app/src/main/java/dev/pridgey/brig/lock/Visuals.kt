package dev.pridgey.brig.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.pridgey.brig.data.Appearance
import dev.pridgey.brig.data.BackgroundType
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.util.BrigFonts
import dev.pridgey.brig.util.WordClock
import dev.pridgey.brig.util.shiftHue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- Live state

/** Current time, ticking on the second or the minute. */
@Composable
fun rememberNow(seconds: Boolean): State<LocalDateTime> = produceState(LocalDateTime.now(), seconds) {
    while (true) {
        value = LocalDateTime.now()
        val now = System.currentTimeMillis()
        delay(if (seconds) 1000 - now % 1000 else 60_000 - now % 60_000)
    }
}

data class BatteryState(val level: Int, val charging: Boolean)

private fun readBattery(i: Intent?): BatteryState {
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return BatteryState(
        level = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else 0,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

@Composable
fun rememberBattery(): State<BatteryState> {
    val ctx = LocalContext.current
    val state = remember {
        mutableStateOf(readBattery(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                state.value = readBattery(i)
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return state
}

// ---------------------------------------------------------------- Backgrounds

@Composable
fun LockBackground(appearance: Appearance, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (appearance.background) {
            BackgroundType.AURORA -> Aurora(accent)
            BackgroundType.TIME_OF_DAY -> SkyGradient()
            BackgroundType.PARTICLES -> Starfield(accent)
            BackgroundType.SOLID -> Box(Modifier.fillMaxSize().background(Color(appearance.solidColor)))
            BackgroundType.IMAGE -> PhotoBackground(appearance.imagePath, appearance.blur)
        }
    }
}

@Composable
private fun Aurora(accent: Color) {
    val t = rememberInfiniteTransition(label = "aurora")
    val p by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(28_000, easing = LinearEasing)), label = "phase",
    )
    val c2 = remember(accent) { accent.shiftHue(48f) }
    val c3 = remember(accent) { accent.shiftHue(-56f) }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF06070C), accent.copy(alpha = 0.18f).compositeOver(Color(0xFF06070C)))))
        fun blob(color: Color, cx: Float, cy: Float, r: Float, a: Float) {
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy),
            )
        }
        blob(accent, w * (0.25f + 0.22f * sin(p)), h * (0.22f + 0.08f * cos(2 * p)), w * 0.95f, 0.55f)
        blob(c2, w * (0.8f + 0.15f * cos(p)), h * (0.5f + 0.14f * sin(p)), w * 0.85f, 0.45f)
        blob(c3, w * (0.4f + 0.3f * sin(p + 2f)), h * (0.88f + 0.06f * cos(3 * p)), w * 0.9f, 0.5f)
    }
}

private data class Sky(val hour: Float, val top: Long, val bottom: Long)

private val skyStops = listOf(
    Sky(0f, 0xFF0B1026, 0xFF000000), Sky(5f, 0xFF1E2A5A, 0xFF3A1C4A),
    Sky(7f, 0xFFFF9E7A, 0xFF5B4B8A), Sky(10f, 0xFF4FA3E0, 0xFF1C4E80),
    Sky(16f, 0xFF3D7CC9, 0xFF16365E), Sky(19f, 0xFFFF7B54, 0xFF4A2A6A),
    Sky(21f, 0xFF1A1F4A, 0xFF07070F), Sky(24f, 0xFF0B1026, 0xFF000000),
)

@Composable
private fun SkyGradient() {
    val now by rememberNow(false)
    val hour = now.hour + now.minute / 60f
    val i = skyStops.indexOfLast { it.hour <= hour }.coerceIn(0, skyStops.size - 2)
    val a = skyStops[i]
    val b = skyStops[i + 1]
    val f = ((hour - a.hour) / (b.hour - a.hour)).coerceIn(0f, 1f)
    val top = lerp(Color(a.top), Color(b.top), f)
    val bottom = lerp(Color(a.bottom), Color(b.bottom), f)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
}

private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

@Composable
private fun Starfield(accent: Color) {
    val stars = remember {
        val rnd = Random(7)
        List(170) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 2.2f, 0.003f + rnd.nextFloat() * 0.018f, rnd.nextFloat() * 6.28f) }
    }
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    Canvas(Modifier.fillMaxSize()) {
        val base = Color(0xFF04050B)
        drawRect(Brush.verticalGradient(listOf(base, accent.copy(alpha = 0.28f).compositeOver(base))))
        stars.forEach { s ->
            val y = ((s.y - time * s.speed) % 1f + 1f) % 1f
            val alpha = 0.3f + 0.7f * ((sin(time * 1.6f + s.phase) + 1f) / 2f)
            val tint = if (s.r > 2.3f) accent.copy(alpha = alpha) else Color.White.copy(alpha = alpha)
            drawCircle(tint, radius = s.r.dp.toPx() * 0.6f, center = Offset(s.x * size.width, y * size.height))
        }
    }
}

@Composable
private fun PhotoBackground(path: String?, blur: Float) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 1440) } }
    }
    bmp?.let {
        Image(
            it, null,
            Modifier.fillMaxSize().then(if (blur > 0f) Modifier.blur((blur * 30).dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun decodeSampled(path: String, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim * 2) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------- Clocks

/** Each character rolls in like an odometer when it changes. */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, ch ->
            key(index) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)) { it } + fadeIn()) togetherWith
                            (slideOutVertically(spring(stiffness = Spring.StiffnessLow)) { -it } + fadeOut()) using
                            SizeTransform(clip = false)
                    },
                    label = "digit",
                ) { c -> Text(c.toString(), style = style) }
            }
        }
    }
}

@Composable
fun Clock(
    style: ClockStyle,
    now: LocalDateTime,
    use24h: Boolean,
    showSeconds: Boolean,
    scale: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val tnum = "tnum"
    when (style) {
        ClockStyle.DIGITAL -> Row(modifier, verticalAlignment = Alignment.Bottom) {
            val big = TextStyle(fontFamily = BrigFonts.clock, fontSize = (92 * scale).sp, fontWeight = FontWeight.Light, color = color, letterSpacing = (-3).sp, fontFeatureSettings = tnum)
            RollingText(now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm")), big)
            Column(Modifier.padding(start = 8.dp, bottom = (18 * scale).dp)) {
                if (showSeconds) RollingText(now.format(DateTimeFormatter.ofPattern("ss")), TextStyle(fontSize = (22 * scale).sp, color = accent, fontWeight = FontWeight.Medium, fontFeatureSettings = tnum))
                if (!use24h) Text(now.format(DateTimeFormatter.ofPattern("a")), fontSize = (18 * scale).sp, color = color.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
            }
        }
        ClockStyle.STACKED -> Column(modifier) {
            val size = (132 * scale).sp
            val s = TextStyle(fontFamily = BrigFonts.clock, fontSize = size, lineHeight = size * 0.86f, fontWeight = FontWeight.Bold, letterSpacing = (-4).sp, fontFeatureSettings = tnum)
            RollingText(now.format(DateTimeFormatter.ofPattern(if (use24h) "HH" else "hh")), s.copy(color = color))
            RollingText(now.format(DateTimeFormatter.ofPattern("mm")), s.copy(color = accent))
            if (showSeconds) RollingText(now.format(DateTimeFormatter.ofPattern("ss")), TextStyle(fontSize = (28 * scale).sp, color = color.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontFeatureSettings = tnum))
        }
        ClockStyle.ANALOG -> AnalogClock(now, showSeconds, accent, color, modifier.size((250 * scale).dp))
        ClockStyle.WORDS -> Text(
            WordClock.phrase(now.hour, now.minute),
            modifier,
            style = TextStyle(fontFamily = BrigFonts.display, fontSize = (46 * scale).sp, lineHeight = (52 * scale).sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = (-1).sp),
        )
        ClockStyle.MINIMAL -> RollingText(
            now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm")),
            TextStyle(fontFamily = BrigFonts.clock, fontSize = (52 * scale).sp, fontWeight = FontWeight.Normal, color = color, fontFeatureSettings = tnum),
            modifier,
        )
    }
}

@Composable
private fun AnalogClock(now: LocalDateTime, showSeconds: Boolean, accent: Color, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        fun polar(len: Float, deg: Float): Offset {
            val a = Math.toRadians(deg.toDouble())
            return Offset((len * sin(a)).toFloat(), (-len * cos(a)).toFloat())
        }
        drawCircle(color.copy(alpha = 0.07f), r)
        for (i in 0 until 60) {
            val major = i % 5 == 0
            drawLine(
                color.copy(alpha = if (major) 0.9f else 0.25f),
                c + polar(if (major) r * 0.8f else r * 0.88f, i * 6f), c + polar(r * 0.94f, i * 6f),
                strokeWidth = if (major) 3.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        val sec = now.second
        val min = now.minute + sec / 60f
        val hr = (now.hour % 12) + min / 60f
        drawLine(color, c, c + polar(r * 0.5f, hr * 30f), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent, c, c + polar(r * 0.76f, min * 6f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        if (showSeconds) drawLine(Color(0xFFFF6B6B), c, c + polar(r * 0.86f, sec * 6f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(accent, 7.dp.toPx())
    }
}
