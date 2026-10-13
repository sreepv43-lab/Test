package io.github.sreepv43.soundhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.ui.components.Chip
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup

/** What the Cinema layout's tab row offers for the page of [section], else nothing. */
internal fun cinemaTabs(section: Section): List<Section> = when (section) {
    Section.SEARCH, Section.ARTISTS, Section.SOUND -> listOf(Section.SEARCH, Section.ARTISTS, Section.SOUND)
    Section.LIBRARY, Section.TRANSFERS -> listOf(Section.LIBRARY, Section.TRANSFERS)
    else -> emptyList()
}

private fun tabLabel(section: Section) = when (section) {
    Section.SEARCH -> "Search"
    Section.ARTISTS -> "Artists"
    Section.SOUND -> "Atmos"
    Section.LIBRARY -> "Library"
    Section.TRANSFERS -> "Downloads"
    else -> section.label
}

/**
 * Cinema layout: Discover holds Search, Artists and Atmos; My Music holds the Library and Downloads.
 * They are chips above the page, so every old page keeps its place without a menu entry of its own.
 */
@Composable
fun CinemaTabs(section: Section, onSelect: (Section) -> Unit, content: @Composable () -> Unit) {
    val tabs = cinemaTabs(section)
    if (currentLayout != UiLayout.CINEMA || tabs.isEmpty()) {
        content()
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().tvButtonGroup().padding(PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tabs.forEach { tab -> Chip(tabLabel(tab), selected = tab == section, key = "tab-${tab.name}") { onSelect(tab) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
