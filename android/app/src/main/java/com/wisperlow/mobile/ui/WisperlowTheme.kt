package com.wisperlow.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisperlow.mobile.R

/** Shared motion tokens so every screen moves with the same rhythm. */
object Motion {
    const val Short = 160
    const val Medium = 280
    const val Long = 420
    val Easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Springs for interactive elements; settle quickly with a hint of overshoot. */
    fun <T> spring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (enabled) androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f) else androidx.compose.animation.core.snap()
    fun <T> bouncy(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (enabled) androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 420f) else androidx.compose.animation.core.snap()

    /** False when the system animator scale is 0 (reduced motion); durations collapse to zero. */
    val enabled: Boolean get() = android.animation.ValueAnimator.areAnimatorsEnabled()
    private fun ms(d: Int) = if (enabled) d else 0
    fun <T> tween() = androidx.compose.animation.core.tween<T>(ms(Medium), easing = Easing)
    fun <T> fade() = androidx.compose.animation.core.tween<T>(ms(Short), easing = Easing)
    fun <T> slow() = androidx.compose.animation.core.tween<T>(ms(Long), easing = Easing)

    /** Tight tracking for live signals such as the mic level. */
    fun <T> level() = androidx.compose.animation.core.tween<T>(ms(90), easing = androidx.compose.animation.core.LinearEasing)
    /** Orb completion: the check draws in, then the ripple fades out. */
    fun <T> checkDraw() = androidx.compose.animation.core.tween<T>(ms(520), easing = Easing)
    fun <T> burst() = androidx.compose.animation.core.tween<T>(ms(1100), easing = Easing)
    /** Ambient orb drift and breathing; slow enough to stay in the background. */
    fun <T> drift() = androidx.compose.animation.core.tween<T>(9000, easing = androidx.compose.animation.core.LinearEasing)
    fun <T> breathe() = androidx.compose.animation.core.tween<T>(2400, easing = androidx.compose.animation.core.LinearEasing)
}

/** Shared spacing scale. */
object Space {
    val Xs = 4.dp
    val S = 8.dp
    val Sm = 12.dp
    val M = 16.dp
    val Ml = 20.dp
    val L = 24.dp
    val Xl = 32.dp
}

object WisperlowColors {
    val Ink = Color(0xFF19171F)
    val Violet = Color(0xFF7257E8)
    val Lilac = Color(0xFFEFEAFF)
    val Canvas = Color(0xFFF8F7FA)
    val Success = Color(0xFF287A57)
    val BubbleSurface = Color(0xFF211F29)
    val BubbleListening = Color(0xFF4936A0)
    val BubbleSuccess = Color(0xFF1F6B4B)
    val BubbleOutline = Color(0x40FFFFFF)
    val Danger = Color(0xFFD14343)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    secondary = WisperlowColors.Violet,
    onSecondary = Color.White,
    tertiary = WisperlowColors.Success,
    background = WisperlowColors.Canvas,
    onBackground = WisperlowColors.Ink,
    surface = Color.White,
    onSurface = WisperlowColors.Ink,
    surfaceVariant = Color(0xFFF0EEF3),
    onSurfaceVariant = Color(0xFF5E5A66),
    outline = Color(0xFFD8D4DE),
    primaryContainer = WisperlowColors.Lilac,
    onPrimaryContainer = Color(0xFF2A1A73),
    secondaryContainer = Color(0xFFE9E3FF),
    onSecondaryContainer = Color(0xFF2A1A73),
    tertiaryContainer = Color(0xFFD9F2E6),
    onTertiaryContainer = Color(0xFF0E3B27),
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFF6B1414),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4B6FF),
    onPrimary = Color(0xFF24145E),
    secondary = Color(0xFFB9A9FF),
    onSecondary = Color(0xFF24145E),
    tertiary = Color(0xFF83D7AE),
    background = Color(0xFF121015),
    onBackground = Color(0xFFF3EFF6),
    surface = Color(0xFF1D1A21),
    onSurface = Color(0xFFF3EFF6),
    surfaceVariant = Color(0xFF2A262E),
    onSurfaceVariant = Color(0xFFC9C2CE),
    outline = Color(0xFF4B454F),
    primaryContainer = Color(0xFF3A2C86),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondaryContainer = Color(0xFF32294F),
    onSecondaryContainer = Color(0xFFE9E3FF),
    tertiaryContainer = Color(0xFF173D2C),
    onTertiaryContainer = Color(0xFFBDEFD6),
    errorContainer = Color(0xFF4A1C1C),
    onErrorContainer = Color(0xFFFFDAD6),
)

// The same type system as the Wisperlow download page: an editorial serif for headings,
// Inter at light-to-regular weights for reading, and a monospace for small meta labels.
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SerifFamily = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)
val SansFamily = FontFamily(inter(300), inter(400), inter(500), inter(600))
val MonoFamily = FontFamily(mono(400), mono(500))

private val WisperlowTypography = androidx.compose.material3.Typography(
    displayMedium = TextStyle(
        fontFamily = SerifFamily,
        fontSize = 48.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SerifFamily,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SerifFamily,
        fontSize = 36.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SerifFamily,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SerifFamily,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFamily,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val WisperlowShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun WisperlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WisperlowTypography,
        shapes = WisperlowShapes,
        content = content,
    )
}
