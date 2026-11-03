package com.example.freebook.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class BookFreePalette(
    val isDark: Boolean,
    val appBackground: Color,
    val readerBackground: Color,
    val surfaceColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val readerText: Color,
    val borderSubtle: Color,
    val pillSelectedBg: Color,
    val pillSelectedBorder: Color,
    val pillUnselectedBorder: Color,
    val dockBackground: Color,
    val dockBorder: Color,
    val dockTabActiveBg: Color,
    val dockTabActiveText: Color,
    val dockTabInactiveText: Color,
    val highlightMarkerBg: Color,
    val menuSurface: Color
)

val DarkBookFreePalette = BookFreePalette(
    isDark = true,
    appBackground = Color(0xFF101012),
    readerBackground = Color(0xFF000000),
    surfaceColor = Color(0xFF1A1A1E),
    textPrimary = Color(0xFFF2F2F3),
    textSecondary = Color(0xFFD4D4D8),
    textMuted = Color(0xFF7B7B80),
    readerText = Color(0xFFE2E2E5),
    borderSubtle = Color(0x18FFFFFF),
    pillSelectedBg = Color(0xFF1E1E21),
    pillSelectedBorder = Color(0xFF34343C),
    pillUnselectedBorder = Color(0xFF28282E),
    dockBackground = Color(0xE0242428),
    dockBorder = Color(0x24FFFFFF),
    dockTabActiveBg = Color(0xFF444448),
    dockTabActiveText = Color(0xFFFFFFFF),
    dockTabInactiveText = Color(0x99FFFFFF),
    highlightMarkerBg = Color(0x38FACC15),
    menuSurface = Color(0xFF1E1E22)
)

val LightBookFreePalette = BookFreePalette(
    isDark = false,
    appBackground = Color(0xFFF7F6F2),
    readerBackground = Color(0xFFFAF8F3),
    surfaceColor = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF141416),
    textSecondary = Color(0xFF3F3F46),
    textMuted = Color(0xFF71717A),
    readerText = Color(0xFF1F1F23),
    borderSubtle = Color(0x18000000),
    pillSelectedBg = Color(0xFF18181B),
    pillSelectedBorder = Color(0xFF18181B),
    pillUnselectedBorder = Color(0xFFD4D4D8),
    dockBackground = Color(0xEBFFFFFF),
    dockBorder = Color(0x1F000000),
    dockTabActiveBg = Color(0xFF18181B),
    dockTabActiveText = Color(0xFFFFFFFF),
    dockTabInactiveText = Color(0x9918181B),
    highlightMarkerBg = Color(0x55FDE047),
    menuSurface = Color(0xFFFFFFFF)
)

val LocalBookFreePalette = staticCompositionLocalOf { DarkBookFreePalette }
