package com.wisperlow.mobile.keyboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The keyboard wears the app's clothes: a soft lilac canvas, quiet keycaps, and one
 * violet used sparingly (enter, the chosen suggestion, the mic while listening).
 */
@Immutable
class KeyboardColors(
    val background: Color,
    /** A faint lilac wash at the top edge, like the app's onboarding screens. */
    val wash: Color,
    val key: Color,
    val keyPressed: Color,
    val keyShadow: Color,
    val keyText: Color,
    val keyHint: Color,
    val modKey: Color,
    val modText: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val stripText: Color,
    val stripMuted: Color,
    val divider: Color,
    val popup: Color,
    val popupText: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val error: Color,
)

private val Light = KeyboardColors(
    background = Color(0xFFF3F1F6),
    wash = Color(0xFFE9E3FF),
    key = Color.White,
    keyPressed = Color(0xFFEFEAFF),
    keyShadow = Color(0x1A19171F),
    keyText = Color(0xFF19171F),
    keyHint = Color(0xFFA19CAB),
    modKey = Color(0xFFE5E1EC),
    modText = Color(0xFF3B3546),
    accent = Color(0xFF5B3FD9),
    onAccent = Color.White,
    accentContainer = Color(0xFFEFEAFF),
    onAccentContainer = Color(0xFF5B3FD9),
    stripText = Color(0xFF19171F),
    stripMuted = Color(0xFF8A8496),
    divider = Color(0x1419171F),
    popup = Color.White,
    popupText = Color(0xFF19171F),
    orbPrimary = Color(0xFF5B3FD9),
    orbSecondary = Color(0xFF7257E8),
    error = Color(0xFFB3261E),
)

private val Dark = KeyboardColors(
    background = Color(0xFF121015),
    wash = Color(0xFF241C3F),
    key = Color(0xFF26222B),
    keyPressed = Color(0xFF3A2C86),
    keyShadow = Color(0x66000000),
    keyText = Color(0xFFF3EFF6),
    keyHint = Color(0xFF7D7686),
    modKey = Color(0xFF1C191F),
    modText = Color(0xFFC9C2CE),
    accent = Color(0xFF7257E8),
    onAccent = Color.White,
    accentContainer = Color(0xFF32294F),
    onAccentContainer = Color(0xFFC4B6FF),
    stripText = Color(0xFFF3EFF6),
    stripMuted = Color(0xFF8F8899),
    divider = Color(0x14FFFFFF),
    popup = Color(0xFF2E2934),
    popupText = Color(0xFFF3EFF6),
    orbPrimary = Color(0xFF7257E8),
    orbSecondary = Color(0xFFB9A9FF),
    error = Color(0xFFFFB4AB),
)

val LocalKeyboardColors = staticCompositionLocalOf { Light }

@Composable
fun keyboardColors(): KeyboardColors = if (isSystemInDarkTheme()) Dark else Light
