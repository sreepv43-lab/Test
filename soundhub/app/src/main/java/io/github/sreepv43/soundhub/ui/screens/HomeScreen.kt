package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.data.UpdateState
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryViews
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.fullBleed
import io.github.sreepv43.soundhub.ui.rememberPageListState
import io.github.sreepv43.soundhub.ui.revealTopWhenFocused
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCard
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup
import io.github.sreepv43.soundhub.ui.components.tvRow

/**
 * Where listening starts: carry on with what was playing (or resume the last session; nothing
 * plays by itself), then recently played, favourite and recently added albums.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenAlbum: (String) -> Unit,
    onGo: (Section) -> Unit,
    onSignIn: () -> Unit,
    onPlaying: () -> Unit,
    onUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val current by container.playback.current.collectAsStateWithLifecycle()
    val state by container.client.state.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    val update by container.updater.state.collectAsStateWithLifecycle()
    val albums = remember(tracks) { LibraryStore.albums(tracks) }
    val recentlyPlayed = remember(collection.history, albums) { LibraryViews.recentlyPlayed(collection.history, albums) }
    val favourites = remember(collection.favouriteAlbums, albums) { albums.filter { it.key in collection.favouriteAlbums } }
    val favouriteSongs = remember(collection.favouriteTracks, tracks) { tracks.filter { it.id in collection.favouriteTracks } }
    val resumeTrack = collection.session?.let { session ->
        session.trackIds.getOrNull(session.index)?.let(container.library::get)?.takeIf { it.id !in missing }
    }
    val signedIn = state is ConnectionState.Connected
    val playing = current
    // What Home leads with: the song playing, else the last session's song.
    val hero = playing?.let { Hero(it.title, it.artist, it.album, it.albumKey, now = true) }
        ?: resumeTrack?.let { Hero(it.title, it.artist, it.album, LibraryViews.albumKeyOf(it.path), now = false) }

    val listState = rememberPageListState()
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            // Who is signed in is under Settings → Account; Home only speaks up when something needs doing.
            ScreenTitle(
                "SoundHub",
                if (state is ConnectionState.Connecting) "Connecting to Soulseek…" else "${tracks.size} songs in your library",
            )
        }
        (update as? UpdateState.Available)?.let { available ->
            item(key = "update") {
                Row(Modifier.revealTopWhenFocused(listState).tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(
                        "A new SoundHub is ready: build ${available.update.build}" +
                            available.update.notes.lineSequence().firstOrNull { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        LosslessColor,
                        Modifier.weight(1f),
                    )
                    ActionButton("Update", Icons.Default.SystemUpdate, primary = false, onClick = onUpdate)
                }
            }
        }
        if (!signedIn && state !is ConnectionState.Connecting) {
            item(key = "sign-in") {
                Row(Modifier.revealTopWhenFocused(listState).tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(
                        (state as? ConnectionState.Failed)?.let { "Not connected to Soulseek: ${it.message}" }
                            ?: "Sign in to Soulseek to search and stream music. Your library plays without it.",
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton("Sign in", pageDefault = hero == null, onClick = onSignIn)
                }
            }
        }
        if (hero != null) {
            item(key = "hero") {
                Row(
                    Modifier.fillMaxWidth().revealTopWhenFocused(listState).clip(RoundedCornerShape(20.dp)).background(AppColors.surfaceVariant).padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumCover(hero.albumKey, hero.album, 128.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "CONTINUE LISTENING",
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 1.6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(hero.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        hero.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        FlowRow(
                            Modifier.tvButtonGroup().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (hero.now) {
                                ActionButton("Now playing", Icons.Default.GraphicEq, pageDefault = true, onClick = onPlaying)
                            } else {
                                ActionButton("Resume", Icons.Default.PlayArrow, pageDefault = true) {
                                    if (container.resume()) onPlaying() else toast(context, "Those songs aren't available (is their drive connected?)")
                                }
                            }
                            if (tracks.isNotEmpty()) {
                                ActionButton("Shuffle library", Icons.Default.Shuffle, primary = false) {
                                    if (playOrExplain(context, container, tracks.filter { it.id !in missing }, shuffle = true)) onPlaying()
                                }
                            }
                        }
                    }
                }
            }
        }
        // The menu already leads to Search, Artists and the Library, so these are only for a start
        // with nothing to continue, and for the favourites.
        val starting = hero == null
        if (starting || favouriteSongs.isNotEmpty()) {
            item(key = "actions") {
                FlowRow(Modifier.revealTopWhenFocused(listState).tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (starting) {
                        ActionButton("Search", Icons.Default.Search, pageDefault = signedIn) { onGo(Section.SEARCH) }
                        ActionButton("Browse artists", Icons.Default.Star, primary = false) { onGo(Section.ARTISTS) }
                        if (tracks.isNotEmpty()) {
                            ActionButton("Shuffle library", Icons.Default.Shuffle, primary = false) {
                                if (playOrExplain(context, container, tracks.filter { it.id !in missing }, shuffle = true)) onPlaying()
                            }
                        }
                    }
                    if (favouriteSongs.isNotEmpty()) {
                        ActionButton("Favourite songs", Icons.Default.Favorite, primary = false) {
                            if (playOrExplain(context, container, favouriteSongs.filter { it.id !in missing }, shuffle = true)) onPlaying()
                        }
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

/** What the Continue listening card shows. [now] is true when it is playing, false for the last session. */
private class Hero(val title: String, val artist: String?, val album: String, val albumKey: String?, val now: Boolean)

/** A row of album tiles; Left from the first opens the menu. */
private fun LazyListScope.shelf(title: String, key: String, albums: List<Album>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "$key-title") { SectionHeader(title) }
    item(key = key) {
        LazyRow(
            Modifier.fullBleed().tvRow().focusGroup().testTag("shelf-$key"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        ) {
            items(albums, key = { it.key }) { album ->
                AlbumCard(album.title, album.artist, album.key, key = "$key:${album.key}") { onOpen(album.key) }
            }
        }
    }
}

private const val SHELF_SIZE = 12
