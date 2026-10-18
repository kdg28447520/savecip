package app.saveclip.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import android.animation.ValueAnimator
import app.saveclip.R

val Accent = Color(0xFFC2410C)
val Persimmon = Accent
val AccentDark = Color(0xFFFF8A4C)
val OnAccentDark = Color(0xFF121211)
val Ink = Color(0xFF1A1A18)
val InkMuted = Color(0xFF6B675E)
val Paper = Color(0xFFF5F1EA)
val Raised = Color(0xFFFBF9F5)
val Panel = Color(0xFFEFEAE1)
val Rule = Color(0xFFD9D3C8)
val Line = Color(0xFF8A857B)
val Danger = Color(0xFFB42318)
val PlayerPaper = Color(0xFF0B0B0A)

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF1E6),
    onPrimaryContainer = Color(0xFF9A3412),
    secondary = Ink,
    onSecondary = Paper,
    secondaryContainer = Panel,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Raised,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = InkMuted,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFEF3F2),
    onErrorContainer = Danger,
    outline = Line,
    outlineVariant = Rule,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = Color(0xFF3A2416),
    onPrimaryContainer = Color(0xFFFFB487),
    secondary = Color(0xFFF2EEE6),
    onSecondary = Color(0xFF121211),
    secondaryContainer = Color(0xFF232220),
    onSecondaryContainer = Color(0xFFF2EEE6),
    background = Color(0xFF121211),
    onBackground = Color(0xFFF2EEE6),
    surface = Color(0xFF1B1A18),
    onSurface = Color(0xFFF2EEE6),
    surfaceVariant = Color(0xFF232220),
    onSurfaceVariant = Color(0xFFA8A397),
    error = Color(0xFFF87171),
    onError = Color(0xFF121211),
    errorContainer = Color(0xFF3A1A1A),
    onErrorContainer = Color(0xFFF87171),
    outline = Color(0xFF6F6A61),
    outlineVariant = Color(0xFF2C2B28),
    inverseSurface = Color(0xFFF2EEE6),
    inverseOnSurface = Color(0xFF121211),
)

private val PlayerColors = DarkColors.copy(
    background = PlayerPaper,
    surface = PlayerPaper,
    outlineVariant = Color(0x17F2EEE6),
)

private fun text(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum",
)

fun monoStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontFamily = PlexMono,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.02.em,
    fontFeatureSettings = "tnum",
)

private val Type = Typography(
    displaySmall = text(28, 36, FontWeight.ExtraBold, -0.03f),
    headlineLarge = text(28, 36, FontWeight.ExtraBold, -0.03f),
    headlineMedium = text(22, 30, FontWeight.ExtraBold, -0.02f),
    titleLarge = text(20, 28, FontWeight.ExtraBold, -0.02f),
    titleMedium = text(14, 20, FontWeight.SemiBold),
    titleSmall = text(13, 18, FontWeight.SemiBold),
    bodyLarge = text(15, 24, FontWeight.Normal),
    bodyMedium = text(14, 20, FontWeight.Normal),
    bodySmall = text(12, 16, FontWeight.Normal),
    labelLarge = text(14, 20, FontWeight.SemiBold),
    labelMedium = text(13, 18, FontWeight.SemiBold),
    labelSmall = text(12, 16, FontWeight.Normal),
)

private val SaveClipShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val LocalMotionEnabled = staticCompositionLocalOf { true }

object ScMotion {
    const val Fast = 120
    const val Base = 200
    const val Sheet = 500
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Emphasized = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val Exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    fun <T> sheetMorph() = spring<T>(dampingRatio = 0.95f, stiffness = 630f)
    fun <T> indicator() = spring<T>(dampingRatio = 0.8f, stiffness = 440f)
    fun <T> toolbar() = spring<T>(dampingRatio = 0.9f, stiffness = 630f)
    fun <T> digit() = spring<T>(dampingRatio = 0.98f, stiffness = 933f)
    fun <T> badgePop() = spring<T>(dampingRatio = 0.6f, stiffness = 700f)

    fun <T> spec(enabled: Boolean, animated: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> {
        return if (enabled) animated else snap()
    }

    fun <T> drawerTween(enabled: Boolean): FiniteAnimationSpec<T> = spec(enabled, tween(Sheet, easing = Drawer))
}

@Composable
fun rememberMotionEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

@Composable
fun SaveClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val motion = rememberMotionEnabled()
    CompositionLocalProvider(LocalMotionEnabled provides motion) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = Type,
            shapes = SaveClipShapes,
            content = content,
        )
    }
}

@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PlayerColors,
        typography = Type,
        shapes = SaveClipShapes,
        content = content,
    )
}
