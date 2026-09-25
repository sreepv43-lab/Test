package io.github.sreepv43.streamhub.ui

import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.MetaRow
import io.github.sreepv43.streamhub.ui.components.RowState
import io.github.sreepv43.streamhub.ui.components.tvFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the side menu and pages with remote key presses sent through the Activity, the same path
 * a TV remote takes, and checks where the selection lands after each press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h720dp-land-television-mdpi")
class TvShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    @OptIn(ExperimentalComposeUiApi::class)
    @Before
    fun setUp() {
        // Real Android TV devices report this feature (it changes Compose's default scrolling).
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        rule.setContent {
            val inputModes = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputModes.requestInputMode(InputMode.Keyboard) }
            StreamHubTheme { FakeApp { nav = it } }
        }
        rule.runOnUiThread { composeView().requestFocus() }
        settle(1_000)
    }

    @Test
    fun firstPosterGetsTheSelectionAndMenuStaysClosed() {
        assertEquals("r0-c0", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun menuOpensAtRowStartAndMovesBetweenSections() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-home", focused())
        assertTrue(menuExpanded())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("menu-search", focused())
        assertTrue("menu must stay open while moving in it", menuExpanded())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("menu-downloads", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("menu-search", focused())

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("search", route())
        assertEquals("search-field", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun everySectionOpensFromTheMenu() {
        val order = listOf("home", "search", "downloads")
        for (target in listOf("search", "downloads", "home", "downloads", "search", "home")) {
            openMenu()
            val from = order.indexOf(route())
            val to = order.indexOf(target)
            repeat(Math.abs(to - from)) { press(if (to > from) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
            assertEquals("menu-$target", focused())
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            settle(1_500)
            assertEquals(target, route())
            assertFalse("menu must close after choosing $target", menuExpanded())
            if (target == "home") assertNull("Home must not stack pages", nav.previousBackStackEntry)
        }
    }

    @Test
    fun rightClosesMenuAndReturnsToTheSamePoster() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("r1-c0", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("rows that are still loading are skipped", "r3-c0", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-home", focused())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("r3-c0", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun backClosesMenuWithoutLeavingThePage() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("detail/{id}", route())
        assertEquals("Play", focused())
        assertFalse("opening a poster must not open the menu", menuExpanded())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Download", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Play", focused())
        assertFalse(menuExpanded())

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(menuExpanded())
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(menuExpanded())
        assertEquals("detail/{id}", route())
        assertEquals("Play", focused())

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("home", route())
        assertEquals("Back returns to the poster that was opened", "r0-c1", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun backReturnsToTheOpenedPosterFurtherDownAndAlong() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 9)
        val opened = focused()
        assertTrue(opened, opened.startsWith("r4-"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("detail/{id}", route())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("home", route())
        assertEquals(opened, focused())
    }

    @Test
    fun searchResultsKeepTheSelectionWhenComingBack() {
        openMenu()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("search-field", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val opened = focused()
        assertTrue(opened, opened.startsWith("s-c"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("detail/{id}", route())
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("search", route())
        assertEquals("the result, not the search box (which would pop up the keyboard)", opened, focused())
    }

    @Test
    fun homeFromAnOpenedPosterGoesBackToHome() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("detail/{id}", route())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-home", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("home", route())
        assertNull(nav.previousBackStackEntry)
        assertEquals("r0-c0", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun choosingTheCurrentSectionJustClosesTheMenu() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-home", focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("home", route())
        assertNull(nav.previousBackStackEntry)
        assertEquals("r1-c0", focused())
        assertFalse(menuExpanded())
    }

    @Test
    fun holdingLeftStopsAtTheFirstPoster() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 4)
        assertEquals("r0-c4", focused())
        for (repeatCount in 0..6) {
            key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, repeatCount)
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP)
        settle()
        assertEquals("r0-c0", focused())
        assertFalse("a held key must not open the menu", menuExpanded())

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("a new press at the first poster opens the menu", menuExpanded())
    }

    @Test
    fun leftAtRowStartOpensMenuEvenWhenTheRowAboveIsScrolled() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 12)
        assertEquals("r0-c12", focused())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val start = focused()
        assertTrue("moved into the next row, got $start", start.startsWith("r1-"))
        repeat(20) {
            if (focused() != "r1-c0" && !menuExpanded()) press(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        assertEquals("r1-c0", focused())
        val rowAboveLeft = (0..12)
            .mapNotNull { c -> rule.onAllNodesWithText("r0-c$c").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left }
            .minOrNull()
        println("leftmost poster of the scrolled row above: $rowAboveLeft px")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-home", focused())
        assertTrue(menuExpanded())
    }

    @Test
    fun rowsAndPageDoNotShiftWhileTheSelectionIsInView() {
        val poster = { name: String -> rule.onAllNodesWithText(name)[1].getBoundsInRoot() }
        val title = { rule.onAllNodesWithText("Row 0")[0].getBoundsInRoot().top }
        val c5 = poster("r0-c5").left
        val top = title()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 3)
        assertEquals("r0-c3", focused())
        assertEquals("row must not scroll while the poster is visible", c5, poster("r0-c5").left)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focused().startsWith("r1-"))
        assertEquals("page must not scroll while the row is visible", top, title())
    }

    @Test
    fun emptyPageKeepsTheSelectionOnTheCollapsedMenu() {
        openMenu()
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle(1_500)
        assertEquals("downloads", route())
        assertEquals("menu-downloads", focused())
        assertFalse("the page must not be covered", menuExpanded())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("menu-downloads", focused())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("menu-search", focused())
        assertTrue(menuExpanded())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(menuExpanded())

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back from a page goes to the previous page", "home", route())
    }

    @Test
    fun leftInTheSearchFieldOpensTheMenu() {
        openMenu()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("search-field", focused())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("menu-search", focused())
        assertTrue(menuExpanded())
    }

    private fun openMenu() {
        repeat(20) { if (!menuExpanded()) press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("menu did not open, selection on ${focused()}", menuExpanded())
    }

    private fun route(): String? = nav.currentDestination?.route

    private fun menuExpanded(): Boolean = rule.onNodeWithTag("menu").getBoundsInRoot().let { it.right - it.left } > 150.dp

    private fun focused(): String {
        val nodes = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        return nodes.joinToString { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
                ?: "?"
        }.ifEmpty { "<nothing>" }
    }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) {
        key(keyCode, KeyEvent.ACTION_DOWN)
        key(keyCode, KeyEvent.ACTION_UP)
        settle()
    }

    private fun key(keyCode: Int, action: Int, repeatCount: Int = 0) {
        rule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, repeatCount))
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

private val entries = listOf(
    MenuEntry("home", "Home", Icons.Default.Home),
    MenuEntry("search", "Search", Icons.Default.Search),
    MenuEntry("downloads", "Downloads", Icons.Default.Download),
)

/** Same structure as the app: TvShell around a NavHost, Home made of real poster rows. */
@Composable
private fun FakeApp(onNav: (NavHostController) -> Unit) {
    val nav = rememberNavController()
    SideEffect { onNav(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    TvShell(
        entries = entries,
        selectedKey = entries.firstOrNull { it.key == route }?.key ?: "home",
        pageKey = entry?.id,
        onSelect = { nav.openSection(it.key, it.key, home = "home") },
    ) { pageModifier ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = pageModifier,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable("home") { FakeHome(onOpen = { nav.navigate("detail/$it") }) }
            composable("search") { FakeSearch(onOpen = { nav.navigate("detail/$it") }) }
            composable("downloads") { Text("No downloads yet", Modifier.padding(24.dp)) }
            composable("detail/{id}") { FakeDetail(it.arguments?.getString("id")) }
        }
    }
}

@Composable
private fun FakeHome(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp)) {
        item { Text("Home page", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        for (r in 0 until 6) {
            item(key = r) {
                val state = if (r == 2) {
                    RowState.Loading
                } else {
                    RowState.Loaded(List(20) { c -> Meta(id = "r$r-c$c", name = "r$r-c$c") })
                }
                MetaRow(title = "Row $r", state = state, onMetaClick = { onOpen(it.id) }, onSeeAll = {})
            }
        }
    }
}

@Composable
private fun FakeSearch(onOpen: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(24.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Search movies") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("search-field").tvFocus(),
            )
            FlatButton(text = "Go", onClick = {}, modifier = Modifier.padding(start = 12.dp))
        }
        MetaRow(
            title = "Results",
            state = RowState.Loaded(List(10) { c -> Meta(id = "s-c$c", name = "s-c$c") }),
            onMetaClick = { onOpen(it.id) },
        )
    }
}

@Composable
private fun FakeDetail(id: String?) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Detail $id")
        Row(Modifier.padding(top = 16.dp)) {
            FlatButton(text = "Play", onClick = {})
            FlatButton(text = "Download", onClick = {}, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
