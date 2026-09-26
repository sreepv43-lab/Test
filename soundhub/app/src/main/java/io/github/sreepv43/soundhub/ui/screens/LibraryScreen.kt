package io.github.sreepv43.soundhub.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.FilterRow
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.tvFocus

/** Downloaded music by album, sorted into the same format categories as search results. */
@Composable
fun LibraryScreen(onPlaying: () -> Unit) {
    val container = LocalContext.current.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(FormatFilter.ALL) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val albums = remember(tracks) { LibraryStore.albums(tracks) }
    val folder = remember { container.musicFolder().path }

    albums.firstOrNull { it.key == openKey }?.let { album ->
        AlbumScreen(album, onBack = { openKey = null }, onPlaying = onPlaying)
        return
    }
    val counts = remember(albums) { FormatFilter.entries.associateWith { f -> albums.count { it.matches(f) } } }
    val shown = remember(albums, filter) { albums.filter { it.matches(filter) } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle(
                "Library",
                if (tracks.isEmpty()) "Songs you play or download from Search are kept here"
                else "${tracks.size} songs · ${formatSize(tracks.sumOf { it.size })} · in $folder",
            )
        }
        if (albums.isNotEmpty()) item { FilterRow(filter, counts) { filter = it } }
        items(shown, key = { it.key }) { album ->
            ListRow(onClick = { openKey = album.key }) {
                TwoLines(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · "), Modifier.weight(1f))
                album.tracks.map { it.info }.filter(filter::matches).groupBy { it.label }.maxByOrNull { it.value.size }
                    ?.value?.first()?.let { FormatBadge(it) }
            }
        }
    }
}

@Composable
private fun AlbumScreen(album: Album, onBack: () -> Unit, onPlaying: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = LocalContext.current.container
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · ")) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                ActionButton("Play album", Icons.Default.PlayArrow) {
                    container.playLibrary(album.tracks)
                    onPlaying()
                }
                ActionButton("Delete album", Icons.Default.Delete, primary = false) { confirmDelete = true }
            }
        }
        items(album.tracks, key = { it.id }) { track ->
            ListRow(onClick = {
                container.playLibrary(album.tracks, track)
                onPlaying()
            }) {
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${album.title}?") },
            text = { Text("The ${album.tracks.size} downloaded files are removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    container.library.remove(album.tracks.map { it.id }, deleteFiles = true)
                    onBack()
                }, modifier = Modifier.tvFocus()) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.tvFocus()) { Text("Keep") }
            },
        )
    }
}
