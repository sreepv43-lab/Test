package io.github.sreepv43.streamhub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.AppColors
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

/** The age rating ("PG-13", "R", "TV-MA") as a small outlined badge. */
@Composable
fun AgeRatingBadge(rating: String, modifier: Modifier = Modifier) {
    Text(
        rating,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = AppColors.text,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, AppColors.textDim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
