package io.github.sreepv43.streamhub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.sreepv43.streamhub.StreamHubApp
import io.github.sreepv43.streamhub.addon.TitleRatings

/**
 * [metaId]'s Rotten Tomatoes and other scores, asked for when first shown (null without an MDBList
 * key, for non-IMDb titles, and until they arrive).
 */
@Composable
fun rememberRatings(metaId: String): TitleRatings? {
    // (Previews and tests may run without the app's container.)
    val container = (LocalContext.current.applicationContext as? StreamHubApp)?.container ?: return null
    val store = container.ratings
    val key by container.settings.ratingsKey.flow.collectAsState()
    if (key.isBlank()) return null
    LaunchedEffect(metaId, key) { store.want(metaId) }
    val ratings by remember(metaId) { store.ratingsFor(metaId) }.collectAsState(store.cached(metaId))
    return ratings
}

/** "🍅 83%" under a poster, when shown on posters (Settings) and known. */
@Composable
fun posterRating(metaId: String): String? {
    val container = (LocalContext.current.applicationContext as? StreamHubApp)?.container ?: return null
    val onPosters by container.settings.ratingsOnPosters.flow.collectAsState()
    if (!onPosters) return null
    return rememberRatings(metaId)?.tomatoes?.let { "🍅 $it%" }
}

/** The scores a title page shows next to IMDb's: "🍅 83%", "🍿 90%", "Metacritic 74". */
fun ratingFacts(ratings: TitleRatings?): List<String> = listOfNotNull(
    ratings?.tomatoes?.let { "🍅 $it%" },
    ratings?.popcorn?.let { "🍿 $it%" },
    ratings?.metacritic?.let { "Metacritic $it" },
)
