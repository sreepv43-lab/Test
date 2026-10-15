package io.github.sreepv43.soundhub.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

/** A dark colour set for the flat layouts (Simple, Cinema): one background, cards a step lighter. */
private class FlatSpec(
    val id: String,
    val name: String,
    val description: String,
    val background: Long,
    val card: Long,
    val cardFocused: Long,
    val variant: Long,
    val accent: Long,
    val text: Long,
    val textDim: Long,
)

private fun flat(prefix: String, spec: FlatSpec) = Palette(
    id = prefix + spec.id,
    name = spec.name,
    description = spec.description,
    dark = true,
    background = Color(spec.background),
    backgroundEnd = Color(spec.background),
    rail = Color(spec.background),
    railEnd = Color(spec.background),
    onRail = Color(spec.text),
    railSelected = Color(spec.card),
    onRailSelected = Color(spec.accent),
    panel = Color(spec.card),
    row = Color(spec.card),
    rowFocused = Color(spec.cardFocused),
    surfaceVariant = Color(spec.variant),
    text = Color(spec.text),
    textDim = Color(spec.textDim),
    onText = Color(spec.background),
    accent = Color(spec.accent),
    onAccent = Color(spec.background),
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
    val PLUM = darkPalette(
        "plum", "SoundHub plum", "Deep plum with coral, like the app icon",
        background = 0xFF1F0A28, rail = 0xFF260D31, panel = 0xFF301240, row = 0xFF371748, rowFocused = 0xFF4D2663,
        surfaceVariant = 0xFF3F1C55, accent = 0xFFFF5A47, text = 0xFFFDF4E6, textDim = 0xFFCBB5D6,
    )
    val INDIGO = darkPalette(
        "indigo", "Indigo", "Rich blue-violet with gold",
        background = 0xFF14123A, rail = 0xFF191648, panel = 0xFF221F5A, row = 0xFF29266A, rowFocused = 0xFF3E3A92,
        surfaceVariant = 0xFF312E7C, accent = 0xFFFFC857, text = 0xFFF1F0FF, textDim = 0xFFB8B6E6,
    )
    val FOREST = darkPalette(
        "forest", "Forest", "Deep green with mint",
        background = 0xFF0B1A14, rail = 0xFF0E211A, panel = 0xFF133026, row = 0xFF173A2E, rowFocused = 0xFF245244,
        surfaceVariant = 0xFF1D4538, accent = 0xFF7DE3A1, text = 0xFFEAF6EE, textDim = 0xFFA9C7B5,
    )
    val SLATE = darkPalette(
        "slate", "Slate", "Medium blue-grey with sky blue",
        background = 0xFF1B2330, rail = 0xFF202A39, panel = 0xFF283446, row = 0xFF2E3C50, rowFocused = 0xFF44577A,
        surfaceVariant = 0xFF364560, accent = 0xFF8AB4FF, text = 0xFFEDF2FA, textDim = 0xFFAEBBCF,
    )
    val ESPRESSO = darkPalette(
        "espresso", "Espresso", "Coffee brown with orange",
        background = 0xFF1E1410, rail = 0xFF261A15, panel = 0xFF33241C, row = 0xFF3B2A21, rowFocused = 0xFF553D2F,
        surfaceVariant = 0xFF45322A, accent = 0xFFFF9E64, text = 0xFFF6EBE0, textDim = 0xFFC7B3A3,
    )
    val BURGUNDY = darkPalette(
        "burgundy", "Burgundy", "Wine red with rose",
        background = 0xFF2A0D16, rail = 0xFF33101B, panel = 0xFF42172A, row = 0xFF4A1B2C, rowFocused = 0xFF6B2A41,
        surfaceVariant = 0xFF55202F, accent = 0xFFFF8FA3, text = 0xFFFCEDF0, textDim = 0xFFD2AAB4,
    )
    val GRAPHITE = darkPalette(
        "graphite", "Graphite", "Charcoal with warm gold",
        background = 0xFF16181C, rail = 0xFF1B1E23, panel = 0xFF22262C, row = 0xFF292E35, rowFocused = 0xFF3D444F,
        surfaceVariant = 0xFF2F353D, accent = 0xFFE5C07B, text = 0xFFECEEF1, textDim = 0xFFA9AFB8,
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

    /** The Simple layout's own light colours: pale blue, white cards, strong blue buttons. */
    val SIMPLE_LIGHT = Palette(
        id = "simple-light",
        name = "Light",
        description = "Pale blue with white cards",
        dark = false,
        background = Color(0xFFDEEDFA),
        backgroundEnd = Color(0xFFDEEDFA),
        rail = Color(0xFFDEEDFA),
        railEnd = Color(0xFFDEEDFA),
        onRail = Color(0xFF16324E),
        railSelected = Color(0xFFFAFDFF),
        onRailSelected = Color(0xFF0567C5),
        panel = Color(0xFFFAFDFF),
        row = Color(0xFFFAFDFF),
        rowFocused = Color(0xFFDCEAF6),
        surfaceVariant = Color(0xFFE8F1F9),
        text = Color(0xFF16324E),
        textDim = Color(0xFF506A83),
        onText = Color.White,
        accent = Color(0xFF0567C5),
        onAccent = Color.White,
        focus = Color(0xFF132F4E),
        atmos = Color(0xFF2F5BD3),
        hiRes = Color(0xFF9A6400),
        lossless = Color(0xFF00796B),
        warning = Color(0xFFB45309),
        error = Color(0xFFC62828),
        favourite = Color(0xFF16A34A),
    )

    /** The Simple layout's dark colours ("Midnight"). */
    val SIMPLE_DARK = Palette(
        id = "simple-dark",
        name = "Midnight",
        description = "Deep navy with soft blue",
        dark = true,
        background = Color(0xFF112238),
        backgroundEnd = Color(0xFF112238),
        rail = Color(0xFF112238),
        railEnd = Color(0xFF112238),
        onRail = Color(0xFFEEF6FF),
        railSelected = Color(0xFF1B3047),
        onRailSelected = Color(0xFF96CAFF),
        panel = Color(0xFF1B3047),
        row = Color(0xFF1B3047),
        rowFocused = Color(0xFF2A4560),
        surfaceVariant = Color(0xFF284159),
        text = Color(0xFFEEF6FF),
        textDim = Color(0xFFADBED1),
        onText = Color(0xFF12283D),
        accent = Color(0xFFAED5FF),
        onAccent = Color(0xFF12283D),
        focus = Color.White,
        atmos = Color(0xFF8FB4FF),
        hiRes = Color(0xFFFFC857),
        lossless = Color(0xFF5ED3B5),
        warning = Color(0xFFFFB86B),
        error = Color(0xFFFF6B6B),
        favourite = Color(0xFF3DDC97),
    )

    /** The Cinema layout's dark colours. */
    val CINEMA_DARK = Palette(
        id = "cinema-dark",
        name = "Cinema",
        description = "Deep green-black with lime accents",
        dark = true,
        background = Color(0xFF111918),
        backgroundEnd = Color(0xFF111918),
        rail = Color(0xFF111918),
        railEnd = Color(0xFF111918),
        onRail = Color(0xFFF4F7EE),
        railSelected = Color(0xFF202B27),
        onRailSelected = Color(0xFFD1EE92),
        panel = Color(0xFF111918),
        row = Color(0xFF202B27),
        rowFocused = Color(0xFF2B3931),
        surfaceVariant = Color(0xFF2B3931),
        text = Color(0xFFF4F7EE),
        textDim = Color(0xFFB2C1B6),
        onText = Color(0xFF172715),
        accent = Color(0xFFD1EE92),
        onAccent = Color(0xFF172715),
        focus = Color.White,
        atmos = Color(0xFF8FC7FF),
        hiRes = Color(0xFFFFC857),
        lossless = Color(0xFF5ED3B5),
        warning = Color(0xFFFFB86B),
        error = Color(0xFFFF6B6B),
        favourite = Color(0xFFD1EE92),
    )

    /** The Cinema layout's light colours. */
    val CINEMA_LIGHT = Palette(
        id = "cinema-light",
        name = "Daylight",
        description = "Warm paper with deep green",
        dark = false,
        background = Color(0xFFF3F0E8),
        backgroundEnd = Color(0xFFF3F0E8),
        rail = Color(0xFFF3F0E8),
        railEnd = Color(0xFFF3F0E8),
        onRail = Color(0xFF1B332C),
        railSelected = Color(0xFFE7E7DB),
        onRailSelected = Color(0xFF365739),
        panel = Color(0xFFF3F0E8),
        row = Color(0xFFFFFDF8),
        rowFocused = Color(0xFFDDE5CB),
        surfaceVariant = Color(0xFFE7E7DB),
        text = Color(0xFF1B332C),
        textDim = Color(0xFF52655B),
        onText = Color(0xFFFFFDF6),
        accent = Color(0xFF365739),
        onAccent = Color(0xFFFFFDF6),
        focus = Color(0xFF1B332C),
        atmos = Color(0xFF2F5BD3),
        hiRes = Color(0xFF9A6400),
        lossless = Color(0xFF00796B),
        warning = Color(0xFFB45309),
        error = Color(0xFFC62828),
        favourite = Color(0xFF365739),
    )

    /** The colour themes of the Panel layout. */
    val all = listOf(SKY, TEAL, OCEAN, VIOLET, PLUM, INDIGO, FOREST, SLATE, ESPRESSO, BURGUNDY, GRAPHITE, AMBER, CRIMSON, BLACK, LIGHT)

    /** The colour sets of the Simple layout. */
    private val flatDarks = listOf(
        FlatSpec("plum", "SoundHub plum", "Deep plum with coral", 0xFF24082F, 0xFF341248, 0xFF4B2263, 0xFF3F1A58, 0xFFFF6A57, 0xFFFDF4E6, 0xFFCBB5D6),
        FlatSpec("indigo", "Indigo", "Blue-violet with gold", 0xFF14123A, 0xFF221F5A, 0xFF37338A, 0xFF2D2A78, 0xFFFFC857, 0xFFF1F0FF, 0xFFB8B6E6),
        FlatSpec("forest", "Forest", "Deep green with mint", 0xFF0B1A14, 0xFF133026, 0xFF225040, 0xFF1B4235, 0xFF7DE3A1, 0xFFEAF6EE, 0xFFA9C7B5),
        FlatSpec("slate", "Slate", "Blue-grey with sky blue", 0xFF1B2330, 0xFF283446, 0xFF41537A, 0xFF34425C, 0xFF8AB4FF, 0xFFEDF2FA, 0xFFAEBBCF),
        FlatSpec("espresso", "Espresso", "Coffee brown with orange", 0xFF1E1410, 0xFF33241C, 0xFF4F3A2D, 0xFF42302A, 0xFFFF9E64, 0xFFF6EBE0, 0xFFC7B3A3),
        FlatSpec("burgundy", "Burgundy", "Wine red with rose", 0xFF2A0D16, 0xFF42172A, 0xFF65283E, 0xFF51202F, 0xFFFF8FA3, 0xFFFCEDF0, 0xFFD2AAB4),
        FlatSpec("graphite", "Graphite", "Charcoal with gold", 0xFF16181C, 0xFF22262C, 0xFF3A414B, 0xFF2D323A, 0xFFE5C07B, 0xFFECEEF1, 0xFFA9AFB8),
    )

    /** The Simple layout's colour sets: its own light and Midnight, then the dark ones. */
    val simple = listOf(SIMPLE_LIGHT, SIMPLE_DARK) + flatDarks.map { flat("simple-", it) }

    /** The colour sets of the Cinema layout. */
    val cinema = listOf(CINEMA_DARK, CINEMA_LIGHT) + flatDarks.map { flat("cinema-", it) }

    fun forLayout(layout: UiLayout): List<Palette> = when (layout) {
        UiLayout.SIMPLE -> simple
        UiLayout.CINEMA -> cinema
        UiLayout.PANEL -> all
    }

    fun byId(id: String): Palette = (all + simple + cinema).firstOrNull { it.id == id } ?: SKY
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
    // The Simple layout is drawn a little smaller (text, art and spacing together) so more fits.
    val density = LocalDensity.current
    val scaled = if (currentLayout != UiLayout.PANEL) Density(density.density * SIMPLE_SCALE, density.fontScale) else density
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = palette.colorScheme(), typography = typography, content = content)
    }
}

private const val SIMPLE_SCALE = 0.85f
