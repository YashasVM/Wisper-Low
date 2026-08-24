package com.wisperlow.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object WisperlowColors {
    val Ink = Color(0xFF19171F)
    val Violet = Color(0xFF7257E8)
    val Lilac = Color(0xFFEFEAFF)
    val Canvas = Color(0xFFF8F7FA)
    val Success = Color(0xFF287A57)
}

private val LightColors = lightColorScheme(
    primary = WisperlowColors.Ink,
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
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F1F8),
    onPrimary = Color(0xFF1A171F),
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
)

private val WisperlowTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val WisperlowShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun WisperlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = WisperlowTypography,
        shapes = WisperlowShapes,
        content = content,
    )
}
