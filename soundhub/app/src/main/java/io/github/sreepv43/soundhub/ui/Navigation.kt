package io.github.sreepv43.soundhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sreepv43.soundhub.library.SearchFolder

enum class Section(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Default.Search),
    ATMOS("Dolby Atmos", Icons.Default.SurroundSound),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    DOWNLOADS("Downloads", Icons.Default.Download),
    NOW_PLAYING("Now playing", Icons.Default.GraphicEq),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** What is on screen. [section] is the one highlighted in the side menu. */
sealed interface Page {
    val key: String
    val section: Section
}

data class SectionPage(override val section: Section) : Page {
    override val key: String get() = "section:${section.name}"
}

/** An album (a user's folder) from search results. */
data class FolderPage(val folder: SearchFolder, override val section: Section) : Page {
    override val key: String get() = "folder:${section.name}:${folder.key}"
}

/** An album in the library. */
data class AlbumPage(val albumKey: String) : Page {
    override val section: Section get() = Section.LIBRARY
    override val key: String get() = "album:$albumKey"
}

/**
 * The pages the user went through, so Back goes to the previous one. Search is always at the
 * bottom (home); Back on it leaves the app. Choosing a section in the menu goes back to it if it is
 * already open underneath, otherwise opens it on top of Search.
 */
@Stable
class Navigator(start: Section) {
    private val stack = mutableStateListOf<Page>(SectionPage(Section.SEARCH))
    internal var onDrop: (Page) -> Unit = {}

    init {
        if (start != Section.SEARCH) stack.add(SectionPage(start))
    }

    val current: Page get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    val pages: List<Page> get() = stack.toList()

    fun open(page: Page) {
        val existing = stack.indexOf(page)
        if (existing >= 0) popTo(existing) else stack.add(page)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        onDrop(stack.removeAt(stack.lastIndex))
        return true
    }

    fun select(section: Section) {
        val existing = stack.indexOf(SectionPage(section))
        if (existing >= 0) {
            popTo(existing)
            return
        }
        popTo(0)
        stack.add(SectionPage(section))
    }

    private fun popTo(index: Int) {
        while (stack.lastIndex > index) onDrop(stack.removeAt(stack.lastIndex))
    }
}

private val menu = Section.entries.map { MenuEntry(it.name, it.label, it.icon) }

/**
 * The TV shell around the page on top of [navigator]'s stack: side menu, Back, and each page
 * keeping its scroll position, filters and open album while other pages are on top of it.
 */
@Composable
fun SoundHubShell(navigator: Navigator, content: @Composable (Page) -> Unit) {
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
