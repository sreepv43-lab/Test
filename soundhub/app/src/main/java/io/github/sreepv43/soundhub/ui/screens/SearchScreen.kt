package io.github.sreepv43.soundhub.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchSession
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.FilterRow
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.formatSpeed
import io.github.sreepv43.soundhub.ui.components.transferText
import io.github.sreepv43.soundhub.ui.components.tvFocus

@Composable
fun SearchScreen(onPlaying: () -> Unit) {
    val container = LocalContext.current.container
    val session = container.search
    val folders by session.folders.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(FormatFilter.ALL) }
    var open by remember { mutableStateOf<SearchFolder?>(null) }

    open?.let { folder ->
        FolderScreen(folder, onBack = { open = null }, onPlaying = onPlaying)
        return
    }
    val counts = remember(folders) { FormatFilter.entries.associateWith { f -> folders.count { it.matches(f) } } }
    val shown = remember(folders, filter) { folders.filter { it.matches(filter) } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle("Search Soulseek", "Find albums and songs, then sort them by format") }
        item { SearchBox(session, hint = "Artist, album or song") { text -> session.start(text) } }
        item { SearchStatus(session, shown.size) }
        if (folders.isNotEmpty()) item { FilterRow(filter, counts) { filter = it } }
        folderItems(shown, filter) { open = it }
    }
}

@Composable
fun SearchBox(session: SearchSession, hint: String, onSearch: (String) -> Unit) {
    val current by session.query.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(current) }
    val submit = { if (text.isNotBlank()) onSearch(text.trim()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text(hint) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.weight(1f).tvFocus(RoundedCornerShape(6.dp), scale = 1f),
        )
        ActionButton("Search", Icons.Default.Search, onClick = submit)
    }
}

@Composable
fun SearchStatus(session: SearchSession, shown: Int) {
    val container = LocalContext.current.container
    val state by container.client.state.collectAsStateWithLifecycle()
    val searching by session.searching.collectAsStateWithLifecycle()
    val query by session.query.collectAsStateWithLifecycle()
    val text = when (val s = state) {
        is ConnectionState.Failed -> "Not connected: ${s.message}. See Settings."
        ConnectionState.Disconnected -> "Sign in to Soulseek in Settings to search."
        ConnectionState.Connecting -> "Connecting to Soulseek…"
        is ConnectionState.Connected -> when {
            query.isEmpty() -> null
            searching && shown == 0 -> "Searching… answers arrive from other users over the next minute"
            searching -> "$shown folders so far, more arriving…"
            shown == 0 -> "Nothing found. Try fewer or different words."
            else -> "$shown folders"
        }
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One row per folder: album, artist, size, the main format and how soon the user can send it. */
fun LazyListScope.folderItems(folders: List<SearchFolder>, filter: FormatFilter, onOpen: (SearchFolder) -> Unit) {
    items(folders, key = { it.key }) { folder ->
        ListRow(onClick = { onOpen(folder) }) {
            TwoLines(
                folder.album,
                listOfNotNull(folder.artist, "${folder.tracks.size} songs", formatSize(folder.totalSize), folder.username)
                    .joinToString(" · "),
                Modifier.weight(1f),
            )
            folder.summary(filter)?.let { FormatBadge(it) }
            Availability(folder)
        }
    }
}

@Composable
private fun Availability(folder: SearchFolder) {
    Column(horizontalAlignment = Alignment.End) {
        if (folder.slotFree) Badge("Free slot", LosslessColor) else Badge("Queue ${folder.queueLength}", WarningColor)
        val speed = formatSpeed(folder.avgSpeed.toLong())
        if (speed.isNotEmpty()) {
            Text(speed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A search result folder: play from any song (it streams while it downloads) or download it all. */
@Composable
fun FolderScreen(folder: SearchFolder, onBack: () -> Unit, onPlaying: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = LocalContext.current.container
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val inLibrary = remember(library) { library.map { it.id }.toSet() }
    val playable = folder.tracks.filter { it.info.playable }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle(
                folder.album,
                listOfNotNull(
                    folder.artist,
                    "from ${folder.username}",
                    if (folder.slotFree) "free upload slot" else "${folder.queueLength} in their queue",
                    formatSpeed(folder.avgSpeed.toLong()).ifEmpty { null },
                ).joinToString(" · "),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                if (playable.isNotEmpty()) {
                    ActionButton("Play album", Icons.Default.PlayArrow) {
                        container.playFolder(folder, playable.first())
                        onPlaying()
                    }
                }
                ActionButton("Download album", Icons.Default.Download, primary = false) { container.download(folder.tracks) }
            }
        }
        items(folder.tracks, key = { it.file.filename }) { track ->
            val transfer = transfers.lastOrNull { it.username == track.username && it.filename == track.file.filename }
            val saved = LibraryStore.id(track.username, track.file.filename) in inLibrary
            val status = when {
                saved -> "In your library"
                transfer != null -> transferText(transfer)
                !track.info.playable -> "Download only: this format can't be played here"
                else -> null
            }
            ListRow(onClick = {
                if (track.info.playable) {
                    container.playFolder(folder, track)
                    onPlaying()
                } else {
                    container.download(listOf(track))
                }
            }) {
                TwoLines(
                    listOfNotNull(track.name.trackNumber?.let { "$it." }, track.name.title).joinToString(" "),
                    listOfNotNull(status, formatSize(track.file.size)).joinToString(" · "),
                    Modifier.weight(1f),
                )
                FormatBadge(track.info)
                Text(
                    formatDuration(track.file.durationSec?.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text(
                "Songs stream while they download and stay in your library afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
