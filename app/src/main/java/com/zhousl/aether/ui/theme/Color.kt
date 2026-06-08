package com.zhousl.aether.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.zhousl.aether.data.AppThemeMode

data class AetherPalette(
    val background: Color,
    val backgroundGradientTop: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineSoft: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val error: Color,
    val messageBubble: Color,
    val scrim: Color,
)

internal val LightAetherPalette = AetherPalette(
    background = Color(0xFFF7F7F8),
    backgroundGradientTop = Color(0xFFF4F4F5),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF1F1F0),
    surfaceHigher = Color(0xFFE9E9E7),
    surfaceVariant = Color(0xFFE2E2DE),
    outline = Color(0xFFD7D7D2),
    outlineSoft = Color(0xFFEBEBE8),
    onSurface = Color(0xFF202123),
    onSurfaceVariant = Color(0xFF6B6B66),
    primary = Color(0xFF10A37F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFF5EC),
    onPrimaryContainer = Color(0xFF0F513E),
    secondary = Color(0xFF5B6CFF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E9FF),
    onSecondaryContainer = Color(0xFF26306F),
    tertiary = Color(0xFFE48AAE),
    error = Color(0xFFD25757),
    messageBubble = Color(0xFFF0F0EF),
    scrim = Color(0x22000000),
)

internal val DarkAetherPalette = AetherPalette(
    background = Color(0xFF212121),
    backgroundGradientTop = Color(0xFF242424),
    surface = Color(0xFF2F2F2F),
    surfaceHigh = Color(0xFF3A3A3A),
    surfaceHigher = Color(0xFF444444),
    surfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFF555555),
    outlineSoft = Color(0xFF444444),
    onSurface = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFFC9C9C4),
    primary = Color(0xFF19C37D),
    onPrimary = Color(0xFF062B1F),
    primaryContainer = Color(0xFF123F31),
    onPrimaryContainer = Color(0xFFD7F8EC),
    secondary = Color(0xFF8EA1FF),
    onSecondary = Color(0xFF10184C),
    secondaryContainer = Color(0xFF27315F),
    onSecondaryContainer = Color(0xFFE3E7FF),
    tertiary = Color(0xFFF0A6C5),
    error = Color(0xFFFF8E8E),
    messageBubble = Color(0xFF3A3A3A),
    scrim = Color(0x66000000),
)

private var currentPalette by mutableStateOf(LightAetherPalette)

internal fun updateAetherPalette(themeMode: AppThemeMode) {
    val palette = if (themeMode == AppThemeMode.Dark) {
        DarkAetherPalette
    } else {
        LightAetherPalette
    }
    if (currentPalette != palette) {
        currentPalette = palette
    }
}

val AetherBackground: Color
    get() = currentPalette.background

val AetherBackgroundGradientTop: Color
    get() = currentPalette.backgroundGradientTop

val AetherSurface: Color
    get() = currentPalette.surface

val AetherSurfaceHigh: Color
    get() = currentPalette.surfaceHigh

val AetherSurfaceHigher: Color
    get() = currentPalette.surfaceHigher

val AetherSurfaceVariant: Color
    get() = currentPalette.surfaceVariant

val AetherOutline: Color
    get() = currentPalette.outline

val AetherOutlineSoft: Color
    get() = currentPalette.outlineSoft

val AetherOnSurface: Color
    get() = currentPalette.onSurface

val AetherOnSurfaceVariant: Color
    get() = currentPalette.onSurfaceVariant

val AetherPrimary: Color
    get() = currentPalette.primary

val AetherOnPrimary: Color
    get() = currentPalette.onPrimary

val AetherPrimaryContainer: Color
    get() = currentPalette.primaryContainer

val AetherOnPrimaryContainer: Color
    get() = currentPalette.onPrimaryContainer

val AetherSecondary: Color
    get() = currentPalette.secondary

val AetherOnSecondary: Color
    get() = currentPalette.onSecondary

val AetherSecondaryContainer: Color
    get() = currentPalette.secondaryContainer

val AetherOnSecondaryContainer: Color
    get() = currentPalette.onSecondaryContainer

val AetherTertiary: Color
    get() = currentPalette.tertiary

val AetherError: Color
    get() = currentPalette.error

val AetherMessageBubble: Color
    get() = currentPalette.messageBubble

val AetherScrim: Color
    get() = currentPalette.scrim
