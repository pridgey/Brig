package dev.pridgey.brig.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.data.Appearance
import dev.pridgey.brig.data.ClockAlign
import dev.pridgey.brig.data.ClockFont
import dev.pridgey.brig.data.ClockStyle
import dev.pridgey.brig.data.ClockWeight
import dev.pridgey.brig.util.BrigFonts
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.WordClock
import dev.pridgey.brig.util.shiftHue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** Everything a clock needs to draw itself, resolved from [Appearance]. */
data class ClockSpec(
    val use24h: Boolean,
    val showSeconds: Boolean,
    val leadingZero: Boolean,
    val scale: Float,
    val accent: Color,
    val color: Color,
    val font: FontFamily,
    val weight: FontWeight?,
) {
    val hourPattern get() = when {
        use24h -> "HH"
        leadingZero -> "hh"
        else -> "h"
    }

    fun w(default: FontWeight) = weight ?: default

    companion object {
        fun from(a: Appearance, accent: Color, scale: Float = a.clockScale) = ClockSpec(
            use24h = a.use24h,
            showSeconds = a.showSeconds,
            leadingZero = a.leadingZero,
            scale = scale,
            accent = accent,
            color = a.clockColor?.let { Color(it) } ?: Color.White,
            font = a.clockFont.family(),
            weight = a.clockWeight?.weight(),
        )
    }
}

fun ClockFont.family(): FontFamily = when (this) {
    ClockFont.DEFAULT -> BrigFonts.clock
    ClockFont.CONDENSED -> FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed")))
    ClockFont.SERIF -> FontFamily.Serif
    ClockFont.MONO -> FontFamily.Monospace
    ClockFont.SCRIPT -> FontFamily.Cursive
}

fun ClockWeight.weight(): FontWeight = when (this) {
    ClockWeight.THIN -> FontWeight.Thin
    ClockWeight.LIGHT -> FontWeight.Light
    ClockWeight.REGULAR -> FontWeight.Normal
    ClockWeight.BOLD -> FontWeight.Bold
    ClockWeight.BLACK -> FontWeight.Black
}

fun Appearance.clockAlignment(style: ClockStyle = clockStyle): Alignment.Horizontal = when (clockAlign) {
    ClockAlign.START -> Alignment.Start
    ClockAlign.CENTER -> Alignment.CenterHorizontally
    ClockAlign.END -> Alignment.End
    ClockAlign.AUTO -> if (style.centered) Alignment.CenterHorizontally else Alignment.Start
}

private fun LocalDateTime.f(pattern: String): String = format(DateTimeFormatter.ofPattern(pattern))

private const val TNUM = "tnum"

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
fun Clock(style: ClockStyle, now: LocalDateTime, spec: ClockSpec, modifier: Modifier = Modifier) {
    val c = spec.color
    val s = spec.scale
    when (style) {
        ClockStyle.STACKED -> Column(modifier) {
            val size = (132 * s).sp
            val t = TextStyle(fontFamily = spec.font, fontSize = size, lineHeight = size * 0.86f, fontWeight = spec.w(FontWeight.Bold), letterSpacing = (-4).sp, fontFeatureSettings = TNUM)
            RollingText(now.f(spec.hourPattern), t.copy(color = c))
            RollingText(now.f("mm"), t.copy(color = spec.accent))
            if (spec.showSeconds) RollingText(now.f("ss"), TextStyle(fontFamily = spec.font, fontSize = (28 * s).sp, color = c.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM))
        }
        ClockStyle.DIGITAL -> Row(modifier, verticalAlignment = Alignment.Bottom) {
            RollingText(
                now.f("${spec.hourPattern}:mm"),
                TextStyle(fontFamily = spec.font, fontSize = (92 * s).sp, fontWeight = spec.w(FontWeight.Light), color = c, letterSpacing = (-3).sp, fontFeatureSettings = TNUM),
            )
            SideLabels(now, spec, Modifier.padding(start = 8.dp, bottom = (18 * s).dp))
        }
        ClockStyle.FLIP -> FlipClock(now, spec, modifier)
        ClockStyle.BUBBLE -> BubbleClock(now, spec, modifier)
        ClockStyle.ORBIT -> OrbitClock(now, spec, modifier)
        ClockStyle.GRADIENT -> GradientClock(now, spec, modifier)
        ClockStyle.OUTLINE -> OutlineClock(now, spec, modifier)
        ClockStyle.DOT_MATRIX -> DotMatrixClock(now, spec, modifier)
        ClockStyle.SEGMENT -> SegmentClock(now, spec, modifier)
        ClockStyle.BINARY -> BinaryClock(now, spec, modifier)
        ClockStyle.ANALOG -> AnalogClock(now, spec, modifier.size((250 * s).dp))
        ClockStyle.WORDS -> Text(
            WordClock.phrase(now.hour, now.minute),
            modifier,
            style = TextStyle(fontFamily = spec.font, fontSize = (46 * s).sp, lineHeight = (52 * s).sp, fontWeight = spec.w(FontWeight.Bold), color = c, letterSpacing = (-1).sp),
        )
        ClockStyle.MINIMAL -> RollingText(
            now.f("${spec.hourPattern}:mm"),
            TextStyle(fontFamily = spec.font, fontSize = (52 * s).sp, fontWeight = spec.w(FontWeight.Normal), color = c, fontFeatureSettings = TNUM),
            modifier,
        )
    }
}

