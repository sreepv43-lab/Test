package io.github.sreepv43.streamhub.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.sreepv43.streamhub.ui.screens.AddonsScreen
import io.github.sreepv43.streamhub.ui.screens.CatalogScreen
import io.github.sreepv43.streamhub.ui.screens.DetailScreen
import io.github.sreepv43.streamhub.ui.screens.DownloadsScreen
import io.github.sreepv43.streamhub.ui.screens.HomeScreen
import io.github.sreepv43.streamhub.ui.screens.LinkScreen
import io.github.sreepv43.streamhub.ui.screens.SearchScreen
import io.github.sreepv43.streamhub.ui.screens.SettingsScreen
import io.github.sreepv43.streamhub.ui.screens.StreamsScreen

private data class Section(val route: String, val base: String, val label: String, val icon: ImageVector)

private val sections = listOf(
    Section(Routes.HOME, Routes.HOME, "Home", Icons.Default.Home),
    Section(Routes.SEARCH, Routes.SEARCH, "Search", Icons.Default.Search),
    Section(Routes.link(), "link", "Open link", Icons.Default.Link),
    Section(Routes.DOWNLOADS, Routes.DOWNLOADS, "Downloads", Icons.Default.Download),
    Section(Routes.addons(), "addons", "Addons", Icons.Default.Extension),
    Section(Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

/**
 * Side menu + content. The menu is a slim, unfocusable icon strip while browsing, so opening a
 * page never pulls the selection into it. It opens only when the user is at the left edge of the
 * page and presses Left again, and closes as soon as an item is chosen (or on Right/Back).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(navRequest: String?, onNavRequestHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val focusManager = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(navRequest) {
        if (navRequest != null) {
            nav.navigate(navRequest) { launchSingleTop = true }
            onNavRequestHandled()
        }
    }

    // Put the selection into every newly opened page (retrying while it is still loading).
    LaunchedEffect(entry?.id, menuOpen) {
        if (menuOpen) {
            delay(50)
            runCatching { menuFocus.requestFocus() }
            return@LaunchedEffect
        }
        repeat(40) {
            delay(100)
            if (contentHasFocus) return@LaunchedEffect
            runCatching { contentFocus.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AppNavHost(
                nav,
                Modifier
                    .fillMaxSize()
                    .padding(start = RailCollapsed)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRequester(contentFocus)
                    // Coming back from the menu returns to the poster that was selected before.
                    .focusRestorer()
                    .focusGroup()
                    // Runs only when nothing on the page used the key: at the left edge, Left opens the menu.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && !menuOpen) {
                            if (!focusManager.moveFocus(FocusDirection.Left)) menuOpen = true
                            true
                        } else false
                    },
            )
            SideMenu(
                nav = nav,
                open = menuOpen,
                selectedFocus = menuFocus,
                onClose = { menuOpen = false },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

private val RailCollapsed = 76.dp
private val RailExpanded = 220.dp

@Composable
private fun SideMenu(
    nav: NavHostController,
    open: Boolean,
    selectedFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val entry by nav.currentBackStackEntryAsState()
    val currentBase = entry?.destination?.route?.substringBefore('?')?.substringBefore('/')
    val selectedIndex = sections.indexOfFirst { it.base == currentBase }.coerceAtLeast(0)

    Column(
        modifier
            .fillMaxHeight()
            .width(if (open) RailExpanded else RailCollapsed)
            .background(if (open) AppColors.panel else AppColors.background)
            .onFocusChanged { if (open && !it.hasFocus) onClose() }
            // Right simply moves into the page (which closes the menu); Back closes it too.
            .onKeyEvent { event ->
                if (open && event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onClose()
                    true
                } else false
            }
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        sections.forEachIndexed { index, section ->
            MenuItem(
                section = section,
                selected = currentBase == section.base,
                expanded = open,
                focusable = open,
                modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                onClick = {
                    onClose()
                    if (section.base == Routes.HOME) {
                        // Always land on the home page itself, dropping whatever was opened from it.
                        if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.navigate(Routes.HOME)
                    } else {
                        nav.navigate(section.route) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun MenuItem(
    section: Section,
    selected: Boolean,
    expanded: Boolean,
    focusable: Boolean,
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
            // Unreachable with the remote while the menu is closed; taps still work on tablets.
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) AppColors.text else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = section.label, tint = tint, modifier = Modifier.size(24.dp))
        if (expanded) {
            Text(
                section.label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, modifier: Modifier) {
    // No page transition animations: they cost frames on TV hardware and delay focus.
    NavHost(
        nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMeta = { nav.navigate(Routes.detail(it.type, it.id)) },
                onOpenHistory = { entry ->
                    if (entry.videoId != entry.metaId && entry.type != "movie") {
                        nav.navigate(Routes.streams(entry.type, entry.metaId, entry.videoId))
                    } else {
                        nav.navigate(Routes.detail(entry.type, entry.metaId))
                    }
                },
                onSeeAll = { nav.navigate(Routes.catalog(it.addon.transportUrl, it.catalog.type, it.catalog.id)) },
                onOpenAddons = { nav.navigate(Routes.addons()) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenMeta = { nav.navigate(Routes.detail(it.type, it.id)) })
        }
        composable(Routes.DOWNLOADS) { DownloadsScreen() }
        composable(
            Routes.ADDONS,
            arguments = listOf(navArgument("install") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            AddonsScreen(initialUrl = entry.arguments?.getString("install"))
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(
            Routes.LINK,
            arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            LinkScreen(initialUrl = entry.arguments?.getString("url"))
        }
        composable(
            Routes.CATALOG,
            arguments = listOf(
                navArgument("addon") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
            ),
        ) {
            CatalogScreen(onOpenMeta = { nav.navigate(Routes.detail(it.type, it.id)) })
        }
        composable(Routes.DETAIL) {
            DetailScreen(
                onOpenEpisode = { type, metaId, videoId -> nav.navigate(Routes.streams(type, metaId, videoId)) },
            )
        }
        composable(Routes.STREAMS) {
            StreamsScreen()
        }
    }
}
