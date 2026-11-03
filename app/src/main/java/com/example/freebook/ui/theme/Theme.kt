package com.example.freebook.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF2F2F3),
    secondary = Color(0xFFA1A1AA),
    background = Color(0xFF101012),
    surface = Color(0xFF1A1A1E),
    onPrimary = Color(0xFF101012),
    onSecondary = Color(0xFFF2F2F3),
    onBackground = Color(0xFFF2F2F3),
    onSurface = Color(0xFFF2F2F3)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF141416),
    secondary = Color(0xFF52525B),
    background = Color(0xFFF7F6F2),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF141416),
    onBackground = Color(0xFF141416),
    onSurface = Color(0xFF141416)
)

@Composable
fun FreebookTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkBookFreePalette else LightBookFreePalette
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalBookFreePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