/** Seconds and AM/PM, stacked small beside a clock. */
@Composable
private fun SideLabels(now: LocalDateTime, spec: ClockSpec, modifier: Modifier = Modifier) {
    if (!spec.showSeconds && spec.use24h) return
    Column(modifier) {
        if (spec.showSeconds) RollingText(now.f("ss"), TextStyle(fontSize = (22 * spec.scale).sp, color = spec.accent, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM))
        if (!spec.use24h) Text(now.f("a"), fontSize = (16 * spec.scale).sp, color = spec.color.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- Flip

@Composable
private fun FlipClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val h = (110 * spec.scale).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FlipGroup(now.f(spec.hourPattern), h, spec, spec.color)
        Spacer(Modifier.width(h * 0.14f))
        FlipGroup(now.f("mm"), h, spec, spec.accent)
        if (spec.showSeconds) {
            Spacer(Modifier.width(h * 0.1f))
            FlipGroup(now.f("ss"), h * 0.5f, spec, spec.color.copy(alpha = 0.8f))
        }
        if (!spec.use24h) {
            Text(now.f("a"), fontSize = (16 * spec.scale).sp, color = spec.color.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun FlipGroup(text: String, h: Dp, spec: ClockSpec, digitColor: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(h * 0.05f)) {
        text.forEachIndexed { i, ch -> key(i) { FlipCard(ch, h, spec, digitColor) } }
    }
}

/** A card that flips over on its hinge when the digit changes. */
@Composable
private fun FlipCard(ch: Char, h: Dp, spec: ClockSpec, digitColor: Color) {
    var current by remember { mutableStateOf(ch) }
    var previous by remember { mutableStateOf(ch) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(ch) {
        if (ch != current) {
            previous = current
            current = ch
            progress.snapTo(0f)
            progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    val fontSize = with(LocalDensity.current) { (h * 0.66f).toSp() }
    val p = progress.value
    val showNew = p >= 0.5f
    Box(
        Modifier
            .size(h * 0.72f, h)
            .graphicsLayer {
                rotationX = if (showNew) 180f * (1f - p) else -180f * p
                cameraDistance = 16f * density
            }
            .clip(RoundedCornerShape(h * 0.14f))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.09f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (if (showNew) current else previous).toString(),
            style = TextStyle(fontFamily = spec.font, fontSize = fontSize, fontWeight = spec.w(FontWeight.Bold), color = digitColor, fontFeatureSettings = TNUM),
        )
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Black.copy(alpha = 0.5f)))
    }
}

// ---------------------------------------------------------------- Bubbles

@Composable
private fun BubbleClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val b = (92 * spec.scale).dp
    val spin = rememberInfiniteTransition(label = "bubbles")
    val r by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "r")
    val fontSize = with(LocalDensity.current) { (b * 0.52f).toSp() }
    @Composable
    fun Bubble(ch: Char, index: Int, bg: Color, fg: Color) {
        val poly = BrigShapes.badges[index % BrigShapes.badges.size]
        Box(Modifier.size(b), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(PolygonShape(poly, if (index % 2 == 0) r else -r)).background(bg))
            RollingText(ch.toString(), TextStyle(fontFamily = spec.font, fontSize = fontSize, fontWeight = spec.w(FontWeight.Bold), color = fg))
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(b * 0.08f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(b * 0.08f), verticalAlignment = Alignment.CenterVertically) {
            now.f(spec.hourPattern).forEachIndexed { i, ch -> key("h$i") { Bubble(ch, i, Color.White.copy(alpha = 0.16f), spec.color) } }
            if (!spec.use24h) Text(now.f("a"), fontSize = (18 * spec.scale).sp, color = spec.color.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(b * 0.08f), verticalAlignment = Alignment.CenterVertically) {
            now.f("mm").forEachIndexed { i, ch -> key("m$i") { Bubble(ch, i + 2, spec.accent, Color.Black) } }
            if (spec.showSeconds) RollingText(now.f("ss"), TextStyle(fontSize = (22 * spec.scale).sp, color = spec.accent, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM))
        }
    }
}

// ---------------------------------------------------------------- Orbit

@Composable
private fun OrbitClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val target = (now.minute + now.second / 60f) / 60f * 360f
    val sweep = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (target < sweep.value) sweep.snapTo(0f) // new hour: restart the ring
        sweep.animateTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow))
    }
    val c = spec.color
    Box(modifier.size((250 * spec.scale).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = (14 * spec.scale).dp.toPx()
            val arcSize = Size(size.width - st, size.height - st)
            val topLeft = Offset(st / 2, st / 2)
            drawArc(c.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arcSize, style = Stroke(st))
            drawArc(spec.accent, -90f, sweep.value, false, topLeft, arcSize, style = Stroke(st, cap = StrokeCap.Round))
            val r = (size.minDimension - st) / 2
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0)
                drawCircle(c.copy(alpha = 0.35f), st * 0.12f, center + Offset((r * 0.8f * sin(a)).toFloat(), (-r * 0.8f * cos(a)).toFloat()))
            }
            if (spec.showSeconds) {
                val a = Math.toRadians(now.second * 6.0)
                drawCircle(c, st * 0.3f, center + Offset((r * sin(a)).toFloat(), (-r * cos(a)).toFloat()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingText(now.f(spec.hourPattern), TextStyle(fontFamily = spec.font, fontSize = (88 * spec.scale).sp, lineHeight = (88 * spec.scale).sp, fontWeight = spec.w(FontWeight.Bold), color = c, fontFeatureSettings = TNUM))
            Text(
                now.f("mm") + if (!spec.use24h) " " + now.f("a") else "",
                fontFamily = spec.font, fontSize = (24 * spec.scale).sp, fontWeight = FontWeight.Bold, color = spec.accent,
            )
        }
    }
}

