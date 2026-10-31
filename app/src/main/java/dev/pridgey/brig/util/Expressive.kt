package dev.pridgey.brig.util

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/*
 * Material 3 Expressive-style building blocks on stable libraries:
 * Pixel-like "cookie" polygon shapes, shape morphing and springy press feedback.
 */

object BrigShapes {
    val cookie4 = RoundedPolygon.star(4, radius = 1f, innerRadius = 0.72f, rounding = CornerRounding(0.42f))
    val cookie6 = RoundedPolygon.star(6, radius = 1f, innerRadius = 0.8f, rounding = CornerRounding(0.3f))
    val cookie9 = RoundedPolygon.star(9, radius = 1f, innerRadius = 0.86f, rounding = CornerRounding(0.22f))
    val cookie12 = RoundedPolygon.star(12, radius = 1f, innerRadius = 0.9f, rounding = CornerRounding(0.18f))
    val pentagon = RoundedPolygon(5, radius = 1f, rounding = CornerRounding(0.35f))
    val square = RoundedPolygon(4, radius = 1.35f, rounding = CornerRounding(0.5f))
    val circle = RoundedPolygon(12, radius = 1f, rounding = CornerRounding(1f))

    /** Rotating set used to give each settings section its own badge shape. */
    val badges = listOf(cookie9, cookie4, pentagon, cookie6, square, cookie12)
}

/** A [Shape] from a RoundedPolygon centred on (0,0) with radius ~1. */
class PolygonShape(private val polygon: RoundedPolygon, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath()
        path.transform(fitMatrix(size, rotation))
        return Outline.Generic(path.asComposePath())
    }
}

/** A [Shape] that morphs between two polygons; [progress] runs 0..1. */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(fitMatrix(size, rotation))
        return Outline.Generic(path.asComposePath())
    }
}

private fun fitMatrix(size: Size, rotation: Float) = android.graphics.Matrix().apply {
    setRotate(rotation)
    postScale(size.width / 2f, size.height / 2f)
    postTranslate(size.width / 2f, size.height / 2f)
}

/** Springy squish when pressed. Pass the same interaction source to the clickable. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.92f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

fun Color.shiftHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor((alpha * 255).toInt(), hsv))
}

fun Color.mix(other: Color, t: Float) = lerp(this, other, t)

/**
 * Pixel phones ship Google Sans as a system font; other phones fall back to the default sans.
 */
object BrigFonts {
    val display = FontFamily(
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
        Font(DeviceFontFamilyName("google-sans-medium"), FontWeight.Medium),
        Font(DeviceFontFamilyName("google-sans-bold"), FontWeight.Bold),
        Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    )
    val clock = FontFamily(
        Font(DeviceFontFamilyName("google-sans-clock"), FontWeight.Normal),
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
        Font(DeviceFontFamilyName("sans-serif-light"), FontWeight.Light),
        Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    )
}
