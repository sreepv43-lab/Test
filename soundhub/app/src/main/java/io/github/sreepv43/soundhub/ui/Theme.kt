package io.github.sreepv43.soundhub.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A colour theme: the backdrop behind the cards (a gradient from [background] to [backgroundEnd]),
 * the side menu ([rail] to [railEnd]), cards ([panel]), text, the accent, the selection outline and
 * the format badge colours.
 */
data class Palette(
    val id: String,
    val name: String,
    val description: String,
    val dark: Boolean,
    val background: Color,
    val backgroundEnd: Color,
    val rail: Color,
    val railEnd: Color,
    /** Icons and labels on the side menu. */
    val onRail: Color,
    /** The pill behind the section shown, and its icon and label. */
    val railSelected: Color,
    val onRailSelected: Color,
    val panel: Color,
    val row: Color,
    val rowFocused: Color,
    val surfaceVariant: Color,
    val text: Color,
    val textDim: Color,
    /** Text and icons on a selected chip or button, which is filled with [text]. */
    val onText: Color,
    val accent: Color,
    val onAccent: Color,
    val focus: Color,
    val atmos: Color,
    val hiRes: Color,
    val lossless: Color,
    val warning: Color,
    val error: Color,
    /** The heart on favourite songs. */
    val favourite: Color,
)

private fun darkPalette(
    id: String,
    name: String,
    description: String,
    background: Long,
    rail: Long,
    panel: Long,
    row: Long,
    rowFocused: Long,
    surfaceVariant: Long,
    accent: Long,
    text: Long = 0xFFE8EEF2,
    textDim: Long = 0xFFA9B6C2,
) = Palette(
    id = id,
    name = name,
    description = description,
    dark = true,
    background = Color(background),
    backgroundEnd = Color(background),
    rail = Color(rail),
    railEnd = Color(rail),
    onRail = Color(textDim),
    railSelected = Color(rowFocused),
    onRailSelected = Color(accent),
    panel = Color(panel),
    row = Color(row),
    rowFocused = Color(rowFocused),
    surfaceVariant = Color(surfaceVariant),
    text = Color(text),
    textDim = Color(textDim),
    onText = Color.Black,
    accent = Color(accent),
    onAccent = Color.Black,
    focus = Color.White,
    atmos = Color(0xFF8FB4FF),
    hiRes = Color(0xFFFFC857),
    lossless = Color(0xFF5ED3B5),
    warning = Color(0xFFFFB86B),
    error = Color(0xFFFF6B6B),
    favourite = Color(0xFF3DDC97),
)

object Palettes {
    /** Light blue with white cards and a blue side menu (the default). */
    val SKY = Palette(
        id = "sky",
        name = "Sky",
        description = "Light blue with white cards",
        dark = false,
        background = Color(0xFFD3EAFD),
        backgroundEnd = Color(0xFF8CC6F4),
        rail = Color(0xFF64B7F6),
        railEnd = Color(0xFF3B98EA),
        onRail = Color.White,
        railSelected = Color(0xFFEAF5FF),
        onRailSelected = Color(0xFF1976D2),
        panel = Color(0xFFFFFFFF),
        row = Color(0xFFFFFFFF),
        rowFocused = Color(0xFFE2F0FD),
        surfaceVariant = Color(0xFFEEF2F7),
        text = Color(0xFF0E1A2B),
        textDim = Color(0xFF5A6A7C),
        onText = Color.White,
        accent = Color(0xFF1E88E5),
        onAccent = Color.White,
        focus = Color(0xFF0B2545),
        atmos = Color(0xFF2F5BD3),
        hiRes = Color(0xFF9A6400),
        lossless = Color(0xFF00796B),
        warning = Color(0xFFB45309),
        error = Color(0xFFC62828),
        favourite = Color(0xFF22C55E),
    )
    val TEAL = darkPalette(
        "teal", "Teal", "The original: dark slate with teal",
        background = 0xFF0B0F14, rail = 0xFF0E1319, panel = 0xFF131A21, row = 0xFF151D25, rowFocused = 0xFF26323E,
        surfaceVariant = 0xFF1C252E, accent = 0xFF1FB5A8,
    )
    val OCEAN = darkPalette(
        "ocean", "Ocean", "Deep navy with bright blue",
        background = 0xFF0A1020, rail = 0xFF0C1326, panel = 0xFF111A30, row = 0xFF142039, rowFocused = 0xFF223457,
        surfaceVariant = 0xFF1A2946, accent = 0xFF4DA3FF,
    )
    val VIOLET = darkPalette(
        "violet", "Violet", "Dark plum with lavender",
        background = 0xFF100C18, rail = 0xFF140F1F, panel = 0xFF1A1428, row = 0xFF211930, rowFocused = 0xFF35284F,
        surfaceVariant = 0xFF2A2040, accent = 0xFFB388FF,
    )
    val AMBER = darkPalette(
        "amber", "Amber", "Warm brown-black with gold, like valve amplifiers",
        background = 0xFF12100B, rail = 0xFF17140E, panel = 0xFF1E1A12, row = 0xFF241F15, rowFocused = 0xFF3A3222,
        surfaceVariant = 0xFF2E281B, accent = 0xFFFFB300, text = 0xFFF3ECDD, textDim = 0xFFBDB29C,
    )
    val CRIMSON = darkPalette(
        "crimson", "Crimson", "Near-black with rose red",
        background = 0xFF140B0D, rail = 0xFF190E11, panel = 0xFF211317, row = 0xFF28171C, rowFocused = 0xFF40252D,
        surfaceVariant = 0xFF331D23, accent = 0xFFFF5C7A,
    )
    val BLACK = darkPalette(
        "black", "Pure black", "True black for OLED TVs, with green",
        background = 0xFF000000, rail = 0xFF000000, panel = 0xFF0D0D0D, row = 0xFF121212, rowFocused = 0xFF2A2A2A,
        surfaceVariant = 0xFF1E1E1E, accent = 0xFF3DDC97,
    )
    val LIGHT = Palette(
        id = "light",
        name = "Light",
        description = "Bright, for tablets in daylight",
        dark = false,
        background = Color(0xFFF3F5F7),
        backgroundEnd = Color(0xFFF3F5F7),
        rail = Color(0xFFE6EBEF),
        railEnd = Color(0xFFE6EBEF),
        onRail = Color(0xFF55636F),
        railSelected = Color.White,
        onRailSelected = Color(0xFF00897B),
        panel = Color(0xFFFFFFFF),
        row = Color(0xFFFFFFFF),
        rowFocused = Color(0xFFD5E2EA),
        surfaceVariant = Color(0xFFE2E8ED),
        text = Color(0xFF15202A),
        textDim = Color(0xFF55636F),
        onText = Color.White,
        accent = Color(0xFF00897B),
        onAccent = Color.White,
        focus = Color(0xFF15202A),
        atmos = Color(0xFF2F5BD3),
        hiRes = Color(0xFF9A6400),
        lossless = Color(0xFF00796B),
        warning = Color(0xFFB45309),
        error = Color(0xFFC62828),
        favourite = Color(0xFF16A34A),
    )

