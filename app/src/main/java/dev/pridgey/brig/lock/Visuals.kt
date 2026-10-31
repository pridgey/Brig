package dev.pridgey.brig.lock

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import dev.pridgey.brig.data.PhotoConfig
import dev.pridgey.brig.util.PhotoFx
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
            BackgroundType.IMAGE -> PhotoBackground(appearance.imagePath, appearance.blur, appearance.photo, accent)
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
private fun PhotoBackground(path: String?, blur: Float, cfg: PhotoConfig, accent: Color) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { loadPhoto(it, 1440) } }
    }
    val matrix = remember(cfg.effect, cfg.brightness, cfg.contrast, cfg.saturation, accent) {
        ColorMatrix(PhotoFx.matrix(cfg, accent))
    }
    // Ken Burns: a very slow zoom and drift.
    val kb = if (cfg.kenBurns) {
        val t = rememberInfiniteTransition(label = "kenburns")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = LinearEasing), RepeatMode.Reverse), label = "kb").value
    } else 0f
    Box(Modifier.fillMaxSize().clipToBounds()) {
        bmp?.let {
            Image(
                it, null,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val sc = cfg.zoom * (1f + 0.14f * kb)
                        scaleX = sc
                        scaleY = sc
                        val maxX = (sc - 1f) * size.width / 2f
                        val maxY = (sc - 1f) * size.height / 2f
                        translationX = (-cfg.panX * maxX + (kb - 0.5f) * 0.08f * size.width).coerceIn(-maxX, maxX)
                        translationY = (-cfg.panY * maxY).coerceIn(-maxY, maxY)
                    }
                    .then(if (blur > 0f) Modifier.blur((blur * 30).dp) else Modifier),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
            )
        }
        if (cfg.vignette > 0f) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = cfg.vignette),
                            center = center, radius = size.maxDimension * 0.72f,
                        ),
                    )
                },
            )
        }
        if (cfg.grain > 0f) Grain(cfg.grain)
        if (cfg.scrim > 0f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f * cfg.scrim),
                        0.35f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f * cfg.scrim),
                    ),
                ),
            )
        }
    }
}

/** Tiled film-grain noise. */
@Composable
private fun Grain(amount: Float) {
    val brush = remember {
        val n = 160
        val rnd = Random(3)
        val pixels = IntArray(n * n) {
            val v = rnd.nextInt(256)
            android.graphics.Color.argb(255, v, v, v)
        }
        val bmp = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(brush, alpha = amount * 0.45f, blendMode = BlendMode.Overlay) })
}

internal fun loadPhoto(path: String, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim * 2) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
