package io.github.sreepv43.streamhub.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

private data class Section(val route: String, val pattern: String, val label: String, val icon: ImageVector) {
    val base get() = routeBase(pattern)
}

private val sections = listOf(
    Section(Routes.HOME, Routes.HOME, "Home", Icons.Default.Home),
    Section(Routes.SEARCH, Routes.SEARCH, "Search", Icons.Default.Search),
    Section(Routes.link(), Routes.LINK, "Open link", Icons.Default.Link),
    Section(Routes.DOWNLOADS, Routes.DOWNLOADS, "Downloads", Icons.Default.Download),
    Section(Routes.addons(), Routes.ADDONS, "Addons", Icons.Default.Extension),
    Section(Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

private val menuEntries = sections.map { MenuEntry(it.base, it.label, it.icon) }

internal fun routeBase(route: String) = route.substringBefore('?').substringBefore('/')

/** Side menu ([TvShell]) around the pages. */
@Composable
fun AppRoot(navRequest: String?, onNavRequestHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()

    LaunchedEffect(navRequest) {
        if (navRequest != null) {
            nav.navigate(navRequest) { launchSingleTop = true }
            onNavRequestHandled()
        }
    }

    // Pages opened from a section (details, streams…) keep that section highlighted.
    val current = entry?.destination?.route?.let(::routeBase)
    val section = sections.firstOrNull { it.base == current }
    var lastSection by rememberSaveable { mutableStateOf(Routes.HOME) }
    LaunchedEffect(section) { if (section != null) lastSection = section.base }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        TvShell(
            entries = menuEntries,
            selectedKey = section?.base ?: lastSection,
            pageKey = entry?.id,
            onSelect = { chosen ->
                val target = sections.first { it.base == chosen.key }
                nav.openSection(target.route, target.pattern)
            },
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        ) { pageModifier ->
            AppNavHost(nav, pageModifier)
        }
    }
}

/**
 * Opens a menu section. Home returns to the home page itself, dropping whatever was opened on top
 * of it; other sections go back to their page if it is open underneath, or replace everything
 * above Home. Choosing the page that is already showing does nothing.
 */
internal fun NavHostController.openSection(route: String, pattern: String, home: String = Routes.HOME) {
    if (currentDestination?.route == pattern) return
    if (popBackStack(pattern, inclusive = false)) return
    navigate(route) {
        popUpTo(home)
        launchSingleTop = true
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
