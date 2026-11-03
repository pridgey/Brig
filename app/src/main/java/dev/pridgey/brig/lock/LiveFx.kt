package dev.pridgey.brig.lock

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.pridgey.brig.data.WeatherConfig
import dev.pridgey.brig.data.WeatherNow
import dev.pridgey.brig.data.WeatherRepo
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- Tilt parallax

/** Phone tilt relative to how it was held when the lock screen appeared, roughly -1..1 on each axis. */
@Composable
fun rememberTilt(enabled: Boolean): State<Offset> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        if (!enabled) {
            state.value = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var base: FloatArray? = null
        val rot = FloatArray(9)
        val angles = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (pitch, roll) = if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    SensorManager.getOrientation(rot, angles)
                    angles[1] to angles[2]
                } else {
                    // Accelerometer fallback: gravity direction, in m/s² scaled to radians-ish.
                    (e.values[1] / 9.81f) to (e.values[0] / -9.81f)
                }
                val b = base ?: floatArrayOf(pitch, roll).also { base = it }
                val target = Offset(((roll - b[1]) / 0.5f).coerceIn(-1f, 1f), ((pitch - b[0]) / 0.5f).coerceIn(-1f, 1f))
                // Smooth out hand jitter.
                state.value = state.value + (target - state.value) * 0.15f
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

// ---------------------------------------------------------------- Ambient light

/** Light level in lux, or null without a sensor (or when off). */
@Composable
fun rememberLux(enabled: Boolean): State<Float?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) {
            state.value = null
            return@DisposableEffect onDispose { }
        }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val prev = state.value
                state.value = if (prev == null) e.values[0] else prev + (e.values[0] - prev) * 0.3f
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

object Ambient {
    data class Tuning(val dim: Float, val warmth: Float, val chipBoost: Float)

    /** Dark rooms get dimmer and warmer; bright sunlight gets less dim and more solid chips. Pure for tests. */
    fun tune(baseDim: Float, lux: Float?): Tuning {
        if (lux == null) return Tuning(baseDim, 0f, 0f)
        return when {
            lux < 3f -> Tuning((baseDim + 0.35f).coerceAtMost(0.85f), 0.22f, 0f)
            lux < 20f -> Tuning((baseDim + 0.15f).coerceAtMost(0.8f), 0.1f, 0f)
            lux > 5000f -> Tuning((baseDim - 0.2f).coerceAtLeast(0f), 0f, 0.12f)
            lux > 1000f -> Tuning((baseDim - 0.1f).coerceAtLeast(0f), 0f, 0.06f)
            else -> Tuning(baseDim, 0f, 0f)
        }
    }
}

// ---------------------------------------------------------------- Weather particles

enum class WeatherFx { NONE, RAIN, STORM, SNOW, FOG }

fun weatherFxFor(code: Int): WeatherFx = when (code) {
    45, 48 -> WeatherFx.FOG
    in 51..67, in 80..82 -> WeatherFx.RAIN
    in 71..77, 85, 86 -> WeatherFx.SNOW
    in 95..99 -> WeatherFx.STORM
    else -> WeatherFx.NONE
}

private class Drop(val x: Float, val y: Float, val speed: Float, val len: Float, val size: Float, val phase: Float)

@Composable
fun WeatherParticles(fx: WeatherFx, modifier: Modifier = Modifier.fillMaxSize()) {
    if (fx == WeatherFx.NONE) return
    val drops = remember(fx) {
        val rnd = Random(11)
        val n = when (fx) { WeatherFx.SNOW -> 110; WeatherFx.FOG -> 6; else -> 140 }
        List(n) { Drop(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat(), 0.6f + rnd.nextFloat(), 0.5f + rnd.nextFloat(), rnd.nextFloat() * 6.28f) }
    }
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (fx) {
            WeatherFx.RAIN, WeatherFx.STORM -> {
                drops.forEach { d ->
                    val y = ((d.y + time * 0.9f * d.speed) % 1f) * (h + 80f) - 40f
                    val x = (d.x * w + y * 0.12f) % w
                    val l = 26f * d.len
                    drawLine(Color.White.copy(alpha = 0.18f + 0.15f * d.size), Offset(x, y), Offset(x + l * 0.12f, y + l), strokeWidth = 1.6f * d.size, cap = StrokeCap.Round)
                }
                if (fx == WeatherFx.STORM) {
                    // An occasional lightning flash.
                    val cycle = time % 7f
                    val flash = if (cycle < 0.08f || cycle in 0.16f..0.22f) 0.28f else 0f
                    if (flash > 0f) drawRect(Color.White.copy(alpha = flash))
                }
            }
            WeatherFx.SNOW -> drops.forEach { d ->
                val y = ((d.y + time * 0.05f * d.speed) % 1f) * (h + 20f) - 10f
                val x = d.x * w + sin(time * 0.8f + d.phase) * 18f
                drawCircle(Color.White.copy(alpha = 0.35f + 0.35f * d.size / 1.5f), radius = 2.4f * d.size, center = Offset(x, y))
            }
            WeatherFx.FOG -> drops.forEach { d ->
                val cy = d.y * h
                val cx = ((d.x + time * 0.01f * d.speed) % 1.4f - 0.2f) * w
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), center = Offset(cx, cy), radius = w * 0.6f),
                    radius = w * 0.6f, center = Offset(cx, cy),
                )
            }
            WeatherFx.NONE -> Unit
        }
    }
}

@Composable
fun LiveWeatherParticles(cfg: WeatherConfig) {
    val ctx = LocalContext.current
    if (cfg.lat == null) return
    val w by produceState<WeatherNow?>(null, cfg) { value = WeatherRepo.current(ctx, cfg) }
    WeatherParticles(weatherFxFor(w?.code ?: return))
}

// ---------------------------------------------------------------- Video loop

/** A muted, looping, center-cropped video. */
@Composable
fun VideoBackground(path: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    if (path == null) return
    val holder = remember(path) { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(path) { onDispose { holder[0]?.release(); holder[0] = null } }
    AndroidView(
        modifier = modifier,
        factory = { c: Context ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val mp = MediaPlayer()
                        holder[0]?.release()
                        holder[0] = mp
                        runCatching {
                            mp.setDataSource(path)
                            mp.setSurface(Surface(st))
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.setOnPreparedListener { p ->
                                cropTo(this@apply, p.videoWidth, p.videoHeight)
                                p.start()
                            }
                            mp.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        holder[0]?.let { cropTo(this@apply, it.videoWidth, it.videoHeight) }
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        holder[0]?.release()
                        holder[0] = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

private fun cropTo(view: TextureView, vw: Int, vh: Int) {
    if (vw <= 0 || vh <= 0 || view.width == 0 || view.height == 0) return
    val viewRatio = view.width.toFloat() / view.height
    val videoRatio = vw.toFloat() / vh
    val (sx, sy) = if (videoRatio > viewRatio) (videoRatio / viewRatio) to 1f else 1f to (viewRatio / videoRatio)
    view.setTransform(Matrix().apply { setScale(sx, sy, view.width / 2f, view.height / 2f) })
}
