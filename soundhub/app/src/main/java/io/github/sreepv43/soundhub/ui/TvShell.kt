package io.github.sreepv43.soundhub.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

data class MenuEntry(val key: String, val label: String, val icon: ImageVector)

val RailCollapsed = 76.dp
private val RailExpanded = 220.dp

/**
 * Shared by [TvShell], its pages and every [io.github.sreepv43.soundhub.ui.components.tvFocus]
 * element: which page is shown, which page holds the selection, and the element selected last on
 * each page, so coming back to a page (Back, or closing the menu) puts the selection where it was.
 */
class TvShellState internal constructor() {
    internal var shownPage: Any? = null
    internal var focusedPage by mutableStateOf<Any?>(null)

    /** The player bar under the page holds the selection. */
    internal var barHasFocus by mutableStateOf(false)

    /** A focused control uses Left/Right itself (the seek bar); the shell then doesn't move focus on them. */
    internal var horizontalClaim: Boolean = false
    private val lastByPage = object : LinkedHashMap<Any?, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any?, Int>?) = size > MAX_PAGES
    }
    private val elements = HashMap<Pair<Any?, Int>, FocusRequester>()
    private val pages = HashMap<Any?, FocusRequester>()
    private val defaults = HashMap<Any?, FocusRequester>()

    internal fun register(page: Any?, id: Int, requester: FocusRequester) {
        elements[page to id] = requester
    }

    internal fun unregister(page: Any?, id: Int, requester: FocusRequester) {
        if (elements[page to id] === requester) elements.remove(page to id)
    }

    internal fun registerDefault(page: Any?, requester: FocusRequester) {
        defaults[page] = requester
    }

    internal fun unregisterDefault(page: Any?, requester: FocusRequester) {
        if (defaults[page] === requester) defaults.remove(page)
    }

    internal fun focused(page: Any?, id: Int) {
        lastByPage[page] = id
    }

    internal fun registerPage(page: Any?, requester: FocusRequester) {
        pages[page] = requester
    }

    internal fun unregisterPage(page: Any?, requester: FocusRequester) {
        if (pages[page] === requester) pages.remove(page)
    }

    /** Handle for moving the selection to [page]'s starting element (null if it isn't composed). */
    internal fun pageRequester(page: Any?): FocusRequester? = pages[page]

    internal fun pageFocusChanged(page: Any?, hasFocus: Boolean) {
        if (hasFocus) focusedPage = page else if (focusedPage == page) focusedPage = null
    }

    internal fun remembers(page: Any?) = page in lastByPage

    internal fun forget(page: Any?) {
        lastByPage.remove(page)
    }

    /** The element selected last on [page], if it is on screen. */
    internal fun remembered(page: Any?): FocusRequester? = lastByPage[page]?.let { elements[page to it] }

    /** The element with [id] on [page], if it is on screen. */
    internal fun element(page: Any?, id: Int): FocusRequester? = elements[page to id]

    /** Where [page] starts when nothing is remembered: its marked default element, if on screen. */
    internal fun defaultOf(page: Any?): FocusRequester? = defaults[page]

    /** Asks the element selected last on [page] to take focus; false if it isn't on screen. */
    internal fun restore(page: Any?): Boolean =
        remembered(page)?.let { runCatching { it.requestFocus() }.isSuccess } == true

    override fun toString() =
        "shown=$shownPage focused=$focusedPage last=${lastByPage[shownPage]} on screen=${elements.keys.filter { it.first == shownPage }.map { it.second }}"
}

val LocalTvShell = staticCompositionLocalOf<TvShellState?> { null }
internal val LocalTvPage = staticCompositionLocalOf<Any?> { null }

