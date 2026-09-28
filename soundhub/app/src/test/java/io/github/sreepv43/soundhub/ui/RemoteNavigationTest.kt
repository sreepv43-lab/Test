package io.github.sreepv43.soundhub.ui

import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.library.FamousArtists
import io.github.sreepv43.soundhub.library.PathNames
import io.github.sreepv43.soundhub.library.Releases
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchResults
import io.github.sreepv43.soundhub.library.SearchTrack
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.slsk.SearchResponse
import io.github.sreepv43.soundhub.slsk.SharedFile
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.SeekBar
import io.github.sreepv43.soundhub.ui.screens.AccountLayout
import io.github.sreepv43.soundhub.ui.screens.ArtistSearch
import io.github.sreepv43.soundhub.ui.screens.ArtistsLayout
import io.github.sreepv43.soundhub.ui.screens.PlayerControls
import io.github.sreepv43.soundhub.ui.screens.ReleaseLayout
import io.github.sreepv43.soundhub.ui.screens.SearchLayout
import io.github.sreepv43.soundhub.ui.screens.SearchStatus
import io.github.sreepv43.soundhub.ui.screens.TransferAction
import io.github.sreepv43.soundhub.ui.screens.TransfersLayout
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
    private val seeks = mutableListOf<Long>()
    private val actions = mutableListOf<TransferAction>()
    private var signIns = 0
    private val picked = mutableListOf<String>()

    @OptIn(ExperimentalComposeUiApi::class)
    @Before
    fun setUp() {
        // Real Android TV devices report this feature (it changes Compose's default scrolling).
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        rule.setContent {
            val inputModes = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
            SoundHubTheme {
                FakeApp(
                    folders.value,
                    onNavigator = { navigator = it },
                    onSeek = { seeks += it },
                    onTransferAction = { actions += it },
                    onSignIn = { signIns++ },
                    onArtist = { picked += it },
                )
            }
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
        assertEquals("recent searches, then the filter chips, then the first album: $seen", "rock", seen[0])
        assertTrue(seen.toString(), seen[1].startsWith("All"))
        assertEquals(seen.toString(), "release-Album 0", seen[2])
        assertEquals(seen.toString(), "release-Album 1", seen[3])
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
        moveTo("menu-TRANSFERS", KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        assertEquals(SectionPage(Section.TRANSFERS), navigator.current)
        assertFalse(menuExpanded())
        assertEquals("the page gets the selection, not the menu", "transfer-12", focused())
    }

    @Test
    fun rightAndBackCloseTheMenuWithoutMoving() {
        downTo("release-Album 2")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuExpanded())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(menuExpanded())
        assertEquals("release-Album 2", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(menuExpanded())
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("release-Album 2", focused())
    }

    @Test
    fun openingAnAlbumStartsOnPlayAndBackReturnsToTheSameAlbum() {
        downTo("release-Album 5")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(navigator.current is ReleasePage)
        assertEquals("Play", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().startsWith("track-"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Right on a song reaches its More button: ${focused()}", focused().startsWith("more-"))
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("release-Album 5", focused())
    }

    @Test
    fun playingOpensNowPlayingOnPlayPauseAndBackReturnsToTheAlbumThenHome() {
        downTo("release-Album 1")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val track = focused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.NOW_PLAYING), navigator.current)
        assertEquals("play-pause", focused())
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(navigator.current is ReleasePage)
        assertEquals(track, focused())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("release-Album 1", focused())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.HOME), navigator.current)
        assertFalse("Home is at the bottom: Back there leaves the app", navigator.canGoBack)
    }

    @Test
    fun anotherSectionFromTheMenuComesBackToTheSameAlbumWithBack() {
        downTo("release-Album 3")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        moveTo("menu-LIBRARY", KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.LIBRARY), navigator.current)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("release-Album 3", focused())
    }

    @Test
    fun newResultsDoNotMoveTheSelection() {
        downTo("release-Album 4")
        rule.runOnUiThread { folders.value = (0 until 12).map(::folder) + (12 until 30).map(::folder) }
        settle(1_000)
        assertEquals("release-Album 4", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("release-Album 5", focused())
    }

    @Test
    fun everyTransferCanBeReachedIncludingCompletedOnes() {
        openSection(Section.TRANSFERS)
        val seen = mutableSetOf(focused())
        repeat(24) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            seen += focused()
        }
        val rows = (1L..12L).map { "transfer-$it" }
        assertTrue("unreachable: ${rows - seen}", seen.containsAll(rows))
        assertTrue(seen.toString(), "Clear completed" in seen)
    }

    // Robolectric doesn't give dialog windows focus, so these check that the first (and selected)
    // element of each dialog is the safe one; on a device the dialog opens on it.
    @Test
    fun transferOptionsStartOnTheSafeChoice() {
        openSection(Section.TRANSFERS)
        downTo("transfer-8")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("option-Try again", "option-Find another source", "option-Remove…", "option-Cancel"), optionsInOrder())
        rule.onNodeWithTag("option-Remove…").performClick()
        settle()
        // Removing a part-downloaded song asks first, with Keep before Remove.
        rule.onNodeWithText("Remove this download?").assertExists()
        assertTrue(left("Keep") < left("Remove"))
        rule.onNodeWithTag("Keep").performClick()
        settle()
        assertTrue(actions.toString(), actions.isEmpty())

        upTo("transfer-12")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Stopping is the only action for a waiting song: Cancel comes first.
        assertEquals(listOf("option-Cancel", "option-Stop download"), optionsInOrder())
        rule.onNodeWithTag("option-Cancel").performClick()
        settle()
        assertTrue(actions.toString(), actions.isEmpty())
    }

    @Test
    fun scrollingThroughManyArrivingResultsAndTryingEveryFilterKeepsWorking() {
        rule.runOnUiThread { folders.value = manyResults(60) }
        settle(500)
        // Scroll down while answers keep arriving, as during a real search.
        repeat(50) { i ->
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            if (i % 5 == 4) rule.runOnUiThread { folders.value = manyResults(60 + (i + 1) * 6) }
        }
        assertTrue(focused(), focused().startsWith("release-"))
        // Holding Down sends repeated presses.
        hold(KeyEvent.KEYCODE_DPAD_DOWN, 30)
        assertTrue(focused(), focused().startsWith("release-") || focused() == "player-bar")
        moveUntil(KeyEvent.KEYCODE_DPAD_UP) { it.startsWith("All ") }
        for (label in listOf("Lossless", "Hi-Res", "Dolby Atmos", "MP3")) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(focused(), focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            settle()
            assertTrue("still on the chip after choosing it: ${focused()}", focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            press(KeyEvent.KEYCODE_DPAD_UP)
        }
        rule.runOnUiThread { folders.value = manyResults(200) }
        moveUntil(KeyEvent.KEYCODE_DPAD_LEFT) { it.startsWith("All ") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(20) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(focused(), focused().startsWith("release-"))
    }

    @Test
    fun anArtistStartsASearchAndBackReturnsToTheArtist() {
        openSection(Section.ARTISTS)
        // Starts on the genres: one press down reaches the artists, one up what to search for.
        assertEquals(FamousArtists.genres.first().name, focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("All music", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(FamousArtists.genres.first().name, focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("artist-${FamousArtists.genres.first().artists.first().name}", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val artist = focused()
        assertTrue(artist, artist.startsWith("artist-"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(artist.removePrefix("artist-")), picked)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.ARTISTS), navigator.current)
        assertEquals(artist, focused())
    }

    @Test
    fun signInCanBeReachedFromThePasswordAndPressed() {
        openSection(Section.SETTINGS)
        assertEquals("the page starts on Sign in", "Sign in", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("password", focused())
        // A small button under a full-width row used to be skipped for the wide text below it.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Sign in", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().startsWith("New to Soulseek"))
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Sign in", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, signIns)
    }

    @Test
    fun upFromTheSongsReturnsToTheAlbumsFirstButton() {
        downTo("release-Album 5")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Shuffle", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().startsWith("track-"))
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Play", focused())
    }

    @Test
    fun chipsKeepTheirPlaceAndLeftFromTheFirstOpensTheMenu() {
        downTo("All  12")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Lossless  12", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuExpanded())
    }

    @Test
    fun theFilterPanelAppliesEachChoiceAndOffersAWayBack() {
        downTo("All  12")
        repeat(MusicFilter.SHORTCUTS.size) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("More filters…", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("Any quality").assertExists()
        rule.onNodeWithText("MP3").performClick()
        rule.onNodeWithTag("Done").performClick()
        settle()
        rule.onNodeWithText("No albums here match MP3.").assertExists()
        rule.onNodeWithTag("Show all results").performClick()
        settle()
        rule.onNodeWithTag("release-Album 0").assertExists()
    }

    @Test
    fun theSeekBarUsesLeftAndRightWhileUpAndDownLeaveIt() {
        rule.runOnUiThread { navigator.open(SectionPage(Section.NOW_PLAYING)) }
        settle(1_000)
        assertEquals("play-pause", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("seek-bar", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("seek-bar", focused())
        assertFalse("Left seeks instead of opening the menu", menuExpanded())
        assertEquals(listOf(10_000L, -10_000L, -10_000L), seeks)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("play-pause", focused())
    }

    @Test
    fun thePlayerBarIsBelowEveryPageAndUpGoesBack() {
        downTo("player-bar")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("bar-play", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(focused(), focused().startsWith("release-"))
        downTo("player-bar")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.NOW_PLAYING), navigator.current)
        rule.onNodeWithTag("player-bar").assertDoesNotExist()
    }

    // ---- helpers ----

    private fun openSection(section: Section) {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        val step = if (section.ordinal > Section.SEARCH.ordinal) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        moveTo("menu-${section.name}", step)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        assertEquals(SectionPage(section), navigator.current)
    }

    /** The rows of the open options dialog, top to bottom. */
    private fun optionsInOrder(): List<String> =
        rule.onAllNodes(SemanticsMatcher("option row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("option-") == true })
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.TestTag] }

    private fun left(tag: String): Float = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    private fun downTo(target: String) = moveTo(target, KeyEvent.KEYCODE_DPAD_DOWN)

    private fun upTo(target: String) = moveTo(target, KeyEvent.KEYCODE_DPAD_UP)

    private fun moveUntil(keyCode: Int, found: (String) -> Boolean) {
        repeat(200) {
            if (found(focused())) return
            press(keyCode)
        }
        assertTrue(focused(), found(focused()))
    }

    /** Holds a key down: the remote sends the press again and again until it is let go. */
    private fun hold(keyCode: Int, repeats: Int) {
        val down = SystemClock.uptimeMillis()
        repeat(repeats) { count ->
            rule.runOnUiThread {
                rule.activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, count))
            }
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(50)
        }
        key(keyCode, KeyEvent.ACTION_UP)
        settle()
    }

    private fun moveTo(target: String, keyCode: Int) {
        repeat(40) {
            if (focused() == target) return
            press(keyCode)
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

/** Answers from [count] users in a mix of formats, three users per album (so albums have several sources). */
private fun manyResults(count: Int): List<SearchFolder> = SearchResults.group(
    (0 until count).map { i ->
        val user = "peer$i"
        val (extension, attributes) = when (i % 5) {
            0 -> "flac" to mapOf(1 to 240, 4 to 96_000, 5 to 24)
            1 -> "mp3" to mapOf(0 to 320, 1 to 240)
            2 -> "flac" to mapOf(1 to 240, 4 to 44_100, 5 to 16)
            3 -> "m4a" to mapOf(0 to 768, 1 to 240)
            else -> "mp3" to mapOf(0 to 192, 1 to 240)
        }
        val album = if (i % 5 == 3) "Record ${i / 3} [Dolby Atmos]" else "Record ${i / 3}"
        val directory = "@@$user\\Music\\Band ${i / 9} - $album"
        SearchResponse(
            user,
            1,
            (1..(3 + i % 4)).map { n -> SharedFile("$directory\\0$n - Track $n.$extension", 10_000_000L * (n + 1), "", attributes) },
            slotFree = i % 3 != 0,
            avgSpeed = 50_000 * (i % 7 + 1),
            queueLength = (i % 4).toLong(),
        )
    },
)

private fun transfers(): List<TransferInfo> = (1L..12L).map { id ->
    val status = when {
        id <= 6 -> TransferStatus.COMPLETED
        id <= 8 -> TransferStatus.FAILED
        else -> TransferStatus.QUEUED
    }
    val bytes = when (status) {
        TransferStatus.COMPLETED -> 1_000_000L
        TransferStatus.FAILED -> 400_000L
        else -> 0L
    }
    TransferInfo(id, "user$id", "@@user$id\\Album\\0$id Song.flac", 1_000_000, bytes, status, 3, 0, null)
}

/** The real shell and page layouts, with made-up data instead of Soulseek. */
@Composable
private fun FakeApp(
    folders: List<SearchFolder>,
    onNavigator: (Navigator) -> Unit,
    onSeek: (Long) -> Unit,
    onTransferAction: (TransferAction) -> Unit,
    onSignIn: () -> Unit,
    onArtist: (String) -> Unit,
) {
    val navigator = remember { Navigator(Section.SEARCH) }
    SideEffect { onNavigator(navigator) }
    val releases = remember(folders) { Releases.group(folders) }
    SoundHubShell(
        navigator,
        bottomBar = {
            if (navigator.current != SectionPage(Section.NOW_PLAYING)) {
                PlayerBarLayout(
                    title = "Song 1",
                    subtitle = "Artist 0 · Album 0",
                    playing = true,
                    progress = 0.3f,
                    onOpen = { navigator.open(SectionPage(Section.NOW_PLAYING)) },
                    onPlayPause = {},
                    onNext = {},
                    onQueue = {},
                ) {}
            }
        },
    ) { page ->
        when (page) {
            is SectionPage -> when (page.section) {
                Section.SEARCH -> {
                    var filter by remember { mutableStateOf(MusicFilter()) }
                    SearchLayout(
                        title = "Search",
                        hint = "Artist, album or song",
                        query = "rock",
                        status = SearchStatus("${releases.size} albums"),
                        releases = releases,
                        filter = filter,
                        onFilter = { filter = it },
                        recent = listOf("rock", "jazz"),
                        onSearch = {},
                        onOpen = { navigator.open(ReleasePage(it, Section.SEARCH)) },
                    )
                }
                Section.TRANSFERS -> TransfersLayout(
                    transfers(),
                    onAction = { _, action -> onTransferAction(action) },
                    onClearCompleted = {},
                    onSearch = {},
                )
                Section.NOW_PLAYING -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeekBar(positionMs = 60_000, durationMs = 200_000, downloaded = 0.5f, seekable = true, onSeekBy = onSeek, onClick = {})
                    PlayerControls(
                        playing = true,
                        shuffle = false,
                        repeatMode = 0,
                        onShuffle = {},
                        onPrevious = {},
                        onPlayPause = {},
                        onNext = {},
                        onRepeat = {},
                    )
                }
                Section.ARTISTS -> {
                    var genre by remember { mutableStateOf(0) }
                    var mode by remember { mutableStateOf(ArtistSearch.ALL) }
                    ArtistsLayout(FamousArtists.genres, genre, { genre = it }, mode, { mode = it }) { artist ->
                        onArtist(artist.name)
                        navigator.bringToTop(Section.SEARCH)
                    }
                }
                Section.SETTINGS -> AccountLayout(
                    state = ConnectionState.Disconnected,
                    username = "listener",
                    passwordSet = true,
                    changed = true,
                    signInButton = Modifier,
                    onEditUsername = {},
                    onEditPassword = {},
                    onSignIn = onSignIn,
                    onSignOut = {},
                )
                else -> Column(Modifier.padding(24.dp)) {
                    Text(page.section.label)
                    ActionButton("${page.section.label} button") {}
                }
            }
            is ReleasePage -> ReleaseLayout(
                release = page.release,
                source = page.release.sources.first(),
                filter = MusicFilter(),
                status = { null },
                onPlay = { _, _ -> navigator.open(SectionPage(Section.NOW_PLAYING)) },
                onEnqueue = { _, _ -> },
                onDownload = {},
                onChooseSource = {},
            )
            else -> Text("other page")
        }
    }
}
