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
        id = "pastel", name = "Pastel lavender", isLight = true,
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

    val PastelMint = Palette(
        id = "pastel-mint", name = "Pastel mint", isLight = true,
        background = Color(0xFFEFF8F3), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFD6EFE2),
        accent = Color(0xFF3E9A78), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF1F3A30), textDim = Color(0xFF5E7A6E), focus = Color(0xFF2E8A66),
    )
    val PastelPeach = Palette(
        id = "pastel-peach", name = "Pastel peach", isLight = true,
        background = Color(0xFFFFF4EE), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFFFE0D1),
        accent = Color(0xFFD9704F), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF3B2A24), textDim = Color(0xFF7D655B), focus = Color(0xFFC75A3C),
    )
    val PastelSky = Palette(
        id = "pastel-sky", name = "Pastel sky", isLight = true,
        background = Color(0xFFEFF5FC), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFD6E6F7),
        accent = Color(0xFF3F84D0), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF1F2C3D), textDim = Color(0xFF5E6F85), focus = Color(0xFF2F6FBA),
    )
    val PastelRose = Palette(
        id = "pastel-rose", name = "Pastel rose", isLight = true,
        background = Color(0xFFFCF1F4), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFF6DCE4),
        accent = Color(0xFFCB5F83), onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF3A2530), textDim = Color(0xFF80636F), focus = Color(0xFFB64B70),
    )
    val PastelLemon = Palette(
        id = "pastel-lemon", name = "Pastel lemon", isLight = true,
        background = Color(0xFFFFFBEA), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFF7EDC2),
        accent = Color(0xFFE2BC3A), onAccent = Color(0xFF2E2A1A),
        text = Color(0xFF2E2A1A), textDim = Color(0xFF7A7152), focus = Color(0xFFA88412),
    )
    val CottonCandy = Palette(
        id = "cotton-candy", name = "Cotton candy", isLight = true,
        background = Color(0xFFF8F1FA), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFF1DDF0),
        accent = Color(0xFF8FB4EE), onAccent = Color(0xFF1F2A44),
        text = Color(0xFF34304A), textDim = Color(0xFF76708F), focus = Color(0xFFD9679F),
    )
    val PastelNight = Palette(
        id = "pastel-night", name = "Pastel night", isLight = false,
        background = Color(0xFF1A1B26), panel = Color(0xFF232536), panelRaised = Color(0xFF30334A),
        accent = Color(0xFFB8A6F0), onAccent = Color(0xFF1A1B26),
        text = Color(0xFFF1EEFA), textDim = Color(0xFFADA8C6), focus = Color(0xFFF7C8E0),
    )

    val all = listOf(
        DarkMoody, EarthTones, VibrantBold, PastelNight,
        Pastel, PastelMint, PastelPeach, PastelSky, PastelRose, PastelLemon, CottonCandy,
    )

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
