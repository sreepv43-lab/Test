package io.github.sreepv43.soundhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sreepv43.soundhub.library.Release

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    ARTISTS("Artists", Icons.Default.Star),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    TRANSFERS("Transfers", Icons.Default.Download),
    SOUND("Atmos & sound", Icons.Default.SurroundSound),
    NOW_PLAYING("Now playing", Icons.Default.GraphicEq),
    SETTINGS("Settings", Icons.Default.Settings),
}

enum class SettingsKind(val label: String) { ACCOUNT("Account"), STORAGE("Storage"), NETWORK("Network (advanced)"), ABOUT("About") }

/** What is on screen. [section] is the one highlighted in the side menu. */
sealed interface Page {
    val key: String
    val section: Section
}

data class SectionPage(override val section: Section) : Page {
    override val key: String get() = "section:${section.name}"
}

/** An album found by a search (one or more users' folders), from Search or the Sound page. */
data class ReleasePage(val release: Release, override val section: Section) : Page {
    override val key: String get() = "release:${section.name}:${release.key}"
}

/** An album in the library. */
data class AlbumPage(val albumKey: String) : Page {
    override val section: Section get() = Section.LIBRARY
    override val key: String get() = "album:$albumKey"
}

data class ArtistPage(val name: String) : Page {
    override val section: Section get() = Section.LIBRARY
    override val key: String get() = "artist:$name"
}

data class PlaylistPage(val id: String) : Page {
    override val section: Section get() = Section.LIBRARY
    override val key: String get() = "playlist:$id"
}

data object QueuePage : Page {
    override val section: Section get() = Section.NOW_PLAYING
    override val key: String get() = "queue"
}

data class SettingsPage(val kind: SettingsKind) : Page {
    override val section: Section get() = Section.SETTINGS
    override val key: String get() = "settings:${kind.name}"
}

/**
 * The pages the user went through, so Back goes to the previous one. [home] is always at the bottom;
 * Back on it leaves the app. Choosing a section in the menu goes back to it if it is already open
 * underneath, otherwise opens it on top of the current page (so Back returns there).
 */
@Stable
class Navigator(start: Section, private val home: Section = Section.HOME) {
    private val stack = mutableStateListOf<Page>(SectionPage(home))
    internal var onDrop: (Page) -> Unit = {}

    init {
        if (start != home) stack.add(SectionPage(start))
    }

    val current: Page get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    val pages: List<Page> get() = stack.toList()

    fun open(page: Page) {
        val existing = stack.indexOf(page)
        if (existing >= 0) popTo(existing) else stack.add(page)
    }

    /**
     * Shows [section] on top, so Back returns to the current page (e.g. Artists → the search it
     * started). An older copy further down moves up, keeping its state; the pages above it stay.
     */
    fun bringToTop(section: Section) {
        val page = SectionPage(section)
        if (current == page) return
        stack.remove(page)
        stack.add(page)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        onDrop(stack.removeAt(stack.lastIndex))
        return true
    }

    fun select(section: Section) {
        val existing = stack.indexOf(SectionPage(section))
        if (existing >= 0) popTo(existing) else stack.add(SectionPage(section))
    }

    private fun popTo(index: Int) {
        while (stack.lastIndex > index) onDrop(stack.removeAt(stack.lastIndex))
    }
}

private val menu = Section.entries.map { MenuEntry(it.name, it.label, it.icon) }

/**
 * The TV shell around the page on top of [navigator]'s stack: side menu, Back, the player bar, and
 * each page keeping its scroll position, filters and open album while other pages are on top of it.
 */
@Composable
fun SoundHubShell(navigator: Navigator, bottomBar: @Composable () -> Unit = {}, content: @Composable (Page) -> Unit) {
    val states = rememberSaveableStateHolder()
    DisposableEffect(navigator, states) {
        navigator.onDrop = { states.removeState(it.key) }
        onDispose { navigator.onDrop = {} }
    }
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }
    val page = navigator.current
    TvShell(
        entries = menu,
        selectedKey = page.section.name,
        pageKey = page.key,
        onSelect = { navigator.select(Section.valueOf(it.key)) },
        bottomBar = bottomBar,
    ) { modifier ->
        Box(modifier) {
            key(page.key) {
                states.SaveableStateProvider(page.key) {
                    TvPage(page.key) { content(page) }
                }
            }
        }
    }
}
