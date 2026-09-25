package io.github.sreepv43.streamhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A colour theme. Every surface in the app is drawn with these solid colours only. */
@Immutable
data class Palette(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val background: Color,
    val panel: Color,
    val panelRaised: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textDim: Color,
    val focus: Color,
)

object Palettes {
    val DarkMoody = Palette(
        id = "dark", name = "Dark & moody", isLight = false,
        background = Color(0xFF0D0F14), panel = Color(0xFF171A21), panelRaised = Color(0xFF232733),
        accent = Color(0xFF4DA3FF), onAccent = Color(0xFF0D0F14),
        text = Color(0xFFF2F4F8), textDim = Color(0xFFA9B0BF), focus = Color(0xFFFFFFFF),
    )
    val Pastel = Palette(
        id = "pastel", name = "Pastel", isLight = true,
        background = Color(0xFFF6F1F8), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFEADFF3),
        accent = Color(0xFF9A7BD6), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF2F2A3A), textDim = Color(0xFF6F6883), focus = Color(0xFF7A5CC4),
    )
    val EarthTones = Palette(
        id = "earth", name = "Earth tones", isLight = false,
        background = Color(0xFF1B1713), panel = Color(0xFF28211B), panelRaised = Color(0xFF3A3027),
        accent = Color(0xFFD08A48), onAccent = Color(0xFF1B1713),
        text = Color(0xFFF2E9DC), textDim = Color(0xFFBFAE97), focus = Color(0xFFF2E9DC),
    )
    val VibrantBold = Palette(
        id = "vibrant", name = "Vibrant & bold", isLight = false,
        background = Color(0xFF09090F), panel = Color(0xFF151528), panelRaised = Color(0xFF24244A),
        accent = Color(0xFFFF3D7F), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFFFFFFFF), textDim = Color(0xFFB9B9DD), focus = Color(0xFF00E5FF),
    )

    val all = listOf(DarkMoody, Pastel, EarthTones, VibrantBold)

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: DarkMoody
}

val LocalPalette = staticCompositionLocalOf { Palettes.DarkMoody }

/** Current theme colours, e.g. `AppColors.panel`. */
object AppColors {
    val background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val panel: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
    val panelRaised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panelRaised
    val accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val onAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
    val text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val textDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textDim
    val focus: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.focus
}

@Composable
fun StreamHubTheme(palette: Palette = Palettes.DarkMoody, content: @Composable () -> Unit) {
    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            background = palette.background, onBackground = palette.text,
            surface = palette.panel, onSurface = palette.text,
            surfaceVariant = palette.panelRaised, onSurfaceVariant = palette.textDim,
            secondaryContainer = palette.panelRaised, onSecondaryContainer = palette.text,
            outline = palette.panelRaised, error = Color(0xFFC2415B),
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            background = palette.background, onBackground = palette.text,
            surface = palette.panel, onSurface = palette.text,
            surfaceVariant = palette.panelRaised, onSurfaceVariant = palette.textDim,
            secondaryContainer = palette.panelRaised, onSecondaryContainer = palette.text,
            outline = palette.panelRaised, error = Color(0xFFFF7A7A),
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
