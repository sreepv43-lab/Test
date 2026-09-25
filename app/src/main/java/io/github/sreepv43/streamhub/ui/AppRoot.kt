package io.github.sreepv43.streamhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.sreepv43.streamhub.ui.components.TvPivotBringIntoViewSpec
import io.github.sreepv43.streamhub.ui.components.tvFocus
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

/** Side navigation rail + content: works with a TV remote (D-pad) and with touch on tablets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(navRequest: String?, onNavRequestHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(navRequest) {
        if (navRequest != null) {
            nav.navigate(navRequest) { launchSingleTop = true }
            onNavRequestHandled()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivotBringIntoViewSpec) {
            Row {
                SideRail(nav)
                AppNavHost(nav, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SideRail(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val currentBase = entry?.destination?.route?.substringBefore('?')?.substringBefore('/')
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.height(16.dp))
        sections.forEach { section ->
            NavigationRailItem(
                selected = currentBase == section.base,
                onClick = {
                    nav.navigate(section.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(section.icon, contentDescription = section.label) },
                label = { Text(section.label) },
                modifier = Modifier.tvFocus(),
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
