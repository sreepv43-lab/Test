package io.github.sreepv43.soundhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF1FB5A8)
val FocusColor = Color(0xFFFFFFFF)
val AtmosColor = Color(0xFF8FB4FF)
val HiResColor = Color(0xFFFFC857)
val LosslessColor = Color(0xFF5ED3B5)
val WarningColor = Color(0xFFFFB86B)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = HiResColor,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EEF2),
    surface = Color(0xFF131A21),
    onSurface = Color(0xFFE8EEF2),
    surfaceVariant = Color(0xFF1C252E),
    onSurfaceVariant = Color(0xFFA9B6C2),
    secondaryContainer = Color(0xFF16423F),
    onSecondaryContainer = Color.White,
    error = Color(0xFFFF6B6B),
)

@Composable
fun SoundHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
