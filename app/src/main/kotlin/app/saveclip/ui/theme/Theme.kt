package app.saveclip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saveclip.R

val Accent = Color(0xFFC2410C)
val Persimmon = Accent
val AccentDark = Color(0xFFFF8A4C)
val OnAccentDark = Color(0xFF1F1206)
val Ink = Color(0xFF1C1917)
val Paper = Color(0xFFFAF8F6)
val Card = Color(0xFFFFFFFF)
val Danger = Color(0xFFB91C1C)
val Line = Color(0xFFE7E2DC)
val InkMuted = Color(0xFF57534E)

private val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF1E6),
    onPrimaryContainer = Color(0xFF9A3412),
    secondary = InkMuted,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F0EC),
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3F0EC),
    onSurfaceVariant = InkMuted,
    error = Danger,
    onError = Color.White,
    outline = Line,
    inverseSurface = Ink,
    inverseOnSurface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = Color(0xFF3A2416),
    onPrimaryContainer = Color(0xFFFFB487),
    secondary = Color(0xFFB8B0A7),
    onSecondary = Color(0xFF1C1917),
    secondaryContainer = Color(0xFF26231F),
    onSecondaryContainer = Color(0xFFF5F2EE),
    background = Color(0xFF141210),
    onBackground = Color(0xFFF5F2EE),
    surface = Color(0xFF1C1A17),
    onSurface = Color(0xFFF5F2EE),
    surfaceVariant = Color(0xFF26231F),
    onSurfaceVariant = Color(0xFFB8B0A7),
    error = Color(0xFFF87171),
    onError = Color(0xFF1C1917),
    outline = Color(0xFF37322C),
    inverseSurface = Color(0xFFF5F2EE),
    inverseOnSurface = Color(0xFF1C1917),
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    fontFeatureSettings = "tnum",
)

private val Type = Typography(
    headlineMedium = style(22, 28, FontWeight.Bold),
    titleLarge = style(20, 28, FontWeight.Bold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(15, 22, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(13, 18, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold),
    labelSmall = style(12, 16, FontWeight.Normal),
)

private val SaveClipShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SaveClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Type,
        shapes = SaveClipShapes,
        content = content,
    )
}