/**
 * One page inside [TvShell]. While the next page is replacing it, the old one can't take the
 * selection; whenever the selection enters the page from outside (menu, previous page), it goes to
 * the element selected there last time, else the page's default element, else the first one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvPage(key: Any?, content: @Composable () -> Unit) {
    val shell = LocalTvShell.current
    val requester = remember { FocusRequester() }
    if (shell != null) {
        DisposableEffect(shell, key) {
            shell.registerPage(key, requester)
            onDispose {
                shell.unregisterPage(key, requester)
                shell.pageFocusChanged(key, false)
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { shell?.pageFocusChanged(key, it.hasFocus) }
            .focusRequester(requester)
            .focusProperties {
                enter = {
                    when {
                        shell == null -> FocusRequester.Default
                        shell.shownPage != key -> FocusRequester.Cancel
                        else -> shell.remembered(key) ?: shell.defaultOf(key) ?: FocusRequester.Default
                    }
                }
                // Left/Right never jump diagonally out of the page (e.g. into the player bar):
                // at the left edge Left opens the menu instead.
                exit = ::verticalExitOnly
            }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalTvPage provides key, content = content)
    }
}

/**
 * TV navigation: a slim side menu next to the page.
 *
 * - While browsing, the menu can't take focus, so nothing ever pulls the selection into it.
 * - It opens only on a fresh press of Left at the left edge of the page, and closes on Right,
 *   Back or a selection.
 * - Arrow keys move the selection before the focused element sees them, so text fields can't
 *   trap it.
 * - Every new page gets the selection as soon as it has something focusable. Until then the
 *   selection waits on the collapsed menu, so the remote always has something to move.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TvShell(
    entries: List<MenuEntry>,
    selectedKey: String?,
    pageKey: Any?,
    onSelect: (MenuEntry) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val shell = remember { TvShellState() }
    val pageFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    var menuActive by remember { mutableStateOf(false) }
    var menuHasFocus by remember { mutableStateOf(false) }
    var refocus by remember { mutableIntStateOf(0) }

    SideEffect { shell.shownPage = pageKey }
    fun pageHasFocus() = shell.focusedPage == pageKey

    fun openMenu() {
        menuActive = true
        expanded = true
        runCatching { menuFocus.requestFocus() }
    }

    fun focusPageStart() {
        runCatching { (shell.pageRequester(pageKey) ?: pageFocus).requestFocus() }
    }

    fun closeMenu() {
        expanded = false
        if (!shell.restore(pageKey)) focusPageStart()
        refocus++
    }

    // Moves the selection into the page whenever a page opens or the menu closes: to the element
    // selected there last time once it is back on screen, otherwise to the page's start, retrying
    // while the page is still loading. If the page has nothing focusable for a while, the
    // selection waits on the collapsed menu instead of being nowhere.
    val inputMode = inputModes.inputMode
    LaunchedEffect(pageKey, refocus, inputMode) {
        if (inputMode == InputMode.Touch) return@LaunchedEffect
        withFrameNanos { }
        var waited = 0L
        while (!pageHasFocus() && !expanded && waited < GIVE_UP_MS) {
            // The listener moved down to the player bar meanwhile: leave the selection there.
            if (shell.barHasFocus && waited >= RESTORE_WAIT_MS) break
            val restored = waited < RESTORE_WAIT_MS && shell.restore(pageKey)
            if (!restored && (waited >= RESTORE_WAIT_MS || !shell.remembers(pageKey))) {
                // The remembered element isn't coming back (e.g. the list changed): use the start.
                shell.forget(pageKey)
                focusPageStart()
            }
            val step = if (waited < 1_000) 50L else 250L
            delay(step)
            waited += step
            if (!pageHasFocus() && !menuHasFocus && !shell.barHasFocus && waited >= PARK_AFTER_MS) {
                menuActive = true
                runCatching { menuFocus.requestFocus() }
            }
        }
    }

    // Focus can vanish when the focused element is removed (a list changes, a download is
    // removed); bring it back.
    val anyFocus = pageHasFocus() || menuHasFocus || shell.barHasFocus
    LaunchedEffect(anyFocus) {
        if (!anyFocus) {
            delay(200)
            refocus++
        }
    }

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTvShell provides shell, LocalBringIntoViewSpec provides TvScrolling.Edge) {
            // The page, with the player bar under it (Down from the end of a page reaches the bar).
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = RailCollapsed)
                    .focusRequester(pageFocus)
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        val direction = arrowDirection(event)
                        val horizontal = direction == FocusDirection.Left || direction == FocusDirection.Right
                        when {
                            direction == null -> false
                            // The seek bar uses Left/Right to seek.
                            horizontal && shell.horizontalClaim -> false
                            moveFocusSafely(focusManager, direction) -> true
                            direction == FocusDirection.Left -> {
                                if (event.nativeKeyEvent.repeatCount == 0) openMenu()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.fillMaxSize()) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { shell.barHasFocus = it.hasFocus }
                        .focusProperties { exit = ::verticalExitOnly }
                        .focusGroup(),
                ) { bottomBar() }
            }
        }

        val selectedIndex = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(if (expanded) RailExpanded else RailCollapsed)
                .background(if (expanded) AppColors.panel else AppColors.rail)
                // A focus group, so moving between items never reports the menu as unfocused.
                .onFocusChanged {
                    menuHasFocus = it.hasFocus
                    if (!it.hasFocus) {
                        menuActive = false
                        expanded = false
                    }
                }
                .focusGroup()
                .onKeyEvent { event -> handleMenuKey(event, expanded, onExpand = { expanded = true }, onClose = ::closeMenu) }
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .testTag("menu"),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            entries.forEachIndexed { index, entry ->
                MenuItem(
                    entry = entry,
                    selected = entry.key == selectedKey,
                    expanded = expanded,
                    focusable = { menuActive },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(menuFocus) else Modifier,
                    onClick = {
                        expanded = false
                        onSelect(entry)
                        refocus++
                    },
                )
            }
        }
    }
}

/**
 * Moves the selection; if Compose's focus search fails while a list is changing under it (rows
 * arriving or being filtered), the key press is dropped instead of closing the app.
 */
