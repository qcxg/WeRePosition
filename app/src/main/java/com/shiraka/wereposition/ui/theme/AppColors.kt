package com.shiraka.wereposition.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 深色调色板
val DarkBg = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val SurfaceCardDark = Color(0xFF1C2333)
val DividerColorDark = Color(0xFF30363D)
val TextPrimaryDark = Color(0xFFE6EDF3)
val TextSecondaryDark = Color(0xFF8B949E)

// 浅色调色板
val LightBg = Color(0xFFF6F8FA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceCardLight = Color(0xFFFFFFFF)
val SurfaceCardCustomLight = Color(0xFFF3F6F9) // Custom color overriding MD3 default
val DividerColorLight = Color(0xFFD0D7DE)
val TextPrimaryLight = Color(0xFF24292F)
val TextSecondaryLight = Color(0xFF57606A)

// 强调色
val AccentBlue = Color(0xFF388BFD)
val AccentGreen = Color(0xFF2EA043)
val AccentOrange = Color(0xFFD29922)
val ErrorRed = Color(0xFFF85149)

val AppColorSchemeDark = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentGreen,
    onSecondary = Color.White,
    background = DarkBg,
    surface = SurfaceCardDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    outline = DividerColorDark,
    surfaceVariant = Color(0xFF263043),
    surfaceContainer = Color(0xFF1C2635),
    surfaceContainerHigh = Color(0xFF27364B),
    primaryContainer = Color(0xFF173760),
    onPrimaryContainer = Color(0xFFDBEAFF),
    error = ErrorRed
)

val AppColorSchemeLight = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentGreen,
    onSecondary = Color.White,
    background = LightBg,
    surface = SurfaceCardLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    outline = DividerColorLight,
    surfaceVariant = Color(0xFFE7EDF5),
    surfaceContainer = Color(0xFFF0F5FB),
    surfaceContainerHigh = Color(0xFFE7F0FA),
    primaryContainer = Color(0xFFD7E8FF),
    onPrimaryContainer = Color(0xFF123A65),
    error = Color(0xFFCF222E)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
)

object AppColors {
    @Composable fun textSecondary(isDark: Boolean) = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun surface(isDark: Boolean) = MaterialTheme.colorScheme.surface
    @Composable fun cardBackground(isDark: Boolean) = MaterialTheme.colorScheme.surfaceContainer
    @Composable fun topBarBackground(isDark: Boolean) = MaterialTheme.colorScheme.surfaceContainer
    @Composable fun background(isDark: Boolean) = MaterialTheme.colorScheme.background
}

fun tonedColorScheme(isDark: Boolean, tone: String): androidx.compose.material3.ColorScheme {
    val hue = tone.removePrefix("hue_").toFloatOrNull()?.coerceIn(0f, 360f) ?: when (tone) {
        "teal" -> 175f
        "violet" -> 270f
        "amber" -> 38f
        "rose" -> 338f
        else -> 215f
    }
    fun color(saturation: Float, value: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    val primary = color(if (isDark) 0.42f else 0.66f, if (isDark) 0.9f else 0.55f)
    val secondary = color(if (isDark) 0.27f else 0.42f, if (isDark) 0.79f else 0.49f)
    val tertiary = color(if (isDark) 0.33f else 0.48f, if (isDark) 0.84f else 0.54f)
    val container = color(if (isDark) 0.30f else 0.17f, if (isDark) 0.32f else 0.95f)
    val onContainer = if (isDark) color(0.24f, 0.94f) else color(0.65f, 0.28f)
    val surfaceLowest = color(if (isDark) 0.10f else 0.025f, if (isDark) 0.09f else 1.0f)
    val surfaceLow = color(if (isDark) 0.13f else 0.05f, if (isDark) 0.14f else 0.985f)
    val surfaceMid = color(if (isDark) 0.15f else 0.07f, if (isDark) 0.16f else 0.97f)
    val surfaceHigh = color(if (isDark) 0.17f else 0.09f, if (isDark) 0.22f else 0.94f)
    val surfaceHighest = color(if (isDark) 0.19f else 0.11f, if (isDark) 0.28f else 0.91f)
    val base = if (isDark) AppColorSchemeDark else AppColorSchemeLight
    return base.copy(
        background = color(if (isDark) 0.12f else 0.025f, if (isDark) 0.075f else 0.985f),
        onBackground = if (isDark) color(0.04f, 0.92f) else color(0.12f, 0.16f),
        surface = color(if (isDark) 0.09f else 0.025f, if (isDark) 0.12f else 0.995f),
        onSurface = if (isDark) color(0.04f, 0.92f) else color(0.12f, 0.16f),
        onSurfaceVariant = if (isDark) color(0.06f, 0.72f) else color(0.1f, 0.37f),
        primary = primary,
        onPrimary = if (isDark) Color.Black else Color.White,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = secondary,
        onSecondary = if (isDark) Color.Black else Color.White,
        secondaryContainer = container,
        onSecondaryContainer = if (isDark) color(0.24f, 0.94f) else color(0.55f, 0.28f),
        tertiary = tertiary,
        onTertiary = if (isDark) Color.Black else Color.White,
        tertiaryContainer = container,
        onTertiaryContainer = if (isDark) color(0.24f, 0.94f) else color(0.55f, 0.28f),
        surfaceVariant = if (isDark) color(0.17f, 0.22f) else color(0.09f, 0.94f),
        surfaceContainerLowest = surfaceLowest,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surfaceMid,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHighest,
        surfaceBright = if (isDark) surfaceHigh else surfaceLowest,
        surfaceDim = if (isDark) surfaceLowest else surfaceHigh,
        inverseSurface = if (isDark) surfaceHighest else color(0.10f, 0.18f),
        inverseOnSurface = if (isDark) color(0.10f, 0.18f) else surfaceLowest,
        inversePrimary = color(if (isDark) 0.65f else 0.36f, if (isDark) 0.58f else 0.75f),
        surfaceTint = primary,
        primaryFixed = color(0.25f, 0.92f),
        primaryFixedDim = color(0.38f, 0.82f),
        onPrimaryFixed = color(0.62f, 0.20f),
        onPrimaryFixedVariant = color(0.55f, 0.34f),
        secondaryFixed = color(0.18f, 0.90f),
        secondaryFixedDim = color(0.27f, 0.80f),
        onSecondaryFixed = color(0.45f, 0.20f),
        onSecondaryFixedVariant = color(0.40f, 0.34f),
        tertiaryFixed = color(0.24f, 0.90f),
        tertiaryFixedDim = color(0.33f, 0.80f),
        onTertiaryFixed = color(0.52f, 0.20f),
        onTertiaryFixedVariant = color(0.46f, 0.34f),
        outline = if (isDark) color(0.1f, 0.44f) else color(0.1f, 0.67f),
        outlineVariant = if (isDark) color(0.11f, 0.3f) else color(0.08f, 0.83f)
    )
}
