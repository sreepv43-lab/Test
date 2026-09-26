package io.github.sreepv43.streamhub.ui

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
        press(KeyEvent.KEYCODE_DPAD_LEFT, times = 3)
        assertEquals("the audio row is below the quality row", "Video's default", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("English", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("en", settings.audioLanguage.value)
        assertEquals("choosing must not move the selection", "English", focused())
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
