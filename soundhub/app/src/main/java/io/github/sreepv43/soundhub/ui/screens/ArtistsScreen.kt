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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import io.github.sreepv43.soundhub.library.MyArtists
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumArt
import io.github.sreepv43.soundhub.ui.components.Chip
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup
import io.github.sreepv43.soundhub.ui.components.tvEnterAt
import io.github.sreepv43.soundhub.ui.components.tvFocus
import io.github.sreepv43.soundhub.ui.components.tvRow

/** What an artist search looks for; Dolby Atmos switches on the search's Dolby Atmos only. */
enum class ArtistSearch(val label: String, val filter: MusicFilter) {
    ALL("All music", MusicFilter()),
    LOSSLESS("Lossless", MusicFilter(quality = Quality.LOSSLESS)),
    HI_RES("Hi-Res", MusicFilter(quality = Quality.HI_RES)),
    ATMOS("Dolby Atmos", MusicFilter()),
}

private enum class MyArtistsDialog { ADD, REMOVE }

/**
 * Artists by genre and language (and the listener's own, first): OK on one searches Soulseek for
 * their music. The page starts on the first shipped genre until the listener has added artists.
 */
@Composable
fun ArtistsScreen(onSearching: (Section) -> Unit) {
    val container = LocalContext.current.container
    val mine by container.settings.myArtists.flow.collectAsStateWithLifecycle()
    val genres = remember(mine) { listOf(MyArtists.genre(mine)) + FamousArtists.genres }
    var genre by rememberSaveable { mutableIntStateOf(if (mine.isBlank()) 1 else 0) }
    var mode by rememberSaveable { mutableStateOf(ArtistSearch.ALL) }
    var dialog by remember { mutableStateOf<MyArtistsDialog?>(null) }
    ArtistsLayout(
        genres = genres,
        genre = genre,
        onGenre = { genre = it },
        mode = mode,
        onMode = { mode = it },
        onAdd = { dialog = MyArtistsDialog.ADD },
        onRemove = { dialog = MyArtistsDialog.REMOVE },
        onPick = { artist ->
            container.searchFilter.value = mode.filter
            container.startSearch(artist.query, atmos = mode == ArtistSearch.ATMOS)
            onSearching(Section.SEARCH)
        },
    )
    when (dialog) {
        MyArtistsDialog.ADD -> TextEntryDialog(
            title = "Add an artist to search for",
            initial = "",
            onDismiss = { dialog = null },
            onDone = { name ->
                container.settings.myArtists.set(MyArtists.add(mine, name))
                genre = 0
                dialog = null
            },
        )
        MyArtistsDialog.REMOVE -> OptionsDialog(
            title = "Remove an artist",
            subtitle = "Only from My artists; nothing is deleted.",
            onDismiss = { dialog = null },
            options = MyArtists.parse(mine).map { name ->
                Option("Remove $name", Icons.Default.Delete, destructive = true) {
                    container.settings.myArtists.set(MyArtists.remove(mine, name))
                }
            },
        )
        null -> Unit
    }
}

@Composable
fun ArtistsLayout(
    genres: List<ArtistGenre>,
    genre: Int,
    onGenre: (Int) -> Unit,
    mode: ArtistSearch,
    onMode: (ArtistSearch) -> Unit,
    onAdd: () -> Unit = {},
    onRemove: () -> Unit = {},
    onPick: (FamousArtist) -> Unit,
) {
    val shown = genres[genre.coerceIn(genres.indices)]
    val mineShown = shown.name == MyArtists.GENRE
    // The remote's own search measures a wide row from its middle, so from the chips on the left
    // Up/Down would skip the genre row for the artist straight above or below: point them at it.
    val modeRow = remember { FocusRequester() }
    val genreRow = remember { FocusRequester() }
    val addButton = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth - 48.dp) / CARD_WIDTH).toInt().coerceIn(2, 5)
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
                        Modifier.focusRequester(modeRow).tvRow().tvEnterAt { "mode-${mode.name}" }.testTag("artist-mode"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(ArtistSearch.entries, key = { it.name }) { entry ->
                            Chip(
                                entry.label,
                                entry == mode,
                                modifier = Modifier.focusProperties { down = genreRow },
                                key = "mode-${entry.name}",
                            ) { onMode(entry) }
                        }
                    }
                }
            }
            item(key = "genres") {
                LazyRow(
                    Modifier.focusRequester(genreRow).tvRow().tvEnterAt { "genre-$genre" }.testTag("genres"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(genres, key = { _, it -> it.name }) { index, entry ->
                        // The page starts here: one press down reaches the artists.
                        Chip(
                            entry.name,
                            index == genre,
                            modifier = Modifier.focusProperties {
                                up = modeRow
                                if (mineShown) down = addButton
                            },
                            key = "genre-$index",
                            pageDefault = index == genre,
                        ) { onGenre(index) }
                    }
                }
            }
            if (mineShown) {
                item(key = "mine") {
                    Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            "Add an artist",
                            Icons.Default.Add,
                            modifier = Modifier.focusRequester(addButton).focusProperties { up = genreRow },
                            onClick = onAdd,
                        )
                        if (shown.artists.isNotEmpty()) {
                            ActionButton(
                                "Remove an artist…",
                                Icons.Default.Delete,
                                primary = false,
                                modifier = Modifier.focusProperties { up = genreRow },
                                onClick = onRemove,
                            )
                        }
                    }
                }
                if (shown.artists.isEmpty()) {
                    item(key = "mine-empty") {
                        Note("Add the artists you like, as their name appears in file names (for Rahman, type Rahman). OK on one searches Soulseek for their music.")
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> "row:${shown.name}:${row.first().name}" }) { index, row ->
                val toGenres = if (index == 0) Modifier.focusProperties { up = if (mineShown) addButton else genreRow } else Modifier
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { artist -> ArtistCard(artist, Modifier.weight(1f).then(toGenres)) { onPick(artist) } }
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

private val CARD_WIDTH = 180.dp
