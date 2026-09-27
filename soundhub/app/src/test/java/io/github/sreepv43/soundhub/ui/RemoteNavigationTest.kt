package io.github.sreepv43.soundhub.ui

import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.library.PathNames
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchTrack
import io.github.sreepv43.soundhub.slsk.SharedFile
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.screens.DownloadsLayout
import io.github.sreepv43.soundhub.ui.screens.FolderLayout
import io.github.sreepv43.soundhub.ui.screens.SearchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives SoundHub's shell and real page layouts (with made-up search results and downloads) with
 * remote key presses sent through the Activity, the path a TV remote takes, and checks where the
 * selection lands after each press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television-mdpi")
class RemoteNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navigator: Navigator
    private val folders = mutableStateOf((0 until 12).map(::folder))

    @OptIn(ExperimentalComposeUiApi::class)
    @Before
    fun setUp() {
        // Real Android TV devices report this feature (it changes Compose's default scrolling).
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        rule.setContent {
            val inputModes = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
            SoundHubTheme { FakeApp(folders.value, onNavigator = { navigator = it }) }
        }
        rule.runOnUiThread { composeView().requestFocus() }
        settle(1_000)
    }

    @Test
    fun startsOnTheSearchBoxWithTheMenuClosed() {
        assertEquals("search-bar", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun downGoesThroughRecentSearchesAndChipsToTheAlbums() {
        val seen = mutableListOf<String>()
        repeat(4) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            seen += focused()
        }
        assertEquals("recent searches, then the format chips, then the first album: $seen", "rock", seen[0])
        assertTrue(seen.toString(), seen[1].startsWith("All"))
        assertEquals(seen.toString(), "folder-Album 0", seen[2])
        assertEquals(seen.toString(), "folder-Album 1", seen[3])
    }

    @Test
    fun okOnTheSearchBoxOpensTheFieldAndArrowsLeaveIt() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("search-field", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("rock", focused())
        rule.onNodeWithTag("search-bar").assertExists()
    }

    @Test
    fun leftOpensTheMenuOnTheCurrentSectionAndOkOpensAnother() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-SEARCH", focused())
        assertTrue(menuExpanded())
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        assertEquals("menu-DOWNLOADS", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        assertEquals(SectionPage(Section.DOWNLOADS), navigator.current)
        assertFalse(menuExpanded())
        assertEquals("the page gets the selection, not the menu", "Clear finished", focused())
    }

    @Test
    fun rightAndBackCloseTheMenuWithoutMoving() {
        downTo("folder-Album 2")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuExpanded())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(menuExpanded())
        assertEquals("folder-Album 2", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(menuExpanded())
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("folder-Album 2", focused())
    }

    @Test
    fun openingAnAlbumStartsOnPlayAndBackReturnsToTheSameAlbum() {
        downTo("folder-Album 5")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(navigator.current is FolderPage)
        assertEquals("Play album", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().startsWith("track-"))
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("folder-Album 5", focused())
    }

    @Test
    fun playingOpensNowPlayingOnPlayPauseAndBackReturnsToTheAlbum() {
        downTo("folder-Album 1")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val track = focused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.NOW_PLAYING), navigator.current)
        assertEquals("Play/Pause", focused())
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(navigator.current is FolderPage)
        assertEquals(track, focused())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("folder-Album 1", focused())
        assertFalse("Search is home: Back there leaves the app", navigator.canGoBack)
    }

    @Test
    fun anotherSectionFromTheMenuComesBackToTheSameAlbumWithBack() {
        downTo("folder-Album 3")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        assertEquals("menu-LIBRARY", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.LIBRARY), navigator.current)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("folder-Album 3", focused())
    }

    @Test
    fun newResultsDoNotMoveTheSelection() {
        downTo("folder-Album 4")
        rule.runOnUiThread { folders.value = (0 until 12).map(::folder) + (12 until 30).map(::folder) }
        settle(1_000)
        assertEquals("folder-Album 4", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("folder-Album 5", focused())
    }

    @Test
    fun everyDownloadCanBeReachedIncludingFinishedOnes() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        val seen = mutableSetOf(focused())
        repeat(20) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            seen += focused()
        }
        val rows = (1L..12L).map { "transfer-$it" }
        assertTrue("unreachable: ${rows - seen}", seen.containsAll(rows))
    }

    @Test
    fun chipsKeepTheirPlaceAndLeftFromTheFirstOpensTheMenu() {
        downTo("All  12")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("MP3  0", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuExpanded())
    }

    // ---- helpers ----

    private fun downTo(target: String) {
        repeat(40) {
            if (focused() == target) return
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertEquals(target, focused())
    }

    private fun menuExpanded(): Boolean = rule.onNodeWithTag("menu").getBoundsInRoot().let { it.right - it.left } > 150.dp

    private fun focused(): String {
        val nodes = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        return nodes.joinToString { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                ?: "?"
        }.ifEmpty { "<nothing>" }
    }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) {
        key(keyCode, KeyEvent.ACTION_DOWN)
        key(keyCode, KeyEvent.ACTION_UP)
        settle()
    }

    private fun key(keyCode: Int, action: Int) {
        rule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0))
        }
        rule.waitForIdle()
    }

    private fun settle(millis: Long = 400) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? = when {
            view.javaClass.name.endsWith("AndroidComposeView") -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find(rule.activity.window.decorView))
    }
}

