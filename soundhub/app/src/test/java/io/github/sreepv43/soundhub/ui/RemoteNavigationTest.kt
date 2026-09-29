package io.github.sreepv43.soundhub.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.Channels
import io.github.sreepv43.soundhub.audio.Quality
import io.github.sreepv43.soundhub.data.UpdateState
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.library.ArtistGenre
import io.github.sreepv43.soundhub.library.FamousArtist
import io.github.sreepv43.soundhub.library.FamousArtists
import io.github.sreepv43.soundhub.library.MyArtists
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
import io.github.sreepv43.soundhub.ui.components.AlbumArt
import io.github.sreepv43.soundhub.ui.components.SeekBar
import io.github.sreepv43.soundhub.ui.screens.AccountLayout
import io.github.sreepv43.soundhub.ui.screens.ArtistSearch
import io.github.sreepv43.soundhub.ui.screens.ArtistsLayout
import io.github.sreepv43.soundhub.ui.screens.PanelSong
import io.github.sreepv43.soundhub.ui.screens.PlayerControls
import io.github.sreepv43.soundhub.ui.screens.PlayerPanelLayout
import io.github.sreepv43.soundhub.ui.screens.ReleaseLayout
import io.github.sreepv43.soundhub.ui.screens.SearchLayout
import io.github.sreepv43.soundhub.ui.screens.SearchStatus
import io.github.sreepv43.soundhub.ui.screens.TransferAction
import io.github.sreepv43.soundhub.ui.screens.TransfersLayout
import io.github.sreepv43.soundhub.ui.screens.UpdatesLayout
import io.github.sreepv43.soundhub.update.AvailableUpdate
import io.github.sreepv43.soundhub.ui.screens.AppearanceLayout
import org.junit.After
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
import java.io.File

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
    private val installs = mutableListOf<Int>()
    private val panelSeeks = mutableListOf<Long>()
    private val jumps = mutableListOf<Int>()
    private val atmosSwitches = mutableListOf<Boolean>()
    private val playlistAdds = mutableListOf<Int>()
    private var artistAdds = 0
    private val mine = mutableStateOf<List<String>?>(null)

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
                    onInstall = { installs += it },
                    onPanelSeek = { panelSeeks += it },
                    onJump = { jumps += it },
                    onAtmos = { atmosSwitches += it },
                    mine = mine.value,
                    onAddArtist = { artistAdds++ },
                    onPlaylist = { playlistAdds += it },
                )
            }
        }
        rule.runOnUiThread { composeView().requestFocus() }
        settle(1_000)
    }

    @Test
    fun startsOnTheSearchBoxWithTheMenuClosed() {
        assertEquals("search-bar", focused())
        assertFalse(menuOpen())
    }

    @Test
    fun downGoesThroughTheAtmosSwitchRecentSearchesAndChipsToTheAlbums() {
        val seen = mutableListOf<String>()
        repeat(6) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            seen += focused()
        }
        assertEquals("the Atmos switch, recent searches, file types, quality, then the albums: $seen", "atmos-switch", seen[0])
        assertEquals(seen.toString(), "rock", seen[1])
        assertTrue(seen.toString(), seen[2].startsWith("All types"))
        assertTrue(seen.toString(), seen[3].startsWith("Any quality"))
        assertEquals(seen.toString(), "release-Album 0", seen[4])
        assertEquals(seen.toString(), "release-Album 1", seen[5])
    }

    @Test
    fun theAtmosSwitchTurnsOnWithOkAndShowsOnlyAtmosAlbums() {
        rule.runOnUiThread { folders.value = manyResults(30) }
        settle()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("atmos-switch", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true), atmosSwitches)
        assertEquals("atmos-switch", focused())
        rule.onNodeWithText("Searching with \"atmos\" added", substring = true).assertExists()
        val atmos = Releases.group(manyResults(30)).filter { it.matches(MusicFilter(channels = Channels.ATMOS)) }
        assertTrue("the test data has Atmos albums", atmos.isNotEmpty())
        moveUntil(KeyEvent.KEYCODE_DPAD_DOWN) { it.startsWith("release-") }
        assertEquals("release-${atmos.first().album}", focused())
        moveTo("atmos-switch", KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true, false), atmosSwitches)
    }

    @Test
    fun okOnTheSearchBoxOpensTheFieldAndArrowsLeaveIt() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("search-field", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("atmos-switch", focused())
        rule.onNodeWithTag("search-bar").assertExists()
    }

    @Test
    fun leftOpensTheMenuOnTheCurrentSectionAndOkOpensAnother() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-SEARCH", focused())
        assertTrue(menuOpen())
        moveTo("menu-TRANSFERS", KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        assertEquals(SectionPage(Section.TRANSFERS), navigator.current)
        assertFalse(menuOpen())
        assertEquals("the page gets the selection, not the menu", "transfer-12", focused())
    }

    @Test
    fun rightAndBackCloseTheMenuWithoutMoving() {
        downTo("release-Album 2")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuOpen())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(menuOpen())
        assertEquals("release-Album 2", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(menuOpen())
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
        assertEquals("release-Album 2", focused())
    }

    @Test
    fun openingAnAlbumStartsOnPlayAndBackReturnsToTheSameAlbum() {
        downTo("release-Album 5")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(navigator.current is ReleasePage)
        assertEquals("Play", focused())
        // Beside the player panel the album's buttons take two lines; Down passes them to the songs.
        moveUntil(KeyEvent.KEYCODE_DPAD_DOWN) { it.startsWith("track-") }
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
        moveUntil(KeyEvent.KEYCODE_DPAD_DOWN) { it.startsWith("track-") }
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
        assertTrue(focused(), focused().startsWith("release-"))
        moveUntil(KeyEvent.KEYCODE_DPAD_UP) { it.startsWith("Any quality") }
        for (label in listOf("Lossless", "Hi-Res", "Lossy")) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(focused(), focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            settle()
            assertTrue("still on the chip after choosing it: ${focused()}", focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            press(KeyEvent.KEYCODE_DPAD_UP)
        }
        // Up from the quality chips: the file types, entered at the one chosen.
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(focused(), focused().startsWith("All types"))
        for (label in listOf("MP3", "AAC / MP4", "FLAC")) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(focused(), focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            settle()
            assertTrue("still on the chip after choosing it: ${focused()}", focused().startsWith(label))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            press(KeyEvent.KEYCODE_DPAD_UP)
        }
        rule.runOnUiThread { folders.value = manyResults(200) }
        moveUntil(KeyEvent.KEYCODE_DPAD_LEFT) { it.startsWith("All types") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        moveUntil(KeyEvent.KEYCODE_DPAD_LEFT) { it.startsWith("Any quality") }
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
        val layout = clickables()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Down from what to search for reaches the genres. On screen: $layout", FamousArtists.genres.first().name, focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("artist-${FamousArtists.genres.first().artists.first().name}", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Up from the first artists reaches the genres", FamousArtists.genres.first().name, focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
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
    fun myArtistsComeFirstAndCanBeAddedFromAndSearched() {
        rule.runOnUiThread { mine.value = listOf("Alan Walker", "Nucleya") }
        openSection(Section.ARTISTS)
        assertEquals("starts on the first shipped genre", FamousArtists.genres.first().name, focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(MyArtists.GENRE, focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Add an artist", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, artistAdds)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("artist-Alan Walker", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("artist-Nucleya", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Up from any of the first artists reaches the Add button", "Add an artist", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(MyArtists.GENRE, focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("Alan Walker"), picked)
    }

    @Test
    fun anAlbumAndItsSongsCanBeAddedToAPlaylistFromSearch() {
        downTo("release-Album 5")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithTag("Add to playlist").performClick()
        assertEquals(listOf(3), playlistAdds)
        moveUntil(KeyEvent.KEYCODE_DPAD_DOWN) { it.startsWith("track-") }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(optionsInOrder().toString(), "option-Add to playlist…" in optionsInOrder())
        rule.onNodeWithTag("option-Add to playlist…").performClick()
        settle()
        assertEquals(listOf(3, 1), playlistAdds)
    }

    @Test
    fun choosingAThemeRecoloursAtOnceAndKeepsTheSelection() {
        rule.runOnUiThread { navigator.open(SettingsPage(SettingsKind.APPEARANCE)) }
        settle(1_000)
        assertEquals("the page starts on the theme in use", "theme-sky", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("teal", currentPalette.id)
        assertEquals("theme-teal", focused())
        moveTo("theme-light", KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("light", currentPalette.id)
        assertEquals("theme-light", focused())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.SEARCH), navigator.current)
    }

    @After
    fun backToTheDefaultTheme() {
        currentPalette = Palettes.SKY
    }

    @Test
    fun anAvailableUpdateIsOnePressAway() {
        rule.runOnUiThread { navigator.open(SettingsPage(SettingsKind.UPDATES)) }
        settle(1_000)
        assertEquals("Download and install build 70", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(70), installs)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Check again", focused())
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
        moveUntil(KeyEvent.KEYCODE_DPAD_DOWN) { it.startsWith("track-") }
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Play", focused())
    }

    @Test
    fun chipsKeepTheirPlaceAndLeftFromTheFirstOpensTheMenu() {
        downTo("All types  12")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("MP3  0", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuOpen())
    }

    @Test
    fun theFilterPanelAppliesEachChoiceAndOffersAWayBack() {
        downTo("Any quality  12")
        repeat(Quality.entries.size) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
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
        assertFalse("Left seeks instead of opening the menu", menuOpen())
        assertEquals(listOf(10_000L, -10_000L, -10_000L), seeks)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("play-pause", focused())
    }

    @Test
    fun backFromThePlayerPanelMovesTheSelectionToThePreviousPage() {
        downTo("release-Album 2")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("panel-play", focused())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(SectionPage(Section.HOME), navigator.current)
        assertEquals("the page shown takes the selection back from the panel", "Home button", focused())
    }

    @Test
    fun rightFromThePageEntersThePlayerPanelAndLeftComesBack() {
        downTo("release-Album 2")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the panel starts on Play/Pause", "panel-play", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("panel-shuffle", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left from the panel's edge returns to the same album", "release-Album 2", focused())
        assertFalse(menuOpen())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right returns to where the panel was left", "panel-shuffle", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("seek-bar", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("seek-bar", focused())
        assertEquals("the panel's seek bar uses Left/Right", listOf(10_000L, -10_000L), panelSeeks)
        moveTo("upnext-0", KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), jumps)
        assertEquals("upnext-0", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("more-upnext:0", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        moveTo("player-card", KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(SectionPage(Section.NOW_PLAYING), navigator.current)
        assertEquals("play-pause", focused())
        rule.onNodeWithTag("player-card").assertDoesNotExist()
    }

    /** Saves the main pages as pictures (build/screenshots, kept by CI) to review the design. */
    @Test
    @Config(qualifiers = "+xhdpi")
    fun savesScreenshotsOfTheMainPages() {
        rule.runOnUiThread { folders.value = manyResults(40) }
        settle(1_000)
        screenshot("1-search")
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        screenshot("2-search-selected")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        screenshot("3-player-panel")
        moveUntil(KeyEvent.KEYCODE_DPAD_LEFT) { it.startsWith("release-") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        screenshot("4-album")
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        screenshot("5-menu")
        press(KeyEvent.KEYCODE_BACK)
        moveTo("atmos-switch", KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        screenshot("6-atmos-switch-on")
        rule.runOnUiThread { navigator.select(Section.ARTISTS) }
        settle(1_000)
        screenshot("7-artists")
        rule.runOnUiThread { navigator.open(SettingsPage(SettingsKind.APPEARANCE)) }
        settle(1_000)
        screenshot("8-appearance")
        // File names that repeat the artist and album, with a long title.
        rule.runOnUiThread {
            folders.value = listOf(longNames())
            navigator.open(ReleasePage(Releases.group(folders.value).first(), Section.SEARCH))
        }
        settle(1_000)
        press(KeyEvent.KEYCODE_DPAD_DOWN, 3)
        screenshot("9-long-names")
    }

    /** Saves the screen as [name].png; if it can't be captured, says why in errors.txt instead of failing. */
    private fun screenshot(name: String) {
        val dir = File(System.getProperty("soundhub.screenshots") ?: "build/screenshots").apply { mkdirs() }
        val bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse { composeError ->
            runCatching {
                rule.runOnUiThread {
                    val view = rule.activity.window.decorView
                    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                }
            }.getOrElse { viewError ->
                File(dir, "errors.txt").appendText("$name: $composeError / $viewError\n")
                return
            }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    /** What can be selected on screen, with where it is (for failure messages). */
    private fun clickables(): String =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().joinToString("; ") { node ->
            val name = node.config.getOrNull(SemanticsProperties.TestTag)
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") ?: "?"
            val b = node.boundsInRoot
            "$name[${b.left.toInt()},${b.top.toInt()}-${b.right.toInt()},${b.bottom.toInt()}]"
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

    private fun menuOpen(): Boolean = focused().startsWith("menu-")

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

/** An album whose file names repeat the artist and album, as many shares do. */
private fun longNames(): SearchFolder {
    val directory = "@@tay\\Music\\Taylor Swift\\1989 (Deluxe Edition) [FLAC 24-44.1]"
    val titles = listOf("Welcome To New York", "Blank Space", "Style", "Out Of The Woods", "All You Had To Do Was Stay (Live From The Tour)", "Shake It Off")
    val tracks = titles.mapIndexed { i, title ->
        val path = "$directory\\Taylor Swift - 1989 - 0${i + 1} - $title.flac"
        SearchTrack(
            "tay",
            SharedFile(path, 46_000_000, "", mapOf(1 to 230, 4 to 44_100, 5 to 24)),
            AudioFormats.classify(path, 46_000_000, durationSec = 230, sampleRate = 44_100, bitDepth = 24),
            PathNames.describe(path),
        )
    }
    return SearchFolder("tay", directory, tracks, slotFree = true, avgSpeed = 900_000, queueLength = 0)
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
    onInstall: (Int) -> Unit,
    onPanelSeek: (Long) -> Unit,
    onJump: (Int) -> Unit,
    onAtmos: (Boolean) -> Unit,
    mine: List<String>?,
    onAddArtist: () -> Unit,
    onPlaylist: (Int) -> Unit,
) {
    val navigator = remember { Navigator(Section.SEARCH) }
    SideEffect { onNavigator(navigator) }
    val releases = remember(folders) { Releases.group(folders) }
    // As on a TV while something is loaded: the player panel beside every page but Now playing.
    val panel: @Composable () -> Unit = {
        PlayerPanelLayout(
            song = PanelSong("Song 1", "Artist 0", 200, "Album 0", null),
            upNext = (2..6).map { PanelSong("Song $it", "Artist 0", 200, "Album 0", null) },
            favourite = false,
            playing = true,
            shuffle = false,
            repeatMode = 0,
            positionMs = 60_000,
            durationMs = 200_000,
            downloaded = null,
            seekable = true,
            onOpen = { navigator.open(SectionPage(Section.NOW_PLAYING)) },
            onFavourite = {},
            onShuffle = {},
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onRepeat = {},
            onSeekBy = onPanelSeek,
            onJump = onJump,
            onMenu = {},
            onClear = {},
            art = { song, size -> AlbumArt(null, song.title, size) },
        )
    }
    SoundHubShell(
        navigator,
        sidePanel = if (navigator.current != SectionPage(Section.NOW_PLAYING)) panel else null,
    ) { page ->
        when (page) {
            is SectionPage -> when (page.section) {
                Section.SEARCH -> {
                    var filter by remember { mutableStateOf(MusicFilter()) }
                    var atmos by remember { mutableStateOf(false) }
                    SearchLayout(
                        hint = "Search songs, artists, albums…",
                        query = "rock",
                        status = SearchStatus("${releases.size} albums"),
                        releases = releases,
                        filter = filter,
                        onFilter = { filter = it },
                        atmos = atmos,
                        onAtmos = {
                            atmos = it
                            onAtmos(it)
                        },
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
                    // As on the device: the listener's own artists come first once there are some.
                    val genres = remember(mine) {
                        if (mine == null) FamousArtists.genres
                        else listOf(ArtistGenre(MyArtists.GENRE, mine.map { FamousArtist(it) })) + FamousArtists.genres
                    }
                    var genre by remember { mutableStateOf(if (mine == null) 0 else 1) }
                    var mode by remember { mutableStateOf(ArtistSearch.ALL) }
                    ArtistsLayout(genres, genre, { genre = it }, mode, { mode = it }, onAdd = onAddArtist) { artist ->
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
                onPlaylist = { onPlaylist(it.size) },
            )
            is SettingsPage -> if (page.kind == SettingsKind.APPEARANCE) {
                AppearanceLayout(Palettes.all, currentPalette.id) { currentPalette = Palettes.byId(it) }
            } else UpdatesLayout(
                state = UpdateState.Available(AvailableUpdate(70, "SoundHub build 70", "feat: updates", "https://example.test/a.apk", 1)),
                currentBuild = 61,
                onCheck = {},
                onInstall = { onInstall(it.build) },
                onAllow = {},
            )
            else -> Text("other page")
        }
    }
}
