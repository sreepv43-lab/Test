package io.github.sreepv43.streamhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF7B5BF5)
val FocusColor = Color(0xFFFFFFFF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF3DDC97),
    background = Color(0xFF0F0D16),
    onBackground = Color(0xFFECEAF4),
    surface = Color(0xFF1A1724),
    onSurface = Color(0xFFECEAF4),
    surfaceVariant = Color(0xFF272336),
    onSurfaceVariant = Color(0xFFB9B4CC),
    secondaryContainer = Color(0xFF3A2F6B),
    onSecondaryContainer = Color.White,
    error = Color(0xFFFF6B6B),
)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
