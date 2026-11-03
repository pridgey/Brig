package dev.pridgey.brig.util

import kotlin.math.max
import kotlin.math.min

/** Picks a usable accent color from an image's pixels. Pure Kotlin so it can be unit tested. */
object PhotoColor {
    /** HSV with hue 0..360, saturation and value 0..1. */
    fun hsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Long {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000 or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * The most prominent colorful hue, nudged to read well on a dark lock screen.
     * Null for black-and-white or very muted images.
     */
    fun accent(pixels: IntArray): Long? {
        val buckets = 18
        val weight = FloatArray(buckets)
        val hueSum = FloatArray(buckets)
        val satSum = FloatArray(buckets)
        pixels.forEach { p ->
            val (h, s, v) = hsv(p).let { Triple(it[0], it[1], it[2]) }
            if (s < 0.22f || v < 0.18f) return@forEach
            val w = s * v
            val i = ((h / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[i] += w
            hueSum[i] += h * w
            satSum[i] += s * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        // Too little color overall: let the default accent stand.
        if (weight[best] < pixels.size * 0.02f) return null
        val hue = hueSum[best] / weight[best]
        val sat = (satSum[best] / weight[best]).coerceIn(0.45f, 0.85f)
        return fromHsv(hue, sat, 0.88f)
    }
}
