package io.github.sreepv43.streamhub.ui

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
 * Side menu + content. The menu is a slim icon strip while browsing and expands (over the content,
 * so nothing reflows) when focus moves into it. Works with a TV remote and with touch.
 */
@Composable
fun AppRoot(navRequest: String?, onNavRequestHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(navRequest) {
        if (navRequest != null) {
            nav.navigate(navRequest) { launchSingleTop = true }
            onNavRequestHandled()
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AppNavHost(nav, Modifier.fillMaxSize().padding(start = RailCollapsed))
            SideMenu(nav, Modifier.align(Alignment.CenterStart))
        }
    }
}

private val RailCollapsed = 76.dp
private val RailExpanded = 220.dp

@Composable
private fun SideMenu(nav: NavHostController, modifier: Modifier) {
    val entry by nav.currentBackStackEntryAsState()
    val currentBase = entry?.destination?.route?.substringBefore('?')?.substringBefore('/')
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxHeight()
            .width(if (expanded) RailExpanded else RailCollapsed)
            .background(if (expanded) Panel else Ink)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        sections.forEach { section ->
            MenuItem(
                section = section,
                selected = currentBase == section.base,
                expanded = expanded,
                onClick = {
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
private fun MenuItem(section: Section, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = when {
        focused -> Ink
        selected -> Accent
        else -> Color.White.copy(alpha = 0.7f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color.White else Color.Transparent, shape)
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
    NavHost(nav, startDestination = Routes.HOME, modifier = modifier) {
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
