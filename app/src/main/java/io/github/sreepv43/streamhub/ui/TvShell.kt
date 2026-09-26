package io.github.sreepv43.streamhub.ui

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
 * Shared by [TvShell] with its pages and with every [tvFocus] element: which page is shown, which
 * page holds the selection, and the element selected last on each page, so closing the menu or
 * coming back to a page with Back puts the selection where it was. Elements are identified by
 * their place in the UI, which stays the same when a page is reopened.
 */
class TvShellState internal constructor() {
    internal var shownPage: Any? = null
    internal var focusedPage by mutableStateOf<Any?>(null)
    private val lastByPage = object : LinkedHashMap<Any?, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any?, Int>?) = size > MAX_PAGES
    }
    private val elements = HashMap<Pair<Any?, Int>, FocusRequester>()
    private val pages = HashMap<Any?, FocusRequester>()

    internal fun register(page: Any?, id: Int, requester: FocusRequester) {
        elements[page to id] = requester
    }

    internal fun unregister(page: Any?, id: Int, requester: FocusRequester) {
        if (elements[page to id] === requester) elements.remove(page to id)
    }

    internal fun focused(page: Any?, id: Int) {
        trace?.add("focused ${page.short()}:$id")
        lastByPage[page] = id
    }

    internal fun registerPage(page: Any?, requester: FocusRequester) {
        pages[page] = requester
    }

    internal fun unregisterPage(page: Any?, requester: FocusRequester) {
        if (pages[page] === requester) pages.remove(page)
    }

    /** Handle for moving the selection to [page]'s first element (null if it isn't composed). */
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

    /** Asks the element selected last on [page] to take focus; false if it isn't on screen. */
    internal fun restore(page: Any?): Boolean {
        val result = remembered(page)?.let { runCatching { it.requestFocus() } }
        trace?.add("restore ${page.short()}:${lastByPage[page]} -> ${result ?: "not on screen"}")
        return result?.isSuccess == true
    }

    /** Test hook: records focus events when set. */
    internal var trace: MutableList<String>? = null
    private fun Any?.short() = toString().take(4)

    override fun toString() =
        "shown=$shownPage focused=$focusedPage last=${lastByPage[shownPage]} on screen=${elements.keys.filter { it.first == shownPage }.map { it.second }}"
}

val LocalTvShell = staticCompositionLocalOf<TvShellState?> { null }
internal val LocalTvPage = staticCompositionLocalOf<Any?> { null }

/**
 * One page inside [TvShell] (wrap each NavHost destination). While the next page is replacing it,
 * the old one can't take the selection; whenever the selection enters the page from outside
 * (menu, previous page, Android's own "focus something" after the old page goes), it goes to the
 * element selected there last time.
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
                        else -> shell.remembered(key) ?: FocusRequester.Default
                    }
                }
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
 * - It opens only on a fresh press of Left at the left edge of the page (holding Left to scroll
 *   back through a row stops at the first poster), and closes on Right, Back or a selection.
 * - Every new page gets the selection as soon as it has something focusable. Until then the
 *   selection waits on the collapsed menu, so the remote always has something to move.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvShell(
    entries: List<MenuEntry>,
    selectedKey: String?,
    pageKey: Any?,
    onSelect: (MenuEntry) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val shell = remember { TvShellState() }
    val pageFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    // Visual state (labels shown, drawn over the page) and whether the items can take focus.
    // Both are read live by the focus system, so changes apply without waiting for a frame.
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
    // selected there last time once it is back on screen, otherwise to the page's first element,
    // retrying while the page is still loading. If the page has nothing focusable for a while, the
    // selection waits on the collapsed menu instead of being nowhere.
    val inputMode = inputModes.inputMode
    LaunchedEffect(pageKey, refocus, inputMode) {
        if (inputMode == InputMode.Touch) return@LaunchedEffect
        withFrameNanos { }
        var waited = 0L
        while (!pageHasFocus() && !expanded && waited < GIVE_UP_MS) {
            val restored = waited < RESTORE_WAIT_MS && shell.restore(pageKey)
            if (!restored && (waited >= RESTORE_WAIT_MS || !shell.remembers(pageKey))) {
                // The remembered element isn't coming back (e.g. the list changed): start at the top.
                shell.trace?.add("first element, after ${waited}ms")
                shell.forget(pageKey)
                focusPageStart()
            }
            val step = if (waited < 1_000) 50L else 250L
            delay(step)
            waited += step
            if (!pageHasFocus() && !menuHasFocus && waited >= PARK_AFTER_MS) {
                menuActive = true
                runCatching { menuFocus.requestFocus() }
            }
        }
    }

    // Focus can vanish when the focused element is removed (a list reloads, a download is
    // deleted); bring it back.
    val anyFocus = pageHasFocus() || menuHasFocus
    LaunchedEffect(anyFocus) {
        if (!anyFocus) {
            delay(200)
            refocus++
        }
    }

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTvShell provides shell, LocalBringIntoViewSpec provides TvScrolling.Edge) {
            content(
                Modifier
                    .fillMaxSize()
                    .padding(start = RailCollapsed)
                    .focusRequester(pageFocus)
                    .focusGroup()
                    // Arrows move the selection before the focused element sees them, so text fields
                    // can't trap it (not every remote is recognised as a D-pad). At the left edge,
                    // a new press of Left opens the menu.
                    .onPreviewKeyEvent { event ->
                        val direction = arrowDirection(event)
                        when {
                            direction == null -> false
                            focusManager.moveFocus(direction) -> true
                            direction == FocusDirection.Left -> {
                                if (event.nativeKeyEvent.repeatCount == 0) openMenu()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }

        val selectedIndex = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(if (expanded) RailExpanded else RailCollapsed)
                .background(if (expanded) AppColors.panel else AppColors.background)
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
        selected -> AppColors.accent
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

/** How lists follow the selection (install with LocalBringIntoViewSpec). */
@OptIn(ExperimentalFoundationApi::class)
object TvScrolling {
    /**
     * Scrolls a list only when the focused element gets close to its edge, and only as far as
     * needed, so moving along a row doesn't make everything shift on every press. (Android TV
     * devices otherwise default to keeping the focused item pinned a third of the way in.)
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

    /** For lists that position their rows themselves (see alignRowOnFocus). */
    val None: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
    }
}

private const val EDGE_MARGIN = 0.08f
private const val RESTORE_WAIT_MS = 300L
private const val PARK_AFTER_MS = 2_000L
private const val MAX_PAGES = 64
private const val GIVE_UP_MS = 30_000L
