package io.github.sreepv43.soundhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF1FB5A8)
val FocusColor = Color(0xFFFFFFFF)
val AtmosColor = Color(0xFF8FB4FF)
val HiResColor = Color(0xFFFFC857)
val LosslessColor = Color(0xFF5ED3B5)
val WarningColor = Color(0xFFFFB86B)

object AppColors {
    val background = Color(0xFF0B0F14)
    val rail = Color(0xFF0E1319)
    val panel = Color(0xFF131A21)
    val row = Color(0xFF151D25)
    val rowFocused = Color(0xFF26323E)
    val text = Color(0xFFE8EEF2)
    val textDim = Color(0xFFA9B6C2)
}

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = HiResColor,
    background = AppColors.background,
    onBackground = AppColors.text,
    surface = AppColors.panel,
    onSurface = AppColors.text,
    surfaceVariant = Color(0xFF1C252E),
    onSurfaceVariant = AppColors.textDim,
    secondaryContainer = Color(0xFF16423F),
    onSecondaryContainer = Color.White,
    error = Color(0xFFFF6B6B),
)

// Read from across the room: a size up from the phone defaults.
private val base = Typography()
private val typography = base.copy(
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
)

@Composable
fun SoundHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