    val all = listOf(SKY, TEAL, OCEAN, VIOLET, AMBER, CRIMSON, BLACK, LIGHT)

    fun byId(id: String): Palette = all.firstOrNull { it.id == id } ?: SKY
}

/**
 * The theme in use. Everything below reads it, so changing it (Settings → Appearance) recolours
 * every page at once, including what is already on screen.
 */
var currentPalette: Palette by mutableStateOf(Palettes.SKY)

val Accent: Color get() = currentPalette.accent
val FocusColor: Color get() = currentPalette.focus
val AtmosColor: Color get() = currentPalette.atmos
val HiResColor: Color get() = currentPalette.hiRes
val LosslessColor: Color get() = currentPalette.lossless
val WarningColor: Color get() = currentPalette.warning
val FavouriteColor: Color get() = currentPalette.favourite

object AppColors {
    val background: Color get() = currentPalette.background
    val rail: Color get() = currentPalette.rail
    val onRail: Color get() = currentPalette.onRail
    val railSelected: Color get() = currentPalette.railSelected
    val onRailSelected: Color get() = currentPalette.onRailSelected
    val panel: Color get() = currentPalette.panel
    val surfaceVariant: Color get() = currentPalette.surfaceVariant
    val row: Color get() = currentPalette.row
    val rowFocused: Color get() = currentPalette.rowFocused
    val text: Color get() = currentPalette.text
    val textDim: Color get() = currentPalette.textDim
    val onText: Color get() = currentPalette.onText

    /** Behind the side menu and the cards. */
    val backdrop: Brush get() = Brush.linearGradient(listOf(currentPalette.background, currentPalette.backgroundEnd))
    val railBrush: Brush get() = Brush.verticalGradient(listOf(currentPalette.rail, currentPalette.railEnd))
}

private fun Palette.colorScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        secondary = hiRes,
        background = background,
        onBackground = text,
        surface = panel,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textDim,
        secondaryContainer = rowFocused,
        onSecondaryContainer = text,
        // Switches, outlined buttons and fields in the theme's own greys, not Material's.
        surfaceContainerHigh = surfaceVariant,
        surfaceContainerHighest = surfaceVariant,
        outline = textDim,
        outlineVariant = surfaceVariant,
        error = error,
    )
}

// Read from across the room: a size up from the phone defaults.
private val base = Typography()
private val typography = base.copy(
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun SoundHubTheme(content: @Composable () -> Unit) {
    val palette = currentPalette
    MaterialTheme(colorScheme = palette.colorScheme(), typography = typography, content = content)
}
