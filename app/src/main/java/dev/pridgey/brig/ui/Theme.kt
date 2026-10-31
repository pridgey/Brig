package dev.pridgey.brig.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.util.BrigFonts
import dev.pridgey.brig.util.mix
import dev.pridgey.brig.util.shiftHue

private val base = Typography()

/** Bigger, bolder display type in the spirit of Material 3 Expressive. */
val BrigTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = BrigFonts.display),
    bodyMedium = base.bodyMedium.copy(fontFamily = BrigFonts.display),
    bodySmall = base.bodySmall.copy(fontFamily = BrigFonts.display),
    labelLarge = base.labelLarge.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = BrigFonts.display, fontWeight = FontWeight.Medium),
)

val BrigShapesTheme = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val HeroTitle = TextStyle(fontFamily = BrigFonts.display, fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-2).sp)

@Composable
fun BrigTheme(accent: Long? = null, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        accent != null -> seeded(Color(accent), dark)
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> seeded(Color(0xFF9DB7FF), dark)
    }
    MaterialTheme(colorScheme = scheme, typography = BrigTypography, shapes = BrigShapesTheme, content = content)
}

private fun seeded(c: Color, dark: Boolean) = if (dark) {
    val bg = Color(0xFF111318)
    darkColorScheme(
        primary = c,
        onPrimary = Color.Black,
        primaryContainer = c.mix(bg, 0.6f),
        onPrimaryContainer = c.mix(Color.White, 0.7f),
        secondary = c.shiftHue(25f).mix(Color.White, 0.25f),
        secondaryContainer = c.shiftHue(25f).mix(bg, 0.7f),
        onSecondaryContainer = c.mix(Color.White, 0.8f),
        tertiary = c.shiftHue(-60f).mix(Color.White, 0.2f),
        tertiaryContainer = c.shiftHue(-60f).mix(bg, 0.7f),
        background = bg,
        surface = bg,
        surfaceContainer = c.mix(bg, 0.9f),
        surfaceContainerHigh = c.mix(bg, 0.86f),
        surfaceContainerHighest = c.mix(bg, 0.8f),
    )
} else {
    val bg = Color(0xFFFAF9FD)
    val deep = c.mix(Color.Black, 0.4f)
    lightColorScheme(
        primary = deep,
        primaryContainer = c.mix(Color.White, 0.6f),
        onPrimaryContainer = c.mix(Color.Black, 0.7f),
        secondary = deep.shiftHue(25f),
        secondaryContainer = c.shiftHue(25f).mix(Color.White, 0.7f),
        tertiary = deep.shiftHue(-60f),
        tertiaryContainer = c.shiftHue(-60f).mix(Color.White, 0.7f),
        background = bg,
        surface = bg,
        surfaceContainer = c.mix(bg, 0.92f),
        surfaceContainerHigh = c.mix(bg, 0.88f),
        surfaceContainerHighest = c.mix(bg, 0.83f),
    )
}