private fun moveFocusSafely(focusManager: FocusManager, direction: FocusDirection): Boolean = try {
    focusManager.moveFocus(direction)
} catch (e: IllegalStateException) {
    Log.w("SoundHub", "Focus move $direction failed", e)
    true
} catch (e: IllegalArgumentException) {
    Log.w("SoundHub", "Focus move $direction failed", e)
    true
}

/** Page and player bar are left only with Up/Down. */
@OptIn(ExperimentalComposeUiApi::class)
private fun verticalExitOnly(direction: FocusDirection): FocusRequester =
    if (direction == FocusDirection.Left || direction == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default

private fun arrowDirection(event: KeyEvent): FocusDirection? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> null
    }
}

private fun handleMenuKey(event: KeyEvent, expanded: Boolean, onExpand: () -> Unit, onClose: () -> Unit): Boolean {
    val down = event.type == KeyEventType.KeyDown
    return when (event.key) {
        Key.DirectionRight -> {
            if (down) onClose()
            true
        }
        // Only an open menu uses Back; otherwise it goes back a page as usual.
        Key.Back, Key.Escape -> {
            if (expanded && !down) onClose()
            expanded
        }
        Key.DirectionUp, Key.DirectionDown -> {
            if (down) onExpand()
            false
        }
        Key.DirectionLeft -> {
            if (down) onExpand()
            true
        }
        else -> false
    }
}

@Composable
private fun MenuItem(
    entry: MenuEntry,
    selected: Boolean,
    expanded: Boolean,
    focusable: () -> Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = when {
        focused -> AppColors.background
        selected -> Accent
        else -> AppColors.textDim
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("menu-${entry.key}")
            // Unreachable with the remote while the menu is closed; taps still work on tablets.
            .focusProperties { canFocus = focusable() }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) AppColors.text else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(24.dp))
        if (expanded) {
            Text(
                entry.label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** How lists follow the selection (installed by [TvShell]). */
@OptIn(ExperimentalFoundationApi::class)
object TvScrolling {
    /**
     * Scrolls a list only when the selection gets close to its edge, and only as far as needed, so
     * moving down a list doesn't shift everything on every press. (Android TV devices otherwise
     * keep the selection pinned a third of the way in.)
     */
    val Edge: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val margin = (containerSize * EDGE_MARGIN).coerceAtMost(((containerSize - size) / 2).coerceAtLeast(0f))
            val leading = offset - margin
            val trailing = offset + size + margin - containerSize
            return when {
                leading >= 0 && trailing <= 0 -> 0f
                leading < 0 && trailing > 0 -> 0f
                abs(leading) < abs(trailing) -> leading
                else -> trailing
            }
        }
    }
}

private const val EDGE_MARGIN = 0.12f
private const val RESTORE_WAIT_MS = 300L
private const val PARK_AFTER_MS = 2_000L
private const val MAX_PAGES = 64
private const val GIVE_UP_MS = 30_000L