// ---------------------------------------------------------------- Gradient & outline

@Composable
private fun GradientClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "gradient")
    val shift by t.animateFloat(0f, 900f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val colors = remember(spec.accent, spec.color) {
        listOf(spec.accent, spec.accent.shiftHue(70f), spec.color, spec.accent.shiftHue(-70f))
    }
    val brush = Brush.linearGradient(colors, start = Offset(shift, 0f), end = Offset(shift + 520f, 420f), tileMode = TileMode.Mirror)
    Column(modifier) {
        Text(
            now.f("${spec.hourPattern}:mm"),
            style = TextStyle(brush = brush, fontFamily = spec.font, fontSize = (104 * spec.scale).sp, fontWeight = spec.w(FontWeight.Black), letterSpacing = (-4).sp, fontFeatureSettings = TNUM),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!spec.use24h) Text(now.f("a"), fontSize = (18 * spec.scale).sp, color = spec.color.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            if (spec.showSeconds) RollingText(now.f("ss"), TextStyle(fontSize = (18 * spec.scale).sp, color = spec.accent, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM))
        }
    }
}

@Composable
private fun OutlineClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val size = (136 * spec.scale).sp
    val stroke = with(LocalDensity.current) { (3 * spec.scale).dp.toPx() }
    val base = TextStyle(fontFamily = spec.font, fontSize = size, lineHeight = size * 0.86f, fontWeight = spec.w(FontWeight.Black), letterSpacing = (-3).sp, fontFeatureSettings = TNUM)
    Column(modifier) {
        RollingText(now.f(spec.hourPattern), base.copy(color = spec.color, drawStyle = Stroke(width = stroke)))
        RollingText(now.f("mm"), base.copy(color = spec.accent, drawStyle = Fill))
        SideLabels(now, spec)
    }
}

// ---------------------------------------------------------------- Dot matrix

private val glyphs: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    ':' to listOf("0", "0", "1", "0", "1", "0", "0"),
)

