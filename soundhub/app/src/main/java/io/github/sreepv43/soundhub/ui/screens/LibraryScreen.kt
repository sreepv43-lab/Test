package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.FilterRow
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize

/** Downloaded music by album, sorted into the same format categories as search results. */
@Composable
fun LibraryScreen(onOpenAlbum: (Album) -> Unit) {
    val container = LocalContext.current.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(FormatFilter.ALL) }
    val albums = remember(tracks) { LibraryStore.albums(tracks) }
    val folder = remember { container.musicFolder().path }
    val counts = remember(albums) { FormatFilter.entries.associateWith { f -> albums.count { it.matches(f) } } }
    val shown = remember(albums, filter) { albums.filter { it.matches(filter) } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                "Library",
                if (tracks.isEmpty()) null else "${tracks.size} songs · ${formatSize(tracks.sumOf { it.size })} · in $folder",
            )
        }
        if (albums.isEmpty()) {
            item(key = "empty") {
                Note("Nothing here yet. Songs you play or download from Search are kept here. Press Left for the menu.")
            }
        } else {
            item(key = "filters") { FilterRow(filter, counts) { filter = it } }
        }
        items(shown, key = { it.key }) { album ->
            ListRow(onClick = { onOpenAlbum(album) }, key = album.key) {
                TwoLines(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · "), Modifier.weight(1f))
                album.tracks.map { it.info }.filter(filter::matches).groupBy { it.label }.maxByOrNull { it.value.size }
                    ?.value?.first()?.let { FormatBadge(it) }
            }
        }
        if (albums.isNotEmpty() && shown.isEmpty()) {
            item(key = "none") { Note("No ${filter.label} albums. Choose All to see everything.") }
        }
    }
}

@Composable
fun AlbumScreen(albumKey: String, onPlaying: () -> Unit, onGone: () -> Unit) {
    val container = LocalContext.current.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val album = remember(tracks, albumKey) { LibraryStore.albums(tracks).firstOrNull { it.key == albumKey } }
    var confirmDelete by remember { mutableStateOf(false) }
    if (album == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
            item { Note("This album is no longer in your library.") }
            item { ActionButton("Back", pageDefault = true, onClick = onGone) }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { ScreenTitle(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · ")) }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                ActionButton("Play album", Icons.Default.PlayArrow, pageDefault = true) {
                    container.playLibrary(album.tracks)
                    onPlaying()
                }
                ActionButton("Delete album", Icons.Default.Delete, primary = false) { confirmDelete = true }
            }
        }
        items(album.tracks, key = { it.id }) { track ->
            ListRow(
                onClick = {
                    container.playLibrary(album.tracks, track)
                    onPlaying()
                },
                key = track.id,
            ) {
                TwoLines(
                    listOfNotNull(track.trackNumber?.let { "$it." }, track.title).joinToString(" "),
                    listOfNotNull(
                        formatSize(track.size),
                        "from ${track.username}",
                        if (!track.info.playable) "can't be played on this device" else null,
                    ).joinToString(" · "),
                    Modifier.weight(1f),
                )
                FormatBadge(track.info)
                Text(
                    formatDuration(track.info.durationSec?.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmDelete) {
        TvDialog(
            title = "Delete ${album.title}?",
            onDismiss = { confirmDelete = false },
            // "Keep" first, so a quick OK never deletes anything.
            buttons = listOf(
                DialogButton("Keep", primary = true) { confirmDelete = false },
                DialogButton("Delete") {
                    confirmDelete = false
                    container.library.remove(album.tracks.map { it.id }, deleteFiles = true)
                    onGone()
                },
            ),
        ) {
            Note("The ${album.tracks.size} downloaded files are removed from this device.")
        }
    }
}
