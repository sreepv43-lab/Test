package io.github.sreepv43.streamhub.ui

import io.github.sreepv43.streamhub.ui.components.rememberPosterMenu
import io.github.sreepv43.streamhub.ui.components.RowState
import io.github.sreepv43.streamhub.ui.components.PosterTarget
import io.github.sreepv43.streamhub.ui.components.PosterMenu
import io.github.sreepv43.streamhub.ui.components.MetaRow
import io.github.sreepv43.streamhub.addon.Meta
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sreepv43.streamhub.ui.components.TvTextField
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.sreepv43.streamhub.AppContainer
import io.github.sreepv43.streamhub.StreamHubApp
import io.github.sreepv43.streamhub.addon.AddonStreams
import io.github.sreepv43.streamhub.addon.InstalledAddon
import io.github.sreepv43.streamhub.addon.Manifest
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.ui.components.StreamsState
import io.github.sreepv43.streamhub.ui.components.streamItems
import io.github.sreepv43.streamhub.ui.screens.SettingsScreen
import io.github.sreepv43.streamhub.ui.screens.CinemaHome
import io.github.sreepv43.streamhub.player.HudPanel
import io.github.sreepv43.streamhub.player.HudTrack
import io.github.sreepv43.streamhub.player.PlayerHud
import io.github.sreepv43.streamhub.player.PlayerHudActions
import io.github.sreepv43.streamhub.player.PlayerHudState
import io.github.sreepv43.streamhub.ui.screens.CinemaDownloads
import io.github.sreepv43.streamhub.download.DownloadItem
import io.github.sreepv43.streamhub.download.DownloadLocation
import androidx.compose.runtime.CompositionLocalProvider
import io.github.sreepv43.streamhub.ui.screens.CinemaTitlePage
import io.github.sreepv43.streamhub.ui.components.StreamHandlers
import io.github.sreepv43.streamhub.ui.screens.HomeState
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.addon.CatalogRef
import io.github.sreepv43.streamhub.addon.CatalogDef
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real screens and components driven with remote key presses (with the app's own container). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = StreamHubApp::class, qualifiers = "w1280dp-h720dp-land-television-mdpi")
class ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val container: AppContainer get() = rule.activity.container

    @Before
    fun setUp() {
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    @Test
    fun playBestStartsTheBestStreamAndTheListCanBeOrderedBestFirst() {
        val web720 = Stream(url = "https://a.example/movie-720.mp4", name = "Web 720", title = "Movie 720p WEB 1.2 GB")
        val torrent1080 = Stream(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            name = "Torrent 1080",
            title = "Movie 1080p BluRay 2.1 GB 👤 300",
        )
        val external = Stream(externalUrl = "https://c.example/watch", name = "Website", title = "Movie 2160p")
        val state = StreamsState(
            results = listOf(
                AddonStreams(addon("First addon"), listOf(web720, external)),
                AddonStreams(addon("Second addon"), listOf(torrent1080)),
            ),
        )
        var played: Stream? = null
        var downloaded: Stream? = null
        show {
            val bestFirst by container.settings.streamsBestFirst.flow.collectAsState()
            LazyColumn(Modifier.fillMaxSize()) {
                streamItems(state, onPlay = { played = it }, onDownload = { downloaded = it }, onExternal = {}, bestFirst = bestFirst)
            }
        }

        focus(rule.onNodeWithText("Play best", substring = true))
        assertTrue(focused(), focused().startsWith("Play best · 1080p"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the sharpest stream the app can play", torrent1080, played)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("By addon", focused())
        assertTrue(rule.onAllNodesWithText("First addon").fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(container.settings.streamsBestFirst.value)
        assertEquals("Best first", focused())
        assertTrue("one list, not grouped by addon", rule.onAllNodesWithText("First addon").fetchSemanticsNodes().isEmpty())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().contains("Torrent 1080"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused(), focused().contains("Web 720"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("streams the app can't play come last: ${focused()}", focused().contains("Website"))

        press(KeyEvent.KEYCODE_DPAD_UP, times = 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Download", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(torrent1080, downloaded)
    }

    @Test
    fun playbackChoicesAreChangedWithTheRemote() {
        show { SettingsScreen() }
        val settings = container.settings

        // Settings are in tabs; the quality choices are under Playback.
        focus(rule.onNodeWithText("Playback"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        focus(rule.onNodeWithText("1080p"))
        assertEquals("1080p", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("720p", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(720, settings.maxResolution.value)

        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        assertEquals("4K", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2160, settings.maxResolution.value)

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Down goes to the start buffer row: ${focused()}", focused() in listOf("0.5 s", "1 s", "2.5 s", "5 s"))
        press(KeyEvent.KEYCODE_DPAD_LEFT, times = 3)
        assertEquals("the start buffer row is below the quality row", "0.5 s", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(500, settings.startBufferMs.value)

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_LEFT, times = 3)
        assertEquals("the audio row is below the start buffer row", "Video's default", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("English", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("en", settings.audioLanguage.value)
        assertEquals("choosing must not move the selection", "English", focused())
    }

    @Test
    fun menuKeyOnAPosterOffersMyList() {
        val meta = Meta(id = "tt0111161", type = "movie", name = "The Shawshank Redemption")
        show {
            val menu = rememberPosterMenu()
            PosterMenu(menu)
            MetaRow(
                title = "Popular",
                state = RowState.Loaded(listOf(meta)),
                onMetaClick = {},
                onMetaLongClick = { menu.target = PosterTarget.of(it) },
            )
        }
        focus(rule.onAllNodesWithText("The Shawshank Redemption")[0])
        press(KeyEvent.KEYCODE_MENU)
        rule.onNodeWithText("Add to My List").performClick()
        settle()
        assertTrue(container.library.contains("tt0111161"))

        focus(rule.onAllNodesWithText("The Shawshank Redemption")[0])
        press(KeyEvent.KEYCODE_MENU)
        rule.onNodeWithText("Remove from My List").performClick()
        settle()
        assertFalse(container.library.contains("tt0111161"))
    }

    @Test
    fun textFieldOnlyOpensTheEditorWhenOkIsPressed() {
        var typed = ""
        show {
            var value by remember { mutableStateOf("") }
            TvTextField(value = value, onValueChange = { value = it; typed = it }, label = "Stremio email")
        }
        focus(rule.onNodeWithText("Stremio email"))
        assertTrue("moving onto the field must not start typing", rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNode(hasSetTextAction()).performTextInput("me@example.com")
        rule.onNodeWithText("OK").performClick()
        settle()
        assertEquals("me@example.com", typed)
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun cinemaHomeShowsTheBannerAndMovesOneRowAtATime() {
        val metas = listOf(
            Meta(id = "tt1", name = "The Last Horizon", background = "https://img.example/bg.jpg", description = "A cartographer follows a signal.", releaseInfo = "2026", imdbRating = "8.6", genres = listOf("Sci-fi")),
            Meta(id = "tt2", name = "Blue Silence"),
        )
        val ref = CatalogRef(addon("Cinemeta"), CatalogDef(type = "movie", id = "top", name = "Popular"))
        var opened: Meta? = null
        show {
            CinemaHome(
                HomeState(initializing = false, rows = listOf(ref to RowState.Loaded(metas))),
                onOpenMeta = { opened = it },
                onOpenHistory = {},
                onSeeAll = {},
                onOpenAddons = {},
            )
        }
        rule.onNodeWithText("Cinemeta · Movie · 2 titles").assertExists()
        rule.onNodeWithText("A cartographer follows a signal.").assertExists()

        focus(rule.onNodeWithText("Customize Home"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("Choose which catalogue rows appear on Home.").assertExists()
        rule.onNodeWithText("Done").performClick()
        settle()
        assertTrue(rule.onAllNodesWithText("Choose which catalogue rows appear on Home.").fetchSemanticsNodes().isEmpty())

        focus(rule.onNodeWithText("Movie details"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("My List", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(container.library.contains("tt1"))
        assertEquals("In My List", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("tt1", opened?.id)

        // Down: My List row (just added), the catalogues heading, then the catalogue row; Up comes back to the banner.
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        assertEquals("Customize Home", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Blue Silence", focused())
        press(KeyEvent.KEYCODE_DPAD_UP, 3)
        assertTrue(focused(), focused() in setOf("Movie details", "In My List"))
    }

    @Test
    fun cinemaMenuShowsLabelsWhileClosed() {
        val entries = listOf(
            MenuEntry("home", "Home", Icons.Default.Home),
            MenuEntry("downloads", "Downloads", Icons.Default.Download),
        )
        show {
            TvShell(entries, selectedKey = "home", pageKey = "page", onSelect = {}, cinema = true) { pageModifier ->
                Box(pageModifier) { TvPage("page") { FlatButton("Play", onClick = {}) } }
            }
        }
        assertEquals("Play", focused())
        rule.onNodeWithText("Downloads").assertExists()
        val width = rule.onNodeWithTag("menu").getBoundsInRoot().let { it.right - it.left }
        // The tagged area is inside the menu's 12 dp side padding.
        assertEquals(RailCinema - 24.dp, width)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Home", focused())
    }

    @Test
    fun cinemaMoviePageShowsSourceCardsWithFiltersAndDetails() {
        val hd = Stream(url = "https://a.example/hd.mkv", name = "Web", title = "Movie.2026.1080p.WEB-DL.x264.AAC.5.1 [Tam + Mal] 4.2 GB")
        val uhd = Stream(url = "https://a.example/uhd.mkv", name = "Web", title = "Movie.2026.2160p.WEB-DL.HDR10.HEVC.DDP5.1 14.8 GB")
        var played: Stream? = null
        show {
            CinemaTitlePage(
                Meta(id = "tt1", name = "The Last Horizon", director = listOf("Elena Ward"), cast = listOf("Leo Vale")),
                subtitle = null,
                streams = StreamsState(results = listOf(AddonStreams(addon("Sample A"), listOf(uhd, hd)))),
                handlers = StreamHandlers(onPlay = { played = it }, onDownload = {}, onExternal = {}),
                resumeMs = null,
            )
        }
        rule.onNodeWithText("Leo Vale").assertExists()

        // The suggested source fits the 1080p setting.
        focus(rule.onNodeWithText("Play suggested"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(hd, played)

        // Sources jumps to the first card.
        focus(rule.onNodeWithText("Sources (2)"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_000)
        assertTrue(focused(), focused().contains("1080p · WEB-DL"))
        // Cards are only built once they scroll into view.
        rule.onNodeWithText("4K · WEB-DL · HDR10").assertExists()

        focus(rule.onAllNodesWithText("4K")[0]) // the chip; the 4K card has its own "4K" badge
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(rule.onAllNodesWithText("1080p · WEB-DL").fetchSemanticsNodes().isEmpty())

        focus(rule.onAllNodesWithText("Details")[0])
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("Description").assertExists()
    }

    @Test
    fun cinemaDownloadsFilterAndPlay() {
        val place = DownloadLocation(DownloadLocation.Kind.DIRECTORY, "/storage/elements/StreamHub", "Elements")
        val done = DownloadItem("1", "Monsoon Letters", url = "https://a.example/1.mkv", location = place, status = DownloadItem.Status.COMPLETED, totalBytes = 100, downloadedBytes = 100)
        val running = DownloadItem("2", "The Last Horizon", url = "https://a.example/2.mkv", location = place, status = DownloadItem.Status.RUNNING, totalBytes = 100, downloadedBytes = 62)
        var played: DownloadItem? = null
        show {
            CinemaDownloads(
                items = listOf(running, done),
                speeds = emptyMap(),
                waitingForWifi = false,
                onPlay = { played = it },
                onPause = {}, onResume = {}, onMove = {}, onDelete = {}, onPauseAll = {}, onResumeAll = {}, onOpenStorage = {},
            )
        }
        rule.onNodeWithText("Downloading · 62%").assertExists()
        rule.onNodeWithText("Completed ✓").assertExists()

        focus(rule.onNodeWithText("Completed"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(rule.onAllNodesWithText("Downloading · 62%").fetchSemanticsNodes().isEmpty())
        focus(rule.onNodeWithText("Play"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("1", played?.id)
    }

    @Test
    fun cinemaSettingsHaveTheDemoTabs() {
        show { CompositionLocalProvider(LocalCinema provides true) { SettingsScreen() } }
        rule.onNodeWithText("MAKE IT YOURS").assertExists()
        rule.onNodeWithText("Download location").assertExists()
        focus(rule.onNodeWithText("Appearance"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("How StreamHub looks").assertExists()
    }

    @Test
    fun cinemaPlayerControlsPlayOpenPanelsAndPickTracks() {
        val calls = mutableListOf<String>()
        val actions = object : PlayerHudActions {
            override fun togglePlay() { calls += "play" }
            override fun seekStep(forward: Boolean, repeat: Boolean) { calls += "seek $forward" }
            override fun exit() { calls += "exit" }
            override fun selectAudio(id: String) { calls += "audio $id" }
            override fun selectSubtitle(id: String?) { calls += "subtitle $id" }
            override fun shiftSubtitles(deltaMs: Long?) {}
            override fun setSubtitleScale(scale: Float) {}
            override fun setSubtitleBox(box: Boolean) {}
            override fun loadSources() {}
            override fun switchSource(index: Int) {}
            override fun setResizeMode(mode: Int) {}
            override fun setSpeed(speed: Float) {}
            override fun setStartBuffer(ms: Int) {}
            override fun copyReport() {}
            override fun restart() {}
            override fun download() {}
            override fun setSleep(minutes: Int) {}
            override fun retry() {}
            override fun dismissProblem() {}
        }
        val state = PlayerHudState(actions).apply {
            title = "Dune"
            durationMs = 9_000_000
            positionMs = 1_475_000
            controlsVisible = true
            audioTracks = listOf(
                HudTrack("0:0", "English", "Dolby Digital Plus · 5.1", selected = true),
                HudTrack("0:1", "Hindi", "AAC · stereo", selected = false),
            )
        }
        show { PlayerHud(state) }
        rule.runOnUiThread { state.focusPlay++ }
        settle()
        assertEquals("Pause", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("play"), calls)

        focus(rule.onNodeWithText("Languages"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(HudPanel.Languages, state.panel)
        rule.onNodeWithText("Audio & subtitles").assertExists()
        focus(rule.onNodeWithText("Hindi"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(calls.toString(), "audio 0:1" in calls)
    }

    private fun addon(name: String) = InstalledAddon("https://$name.example/manifest.json".replace(' ', '-'), Manifest(id = name, name = name))

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val inputModes = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
            StreamHubTheme { content() }
        }
        rule.runOnUiThread { composeView().requestFocus() }
        settle()
    }

    private fun focus(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    private fun focused(): String {
        val nodes = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        return nodes.joinToString { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
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

    private fun composeView(): View {
        fun find(view: View): View? = when {
            view.javaClass.name.endsWith("AndroidComposeView") -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find(rule.activity.window.decorView))
    }

    private fun settle(millis: Long = 400) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }
}
