package io.github.sreepv43.soundhub.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the whole app is laid out, chosen under Settings → Appearance. Each comes with its own colours
 * ([Palettes.forLayout]), so the colour theme only applies to [PANEL].
 */
enum class UiLayout(val id: String, val label: String, val description: String) {
    PANEL("panel", "Panel", "Icon menu, pages on cards, and Up Next with the player beside the page"),
    SIMPLE("simple", "Simple", "Labelled menu, a large Continue listening card and one player bar along the bottom"),
    CINEMA("cinema", "Cinema", "Listen, Discover and My Music along the top, Settings at the right and one player along the bottom");

    companion object {
        fun byId(id: String): UiLayout = entries.firstOrNull { it.id == id } ?: PANEL
    }
}

/** The layout in use (read by the shell, so changing it re-lays out every page at once). */
var currentLayout: UiLayout by mutableStateOf(UiLayout.PANEL)
