package dev.pridgey.brig.util

import androidx.compose.ui.graphics.Color
import dev.pridgey.brig.data.PhotoConfig
import dev.pridgey.brig.data.PhotoEffect

/**
 * Photo looks as 4x5 color matrices (Android's ColorMatrix layout: offsets in 0..255).
 * Pure Kotlin so it can be unit tested.
 */
object PhotoFx {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** The matrix that applies [inner] first, then [outer]. */
    fun concat(outer: FloatArray, inner: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0..3) {
            for (c in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += outer[r * 5 + k] * inner[k * 5 + c]
                out[r * 5 + c] = sum
            }
            var t = outer[r * 5 + 4]
            for (k in 0..3) t += outer[r * 5 + k] * inner[k * 5 + 4]
            out[r * 5 + 4] = t
        }
        return out
    }

    fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(20) { a[it] + (b[it] - a[it]) * t }

    fun saturation(s: Float): FloatArray {
        val i = 1f - s
        return floatArrayOf(
            LR * i + s, LG * i, LB * i, 0f, 0f,
            LR * i, LG * i + s, LB * i, 0f, 0f,
            LR * i, LG * i, LB * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun contrast(c: Float): FloatArray {
        val t = 128f * (1f - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun brightness(b: Float): FloatArray {
        val t = b * 255f
        return floatArrayOf(
            1f, 0f, 0f, 0f, t,
            0f, 1f, 0f, 0f, t,
            0f, 0f, 1f, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun scale(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private val sepia = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private val invert = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Maps brightness onto a gradient from [lo] (shadows) to [hi] (highlights). */
    fun duotone(lo: Color, hi: Color): FloatArray {
        fun row(l: Float, h: Float) = floatArrayOf((h - l) * LR, (h - l) * LG, (h - l) * LB, 0f, l * 255f)
        return row(lo.red, hi.red) + row(lo.green, hi.green) + row(lo.blue, hi.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    }

    fun effect(e: PhotoEffect, accent: Color): FloatArray = when (e) {
        PhotoEffect.NONE -> identity()
        PhotoEffect.MONO -> saturation(0f)
        PhotoEffect.NOIR -> concat(contrast(1.6f), saturation(0f))
        PhotoEffect.SEPIA -> sepia
        PhotoEffect.VINTAGE -> concat(concat(contrast(0.88f), brightness(0.04f)), concat(scale(1.06f, 1f, 0.9f), mix(identity(), sepia, 0.5f)))
        PhotoEffect.DUOTONE -> duotone(accent.mix(Color.Black, 0.85f), accent.mix(Color.White, 0.3f))
        PhotoEffect.WARM -> concat(scale(1.1f, 1.02f, 0.86f), saturation(1.1f))
        PhotoEffect.COOL -> concat(scale(0.88f, 1f, 1.12f), saturation(0.95f))
        PhotoEffect.POP -> concat(contrast(1.15f), saturation(1.7f))
        PhotoEffect.INVERT -> invert
    }

    /** Effect first, then saturation, contrast and brightness. */
    fun matrix(cfg: PhotoConfig, accent: Color): FloatArray =
        concat(brightness(cfg.brightness), concat(contrast(cfg.contrast), concat(saturation(cfg.saturation), effect(cfg.effect, accent))))

    /** Applies a matrix to one 0..255 RGB color (used by tests). */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).coerceIn(0f, 255f)
        return Triple(ch(0), ch(1), ch(2))
    }
}
