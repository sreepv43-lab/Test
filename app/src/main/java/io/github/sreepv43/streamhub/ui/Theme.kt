package io.github.sreepv43.streamhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Cool, luminous accent used sparingly for selection and progress. */
val Accent = Color(0xFF9CC8FF)
val FocusColor = Color(0xFFFFFFFF)

/** Near-black with a hint of blue: lets the translucent "glass" layers and artwork glow. */
val Ink = Color(0xFF06070C)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF051226),
    secondary = Color(0xFFE6C9FF),
    background = Ink,
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF10131C),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xB3E6EAF5),
    secondaryContainer = Color(0x33FFFFFF),
    onSecondaryContainer = Color.White,
    outline = Color(0x40FFFFFF),
    error = Color(0xFFFF8A8A),
)

private val base = Typography()

/** Light display weights and airy tracking, as used by glass-style TV interfaces. */
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.01).em),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.01).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 21.sp, letterSpacing = 0.005.em),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium.copy(letterSpacing = 0.01.em),
    bodySmall = base.bodySmall.copy(letterSpacing = 0.02.em),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall.copy(letterSpacing = 0.06.em),
)

/** Small caps-like label used above rows ("CONTINUE WATCHING"). */
val EyebrowStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.em)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
