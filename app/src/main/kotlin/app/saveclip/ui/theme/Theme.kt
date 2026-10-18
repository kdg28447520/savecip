package app.saveclip.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Persimmon = Color(0xFFE25B2A)
val PersimmonDeep = Color(0xFFB8431C)
val Ink = Color(0xFF1C1915)
val Paper = Color(0xFFF4F0E8)
val Card = Color(0xFFFFFCF8)
val Pine = Color(0xFF1F6B54)
val Moss = Color(0xFFE5F2EC)
val Danger = Color(0xFF9C2F2F)
val Line = Color(0xFFE4DDD2)
val InkMuted = Color(0xFF5C564C)

private val LightColors = lightColorScheme(
    primary = Persimmon,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4D6),
    onPrimaryContainer = PersimmonDeep,
    secondary = Pine,
    onSecondary = Color.White,
    secondaryContainer = Moss,
    onSecondaryContainer = Color(0xFF12382C),
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E0D6),
    onSurfaceVariant = InkMuted,
    error = Danger,
    onError = Color.White,
    outline = Line,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A5B),
    onPrimary = Color(0xFF3B1408),
    primaryContainer = Color(0xFF6A2C16),
    onPrimaryContainer = Color(0xFFFFD8C8),
    secondary = Color(0xFF8FCBB6),
    onSecondary = Color(0xFF073328),
    secondaryContainer = Color(0xFF1C463A),
    onSecondaryContainer = Color(0xFFD5F3E8),
    background = Color(0xFF141210),
    onBackground = Color(0xFFF3EDE4),
    surface = Color(0xFF1E1B17),
    onSurface = Color(0xFFF3EDE4),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFD0C6BA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF3A342E),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

private val SaveClipShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun SaveClipTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Type,
        shapes = SaveClipShapes,
        content = content,
    )
}
