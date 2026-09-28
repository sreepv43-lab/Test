package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.audio.Quality
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.ArtistGenre
import io.github.sreepv43.soundhub.library.FamousArtist
import io.github.sreepv43.soundhub.library.FamousArtists
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.components.AlbumArt
import io.github.sreepv43.soundhub.ui.components.Chip
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.tvEnterAt
import io.github.sreepv43.soundhub.ui.components.tvFocus
import io.github.sreepv43.soundhub.ui.components.tvRow

/** What an artist search looks for; Dolby Atmos searches go to the Atmos & sound page. */
enum class ArtistSearch(val label: String, val filter: MusicFilter) {
    ALL("All music", MusicFilter()),
    LOSSLESS("Lossless", MusicFilter(quality = Quality.LOSSLESS)),
    HI_RES("Hi-Res", MusicFilter(quality = Quality.HI_RES)),
    ATMOS("Dolby Atmos", MusicFilter()),
}

/** Famous artists by genre: OK on one searches Soulseek for their music. */
@Composable
fun ArtistsScreen(onSearching: (Section) -> Unit) {
    val container = LocalContext.current.container
    var genre by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(ArtistSearch.ALL) }
    ArtistsLayout(
        genres = FamousArtists.genres,
        genre = genre,
        onGenre = { genre = it },
        mode = mode,
        onMode = { mode = it },
        onPick = { artist ->
            if (mode == ArtistSearch.ATMOS) {
                container.startSearch(artist.query, atmos = true)
                onSearching(Section.SOUND)
            } else {
                container.searchFilter.value = mode.filter
                container.startSearch(artist.query)
                onSearching(Section.SEARCH)
            }
        },
    )
}

@Composable
fun ArtistsLayout(
    genres: List<ArtistGenre>,
    genre: Int,
    onGenre: (Int) -> Unit,
    mode: ArtistSearch,
    onMode: (ArtistSearch) -> Unit,
    onPick: (FamousArtist) -> Unit,
) {
    val shown = genres[genre.coerceIn(genres.indices)]
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / CARD_WIDTH).toInt().coerceIn(2, 5)
        val rows = remember(shown, columns) { shown.artists.chunked(columns) }
        LazyColumn(
            Modifier.fillMaxSize().testTag("page-list"),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") { ScreenTitle("Artists", "Press OK on an artist to search Soulseek for their music") }
            item(key = "mode") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Note("Search for", modifier = Modifier.padding(end = 12.dp))
                    LazyRow(
                        Modifier.tvRow().tvEnterAt { "mode-${mode.name}" }.testTag("artist-mode"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(ArtistSearch.entries, key = { it.name }) { entry ->
                            Chip(entry.label, entry == mode, key = "mode-${entry.name}") { onMode(entry) }
                        }
                    }
                }
            }
            item(key = "genres") {
                LazyRow(
                    Modifier.tvRow().tvEnterAt { "genre-$genre" }.testTag("genres"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(genres, key = { _, it -> it.name }) { index, entry ->
                        Chip(entry.name, index == genre, key = "genre-$index") { onGenre(index) }
                    }
                }
            }
            items(rows, key = { row -> "row:${shown.name}:${row.first().name}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { artist -> ArtistCard(artist, Modifier.weight(1f)) { onPick(artist) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ArtistCard(artist: FamousArtist, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(72.dp)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape, scale = 1.04f, key = "artist:${artist.name}")
            .clip(shape)
            .background(if (focused) AppColors.rowFocused else AppColors.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("artist-${artist.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumArt(null, artist.name, 48.dp)
        Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val CARD_WIDTH = 190.dp
