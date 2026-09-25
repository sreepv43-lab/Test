package io.github.sreepv43.streamhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Flat, solid palette: dark background, slightly lighter panels, one accent. */
val Ink = Color(0xFF0D0F14)
val Panel = Color(0xFF171A21)
val PanelRaised = Color(0xFF232733)
val Accent = Color(0xFF4DA3FF)
val FocusColor = Color(0xFFFFFFFF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Color(0xFF8FD3A8),
    background = Ink,
    onBackground = Color(0xFFF2F4F8),
    surface = Panel,
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Color(0xFFA9B0BF),
    secondaryContainer = PanelRaised,
    onSecondaryContainer = Color.White,
    outline = Color(0xFF3A4050),
    error = Color(0xFFFF7A7A),
)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
