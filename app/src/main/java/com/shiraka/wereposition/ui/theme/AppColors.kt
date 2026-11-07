package com.shiraka.wereposition.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    fun textSecondary(isDark: Boolean) = if (isDark) TextSecondaryDark else TextSecondaryLight
    fun surface(isDark: Boolean) = if (isDark) SurfaceDark else SurfaceLight
    fun cardBackground(isDark: Boolean) = if (isDark) SurfaceCardDark else SurfaceCardCustomLight
    fun topBarBackground(isDark: Boolean) = if (isDark) SurfaceDark else SurfaceCardCustomLight
    fun background(isDark: Boolean) = if (isDark) DarkBg else LightBg
}