private fun folder(i: Int): SearchFolder {
    val directory = "@@user$i\\Music\\Artist $i - Album $i"
    val tracks = (1..3).map { n ->
        val path = "$directory\\0$n - Song $n.flac"
        SearchTrack(
            "user$i",
            SharedFile(path, 30_000_000, "", mapOf(1 to 200, 4 to 44_100, 5 to 16)),
            AudioFormats.classify(path, 30_000_000, durationSec = 200, sampleRate = 44_100, bitDepth = 16),
            PathNames.describe(path),
        )
    }
    return SearchFolder("user$i", directory, tracks, slotFree = true, avgSpeed = 1_000_000, queueLength = 0)
}

private fun transfers(): List<TransferInfo> = (1L..12L).map { id ->
    val status = when {
        id <= 6 -> TransferStatus.COMPLETED
        id <= 8 -> TransferStatus.FAILED
        else -> TransferStatus.QUEUED
    }
    TransferInfo(id, "user$id", "@@user$id\\Album\\0$id Song.flac", 1_000_000, if (status == TransferStatus.COMPLETED) 1_000_000 else 0, status, 3, 0, null)
}

/** The real shell and page layouts, with made-up data instead of Soulseek. */
@Composable
private fun FakeApp(folders: List<SearchFolder>, onNavigator: (Navigator) -> Unit) {
    val navigator = remember { Navigator(Section.SEARCH) }
    SideEffect { onNavigator(navigator) }
    SoundHubShell(navigator) { page ->
        when (page) {
            is SectionPage -> when (page.section) {
                Section.SEARCH -> {
                    var filter by rememberSaveable { mutableStateOf(FormatFilter.ALL) }
                    SearchLayout(
                        title = "Search",
                        hint = "Artist, album or song",
                        query = "rock",
                        status = "${folders.size} albums",
                        folders = folders,
                        filter = filter,
                        onFilter = { filter = it },
                        recent = listOf("rock", "jazz"),
                        onSearch = {},
                        onOpen = { navigator.open(FolderPage(it, Section.SEARCH)) },
                    )
                }
                Section.DOWNLOADS -> DownloadsLayout(transfers(), onAction = { _, _ -> }, onClearFinished = {})
                Section.NOW_PLAYING -> Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Previous") {}
                    ActionButton("Play/Pause", pageDefault = true) {}
                    ActionButton("Next") {}
                }
                else -> Column(Modifier.padding(24.dp)) {
                    Text(page.section.label)
                    ActionButton("${page.section.label} button") {}
                }
            }
            is FolderPage -> FolderLayout(
                folder = page.folder,
                status = { null },
                onPlay = { navigator.open(SectionPage(Section.NOW_PLAYING)) },
                onDownload = {},
            )
            is AlbumPage -> Text("album")
        }
    }
}
