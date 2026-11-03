package dev.pridgey.brig.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.data.ClockLayer
import dev.pridgey.brig.data.CustomClock
import dev.pridgey.brig.data.LayerColor
import dev.pridgey.brig.data.LayerKind
import dev.pridgey.brig.data.LayerShape
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.mix
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Formats [pattern] with java.time, showing the pattern itself if it's invalid. Quote literal text with '…'. */
fun formatLayer(pattern: String, now: LocalDateTime): String =
    runCatching { now.format(DateTimeFormatter.ofPattern(pattern)) }.getOrDefault(pattern)

fun ClockLayer.resolveColor(spec: ClockSpec): Color = when (color) {
    LayerColor.WHITE -> spec.color
    LayerColor.ACCENT -> spec.accent
    LayerColor.ACCENT_SOFT -> spec.accent.mix(Color.White, 0.5f)
    LayerColor.CUSTOM -> Color(customColor)
}.copy(alpha = alpha.coerceIn(0f, 1f))

/** A clock built from user-defined text and shape layers, drawn bottom to top. */
@Composable
fun CustomClockView(clock: CustomClock, now: LocalDateTime, spec: ClockSpec, modifier: Modifier = Modifier) {
    val s = spec.scale
    val spinning = clock.layers.any { it.spin }
    val spin = if (spinning) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "deg").value
    } else 0f
    BoxWithConstraints(modifier.fillMaxWidth().height((clock.height * s).dp)) {
        val w = maxWidth
        val h = maxHeight
        clock.layers.forEach { layer ->
            val color = layer.resolveColor(spec)
            val pos = Modifier.offset(w * layer.x, h * layer.y)
            val rot = layer.rotation + if (layer.spin) spin else 0f
            when (layer.kind) {
                LayerKind.TEXT -> RollingText(
                    formatLayer(layer.pattern, now),
                    TextStyle(
                        fontFamily = layer.font.family(),
                        fontSize = (layer.size * s).sp,
                        lineHeight = (layer.size * s * 1.0f).sp,
                        fontWeight = layer.weight.weight(),
                        color = color,
                        fontFeatureSettings = "tnum",
                    ),
                    pos.graphicsLayer { rotationZ = rot },
                )
                LayerKind.SHAPE -> {
                    val size = (layer.size * s).dp
                    val m = pos.graphicsLayer { rotationZ = rot }
                    when (layer.shape) {
                        LayerShape.RING -> Canvas(m.size(size)) {
                            val stroke = this.size.minDimension * 0.08f
                            drawCircle(color, radius = this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                        }
                        LayerShape.PILL -> Box(m.size(size, size * 0.42f).clip(RoundedCornerShape(50)).background(color))
                        else -> Box(m.size(size).clip(PolygonShape(layer.shape.polygon())).background(color))
                    }
                }
            }
        }
    }
}

fun LayerShape.polygon() = when (this) {
    LayerShape.CIRCLE, LayerShape.RING, LayerShape.PILL -> BrigShapes.circle
    LayerShape.COOKIE4 -> BrigShapes.cookie4
    LayerShape.COOKIE6 -> BrigShapes.cookie6
    LayerShape.COOKIE9 -> BrigShapes.cookie9
    LayerShape.COOKIE12 -> BrigShapes.cookie12
    LayerShape.SQUARE -> BrigShapes.square
}