@Composable
private fun DotMatrixClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val text = now.f("${spec.hourPattern}:mm")
    val colonOn = !spec.showSeconds || now.second % 2 == 0
    val pitch = (12 * spec.scale).dp
    val cols = text.sumOf { glyphs[it]?.first()?.length ?: 0 } + text.length - 1
    Column(modifier, horizontalAlignment = Alignment.End) {
        Canvas(Modifier.size(pitch * cols, pitch * 7)) {
            val p = pitch.toPx()
            val r = p * 0.36f
            var x0 = 0
            var afterColon = false
            text.forEach { ch ->
                val g = glyphs[ch] ?: return@forEach
                val lit = when {
                    ch == ':' -> spec.accent
                    afterColon -> spec.accent
                    else -> spec.color
                }
                for (row in 0 until 7) for (col in g[row].indices) {
                    val on = g[row][col] == '1' && (ch != ':' || colonOn)
                    drawCircle(if (on) lit else spec.color.copy(alpha = 0.07f), r, Offset((x0 + col) * p + p / 2, row * p + p / 2))
                }
                if (ch == ':') afterColon = true
                x0 += g[0].length + 1
            }
        }
        if (!spec.use24h) Text(now.f("a"), fontSize = (14 * spec.scale).sp, color = spec.color.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- Retro LED (7-segment)

// Bits a..g, a is the highest bit.
private val segments = intArrayOf(0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011, 0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011)

@Composable
private fun SegmentClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val text = now.f("${spec.hourPattern}:mm")
    val dh = (104 * spec.scale).dp
    val dw = dh * 0.55f
    val colonW = dw * 0.4f
    val gap = dh * 0.1f
    val digits = text.count { it.isDigit() }
    val totalW = dw * digits + colonW * (text.length - digits) + gap * (text.length - 1) + dh * 0.12f
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Canvas(Modifier.size(totalW, dh)) {
            val canvas = drawContext.canvas
            canvas.save()
            canvas.translate(dh.toPx() * 0.12f, 0f)
            canvas.skew(-0.12f, 0f) // classic italic LED slant
            var x = 0f
            var afterColon = false
            text.forEach { ch ->
                if (ch == ':') {
                    val cx = x + colonW.toPx() / 2
                    val r = dw.toPx() * 0.09f
                    drawCircle(spec.accent, r, Offset(cx, dh.toPx() * 0.32f))
                    drawCircle(spec.accent, r, Offset(cx, dh.toPx() * 0.68f))
                    x += colonW.toPx() + gap.toPx()
                    afterColon = true
                } else {
                    drawSegmentDigit(ch - '0', Offset(x, 0f), dw.toPx(), dh.toPx(), if (afterColon) spec.accent else spec.color)
                    x += dw.toPx() + gap.toPx()
                }
            }
            canvas.restore()
        }
        SideLabels(now, spec, Modifier.padding(start = 8.dp))
    }
}

private fun DrawScope.drawSegmentDigit(d: Int, origin: Offset, w: Float, h: Float, color: Color) {
    val t = w * 0.17f
    val ht = t / 2
    val g = t * 0.75f
    val lines = listOf(
        Offset(ht + g, ht) to Offset(w - ht - g, ht), // a
        Offset(w - ht, ht + g) to Offset(w - ht, h / 2 - g), // b
        Offset(w - ht, h / 2 + g) to Offset(w - ht, h - ht - g), // c
        Offset(ht + g, h - ht) to Offset(w - ht - g, h - ht), // d
        Offset(ht, h / 2 + g) to Offset(ht, h - ht - g), // e
        Offset(ht, ht + g) to Offset(ht, h / 2 - g), // f
        Offset(ht + g, h / 2) to Offset(w - ht - g, h / 2), // g
    )
    val bits = segments.getOrElse(d) { 0 }
    lines.forEachIndexed { i, (a, b) ->
        val on = (bits shr (6 - i)) and 1 == 1
        if (on) {
            drawLine(color.copy(alpha = 0.22f), origin + a, origin + b, strokeWidth = t * 2.2f, cap = StrokeCap.Round) // glow
            drawLine(color, origin + a, origin + b, strokeWidth = t, cap = StrokeCap.Round)
        } else {
            drawLine(color.copy(alpha = 0.06f), origin + a, origin + b, strokeWidth = t, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Binary

@Composable
private fun BinaryClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val hour = if (spec.use24h) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    val digits = buildList {
        add(hour / 10); add(hour % 10); add(now.minute / 10); add(now.minute % 10)
        if (spec.showSeconds) { add(now.second / 10); add(now.second % 10) }
    }
    val maxBits = listOf(2, 4, 3, 4, 3, 4)
    val dot = (22 * spec.scale).dp
    val gap = (9 * spec.scale).dp
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
        digits.forEachIndexed { i, d ->
            if (i == 2 || i == 4) Spacer(Modifier.width(gap))
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                for (bit in 3 downTo 0) {
                    val exists = bit < maxBits[i]
                    val on = exists && (d shr bit) and 1 == 1
                    val lit = if (i < 2) spec.color else spec.accent
                    val color by animateColorAsState(
                        when {
                            !exists -> Color.Transparent
                            on -> lit
                            else -> spec.color.copy(alpha = 0.1f)
                        },
                        spring(stiffness = Spring.StiffnessLow), label = "bit",
                    )
                    Box(Modifier.size(dot).clip(if (on) PolygonShape(BrigShapes.cookie4) else CircleShape).background(color))
                }
                Text(d.toString(), fontSize = (12 * spec.scale).sp, color = spec.color.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------- Analog

@Composable
private fun AnalogClock(now: LocalDateTime, spec: ClockSpec, modifier: Modifier) {
    val color = spec.color
    val accent = spec.accent
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
        if (spec.showSeconds) drawLine(Color(0xFFFF6B6B), c, c + polar(r * 0.86f, sec * 6f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(accent, 7.dp.toPx())
    }
}
