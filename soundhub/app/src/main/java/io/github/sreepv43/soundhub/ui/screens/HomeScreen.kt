package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryViews
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCard
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.tvRow
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup

/**
 * Where listening starts: carry on with what was playing (or resume the last session; nothing
 * plays by itself), then recently played, favourite and recently added albums.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenAlbum: (String) -> Unit, onGo: (Section) -> Unit, onSignIn: () -> Unit, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val current by container.playback.current.collectAsStateWithLifecycle()
    val state by container.client.state.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    val albums = remember(tracks) { LibraryStore.albums(tracks) }
    val recentlyPlayed = remember(collection.history, albums) { LibraryViews.recentlyPlayed(collection.history, albums) }
    val favourites = remember(collection.favouriteAlbums, albums) { albums.filter { it.key in collection.favouriteAlbums } }
    val favouriteSongs = remember(collection.favouriteTracks, tracks) { tracks.filter { it.id in collection.favouriteTracks } }
    val resumeTrack = collection.session?.let { session ->
        session.trackIds.getOrNull(session.index)?.let(container.library::get)?.takeIf { it.id !in missing }
    }
    val signedIn = state is ConnectionState.Connected
    val playing = current

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                "SoundHub",
                when (val s = state) {
                    is ConnectionState.Connected -> "Signed in to Soulseek as ${s.username} · ${tracks.size} songs in your library"
                    ConnectionState.Connecting -> "Connecting to Soulseek…"
                    else -> "${tracks.size} songs in your library"
                },
            )
        }
        if (!signedIn && state !is ConnectionState.Connecting) {
            item(key = "sign-in") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(
                        (state as? ConnectionState.Failed)?.let { "Not connected to Soulseek: ${it.message}" }
                            ?: "Sign in to Soulseek to search and stream music. Your library plays without it.",
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton("Sign in", pageDefault = playing == null && resumeTrack == null, onClick = onSignIn)
                }
            }
        }
        item(key = "actions") {
            FlowRow(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    playing != null -> ActionButton("Now playing: ${playing.title}", Icons.Default.GraphicEq, pageDefault = true, onClick = onPlaying)
                    resumeTrack != null -> ActionButton("Resume: ${resumeTrack.title}", Icons.Default.PlayArrow, pageDefault = true) {
                        if (container.resume()) onPlaying() else toast(context, "Those songs aren't available (is their drive connected?)")
                    }
                }
                ActionButton(
                    "Search",
                    Icons.Default.Search,
                    primary = playing == null && resumeTrack == null,
                    pageDefault = signedIn && playing == null && resumeTrack == null,
                ) { onGo(Section.SEARCH) }
                if (tracks.isNotEmpty()) {
                    ActionButton("Shuffle library", Icons.Default.Shuffle, primary = false) {
                        if (playOrExplain(context, container, tracks.filter { it.id !in missing }, shuffle = true)) onPlaying()
                    }
                    ActionButton("Library", Icons.Default.LibraryMusic, primary = false) { onGo(Section.LIBRARY) }
                }
                if (favouriteSongs.isNotEmpty()) {
                    ActionButton("Favourite songs", Icons.Default.Favorite, primary = false) {
                        if (playOrExplain(context, container, favouriteSongs.filter { it.id !in missing }, shuffle = true)) onPlaying()
                    }
                }
            }
        }
        shelf("Recently played", "played", recentlyPlayed, onOpenAlbum)
        shelf("Favourite albums", "favourites", favourites, onOpenAlbum)
        shelf("Recently added", "added", albums.take(SHELF_SIZE), onOpenAlbum)
        if (albums.isEmpty()) {
            item(key = "empty") {
                Note(
                    "Your library is empty. Search Soulseek, then play or download an album: its songs are kept " +
                        "here, so they play instantly next time.",
                )
            }
        }
    }
}

/** A row of album tiles; Left from the first opens the menu. */
private fun LazyListScope.shelf(title: String, key: String, albums: List<Album>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "$key-title") { SectionHeader(title) }
    item(key = key) {
        LazyRow(
            Modifier.tvRow().focusGroup().testTag("shelf-$key"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(albums, key = { it.key }) { album ->
                AlbumCard(album.title, album.artist, album.key, key = "$key:${album.key}") { onOpen(album.key) }
            }
        }
    }
}

private const val SHELF_SIZE = 12
