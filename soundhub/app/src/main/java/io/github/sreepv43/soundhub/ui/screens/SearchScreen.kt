package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchSession
import io.github.sreepv43.soundhub.library.SearchTrack
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.FilterRow
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.RecentSearches
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SearchBar
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.formatSpeed
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.transferText

@Composable
fun SearchScreen(onOpen: (SearchFolder) -> Unit) {
    val container = LocalContext.current.container
    val session = container.search
    val folders by session.folders.collectAsStateWithLifecycle()
    val query by session.query.collectAsStateWithLifecycle()
    val recent by container.settings.recentSearches.flow.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(FormatFilter.ALL) }
    SearchLayout(
        title = "Search Soulseek",
        hint = "Artist, album or song",
        query = query,
        status = searchStatus(session, folders.size),
        folders = folders,
        filter = filter,
        onFilter = { filter = it },
        recent = remember(recent) { recent.lines().filter { it.isNotBlank() } },
        onSearch = { container.startSearch(it) },
        onOpen = onOpen,
    )
}

/**
 * A search page: the search box (a button until pressed, so the remote never pops up the keyboard
 * by passing over it), recent searches, format chips and the albums found. [filter] null hides
 * the chips (the Atmos page shows only Atmos albums).
 */
@Composable
fun SearchLayout(
    title: String,
    hint: String,
    query: String,
    status: String?,
    folders: List<SearchFolder>,
    filter: FormatFilter?,
    onFilter: (FormatFilter) -> Unit,
    recent: List<String>,
    onSearch: (String) -> Unit,
    onOpen: (SearchFolder) -> Unit,
    top: LazyListScope.() -> Unit = {},
    bottom: LazyListScope.() -> Unit = {},
) {
    val counts = remember(folders) { FormatFilter.entries.associateWith { f -> folders.count { it.matches(f) } } }
    val shown = remember(folders, filter) { if (filter == null) folders else folders.filter { it.matches(filter) } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { ScreenTitle(title) }
        top()
        item(key = "search") { SearchBar(query, hint, onSearch) }
        if (recent.isNotEmpty()) item(key = "recent") { RecentSearches(recent, onSearch) }
        if (status != null) item(key = "status") { Note(status) }
        if (filter != null && folders.isNotEmpty()) item(key = "filters") { FilterRow(filter, counts, onFilter) }
        folderItems(shown, filter ?: FormatFilter.ALL, onOpen)
        if (filter != null && folders.isNotEmpty() && shown.isEmpty()) {
            item(key = "none") { Note("No ${filter.label} albums in these results. Choose All to see everything.") }
        }
        bottom()
    }
}

@Composable
fun searchStatus(session: SearchSession, count: Int): String? {
    val container = LocalContext.current.container
    val state by container.client.state.collectAsStateWithLifecycle()
    val searching by session.searching.collectAsStateWithLifecycle()
    val query by session.query.collectAsStateWithLifecycle()
    return when (val s = state) {
        is ConnectionState.Failed -> "Not connected: ${s.message}. Open Settings from the menu (press Left)."
        ConnectionState.Disconnected -> "Sign in to Soulseek first: press Left for the menu, then Settings."
        ConnectionState.Connecting -> "Connecting to Soulseek…"
        is ConnectionState.Connected -> when {
            query.isEmpty() -> "Press OK on the search box to type, or pick a recent search."
            searching && count == 0 -> "Searching for \"$query\"… answers arrive from other users over the next minute."
            searching -> "$count albums so far for \"$query\", more arriving (new ones are added at the end)…"
            count == 0 -> "Nothing found for \"$query\". Try fewer or different words."
            else -> "$count albums for \"$query\"."
        }
    }
}

/** One row per album: title, artist, size, the main format and how soon the user can send it. */
fun LazyListScope.folderItems(folders: List<SearchFolder>, filter: FormatFilter, onOpen: (SearchFolder) -> Unit) {
    items(folders, key = { it.key }) { folder ->
        ListRow(onClick = { onOpen(folder) }, key = folder.key, modifier = Modifier.testTag("folder-${folder.album}")) {
            TwoLines(
                folder.album,
                listOfNotNull(folder.artist, "${folder.tracks.size} songs", formatSize(folder.totalSize), "from ${folder.username}")
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
        if (folder.slotFree) Badge("Starts now", LosslessColor) else Badge("Queue ${folder.queueLength}", WarningColor)
        val speed = formatSpeed(folder.avgSpeed.toLong())
        if (speed.isNotEmpty()) {
            Text(speed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An album from the search results: play from any song (it streams while it downloads) or download it all. */
@Composable
fun FolderScreen(folder: SearchFolder, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val inLibrary = remember(library) { library.map { it.id }.toSet() }
    FolderLayout(
        folder = folder,
        status = { track ->
            val transfer = transfers.lastOrNull { it.username == track.username && it.filename == track.file.filename }
            when {
                LibraryStore.id(track.username, track.file.filename) in inLibrary -> "In your library"
                transfer != null -> transferText(transfer)
                !track.info.playable -> "Download only: this format can't be played here"
                else -> null
            }
        },
        onPlay = { track ->
            if (container.requireSignIn()) {
                container.playFolder(folder, track)
                onPlaying()
            }
        },
        onDownload = { tracks ->
            if (container.requireSignIn()) {
                container.download(tracks)
                toast(context, "Downloading ${tracks.size} song${if (tracks.size == 1) "" else "s"}. Progress is under Downloads in the menu.")
            }
        },
    )
}

@Composable
fun FolderLayout(
    folder: SearchFolder,
    status: (SearchTrack) -> String?,
    onPlay: (SearchTrack) -> Unit,
    onDownload: (List<SearchTrack>) -> Unit,
) {
    val playable = folder.tracks.filter { it.info.playable }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                folder.album,
                listOfNotNull(
                    folder.artist,
                    "from ${folder.username}",
                    if (folder.slotFree) "starts right away" else "${folder.queueLength} waiting in their queue",
                    formatSpeed(folder.avgSpeed.toLong()).ifEmpty { null },
                ).joinToString(" · "),
            )
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                if (playable.isNotEmpty()) {
                    ActionButton("Play album", Icons.Default.PlayArrow, pageDefault = true) { onPlay(playable.first()) }
                }
                ActionButton("Download album", Icons.Default.Download, primary = playable.isEmpty()) { onDownload(folder.tracks) }
            }
        }
        items(folder.tracks, key = { it.file.filename }) { track ->
            ListRow(
                onClick = { if (track.info.playable) onPlay(track) else onDownload(listOf(track)) },
                key = track.file.filename,
                modifier = Modifier.testTag("track-${track.name.title}"),
            ) {
                TwoLines(
                    listOfNotNull(track.name.trackNumber?.let { "$it." }, track.name.title).joinToString(" "),
                    listOfNotNull(status(track), formatSize(track.file.size)).joinToString(" · "),
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
    }
}
