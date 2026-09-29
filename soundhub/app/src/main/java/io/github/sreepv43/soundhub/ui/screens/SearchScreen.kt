package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import io.github.sreepv43.soundhub.audio.Channels
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.Release
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchSession
import io.github.sreepv43.soundhub.library.SearchTrack
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumArt
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.FileTypeFilter
import io.github.sreepv43.soundhub.ui.components.FilterDialog
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.QualityFilter
import io.github.sreepv43.soundhub.ui.components.RecentSearches
import io.github.sreepv43.soundhub.ui.components.RowWithMore
import io.github.sreepv43.soundhub.ui.components.SearchBar
import io.github.sreepv43.soundhub.ui.components.ToggleRow
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.formatSpeed
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.transferText
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup

/** A status line under the search box, with a button when there is something to do about it. */
data class SearchStatus(val text: String?, val action: DialogButton? = null)

@Composable
fun SearchScreen(onOpen: (Release) -> Unit, onSignIn: () -> Unit) {
    val container = LocalContext.current.container
    val releases by container.releases.collectAsStateWithLifecycle()
    val text by container.searchText.collectAsStateWithLifecycle()
    val atmos by container.searchAtmos.collectAsStateWithLifecycle()
    val recent by container.settings.recentSearches.flow.collectAsStateWithLifecycle()
    val filter by container.searchFilter.collectAsStateWithLifecycle()
    SearchLayout(
        hint = "Search songs, artists, albums…",
        query = text,
        status = searchStatus(container.search, releases.size, onSignIn),
        releases = releases,
        filter = filter,
        onFilter = { container.searchFilter.value = it },
        atmos = atmos,
        onAtmos = container::setSearchAtmos,
        recent = remember(recent) { recent.lines().filter { it.isNotBlank() } },
        onSearch = { container.startSearch(it) },
        onOpen = onOpen,
    )
}

/**
 * The search: the search box (a button until pressed, so passing over it never pops up the
 * keyboard), the Dolby Atmos only switch (adds "atmos" to the search and shows only Atmos files),
 * recent searches, file type and quality chips and the albums found, one row per album however
 * many users have it.
 */
@Composable
fun SearchLayout(
    hint: String,
    query: String,
    status: SearchStatus,
    releases: List<Release>,
    filter: MusicFilter,
    onFilter: (MusicFilter) -> Unit,
    atmos: Boolean,
    onAtmos: (Boolean) -> Unit,
    recent: List<String>,
    onSearch: (String) -> Unit,
    onOpen: (Release) -> Unit,
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val active = if (atmos) filter.copy(channels = Channels.ATMOS) else filter
    val counts = remember(releases) { HashMap<MusicFilter, Int>() }
    val count = { f: MusicFilter -> counts.getOrPut(f) { releases.count { it.matches(f) } } }
    val shown = remember(releases, active) { releases.filter { it.matches(active) } }
    // Channels (other than the switch's Atmos) and availability live under More filters.
    val more = MusicFilter(channels = filter.channels, freeSlotOnly = filter.freeSlotOnly).takeUnless { it.isDefault }?.label
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "search") { SearchBar(query, hint, onSearch) }
        item(key = "atmos") {
            ToggleRow(
                "Dolby Atmos only",
                if (atmos) "Searching with \"atmos\" added, showing Atmos files only" else "Adds \"atmos\" to the search and shows only Atmos files",
                checked = atmos,
                key = "atmos-switch",
                icon = Icons.Default.SurroundSound,
                onToggle = onAtmos,
            )
        }
        if (recent.isNotEmpty()) item(key = "recent") { RecentSearches(recent, onSearch) }
        if (status.text != null) {
            item(key = "status") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(status.text, modifier = Modifier.weight(1f))
                    status.action?.let { ActionButton(it.text, primary = it.primary, onClick = it.onClick) }
                }
            }
        }
        if (releases.isNotEmpty()) {
            item(key = "types") {
                FileTypeFilter(filter.codec, count = { count(active.copy(codec = it)) }) { onFilter(filter.copy(codec = it)) }
            }
            item(key = "qualities") {
                QualityFilter(
                    filter.quality,
                    count = { count(active.copy(quality = it)) },
                    more = more,
                    onSelect = { onFilter(filter.copy(quality = it)) },
                    onMore = { showFilters = true },
                )
            }
        }
        releaseItems(shown, active, onOpen)
        if (releases.isNotEmpty() && shown.isEmpty()) {
            item(key = "none") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note("No albums here match ${active.label}.", modifier = Modifier.weight(1f))
                    ActionButton("Show all results") { if (!filter.isDefault) onFilter(MusicFilter()) else onAtmos(false) }
                }
            }
        }
    }
    if (showFilters) {
        FilterDialog(filter, showAvailability = true, onChange = onFilter, onDismiss = { showFilters = false })
    }
}

@Composable
fun searchStatus(session: SearchSession, count: Int, onSignIn: () -> Unit): SearchStatus {
    val container = LocalContext.current.container
    val state by container.client.state.collectAsStateWithLifecycle()
    val searching by session.searching.collectAsStateWithLifecycle()
    val query by session.query.collectAsStateWithLifecycle()
    val signIn = DialogButton("Sign in", primary = true, onClick = onSignIn)
    return when (val s = state) {
        is ConnectionState.Failed -> SearchStatus("Not connected to Soulseek: ${s.message}", signIn)
        ConnectionState.Disconnected -> SearchStatus("Sign in to Soulseek to search.", signIn)
        ConnectionState.Connecting -> SearchStatus("Connecting to Soulseek…")
        is ConnectionState.Connected -> SearchStatus(
            when {
                query.isEmpty() -> "Press OK on the search box to type, pick a recent search, or choose Artists in the menu."
                searching && count == 0 -> "Searching for \"$query\"… answers arrive from other users over the next minute."
                searching -> "$count albums so far for \"$query\", more arriving (new ones are added at the end)…"
                count == 0 -> "Nothing found for \"$query\". Try fewer or different words."
                else -> "$count albums for \"$query\"."
            },
        )
    }
}

/** One row per album: artwork, title, artist, songs, how many users have it, best format and availability. */
fun LazyListScope.releaseItems(releases: List<Release>, filter: MusicFilter, onOpen: (Release) -> Unit) {
    items(releases, key = { it.key }) { release ->
        val best = release.bestSource(filter)
        ListRow(onClick = { onOpen(release) }, key = release.key, modifier = Modifier.testTag("release-${release.album}")) {
            AlbumArt(null, release.album, 52.dp)
            TwoLines(
                release.album,
                listOfNotNull(
                    release.artist,
                    "${release.trackCount} songs",
                    if (release.sources.size > 1) "${release.sources.size} sources" else "from ${best.username}",
                    formatSpeed(best.avgSpeed.toLong()).ifEmpty { null },
                ).joinToString(" · "),
                Modifier.weight(1f),
            ) {
                best.summary(filter)?.let { FormatBadge(it) }
                if (best.slotFree) Badge("Slot free", LosslessColor) else Badge("Queue ${best.queueLength}", WarningColor)
            }
        }
    }
}

/**
 * An album found by a search: plays or downloads from one user's copy (the best one, or the one
 * picked under Sources). Playing streams while it downloads and keeps the songs in the library.
 */
@Composable
fun ReleaseScreen(release: Release, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val live by container.releases.collectAsStateWithLifecycle()
    val searchFilter by container.searchFilter.collectAsStateWithLifecycle()
    val atmos by container.searchAtmos.collectAsStateWithLifecycle()
    val filter = if (atmos) searchFilter.copy(channels = Channels.ATMOS) else searchFilter
    // More users' copies keep arriving while the search runs.
    val current = live.firstOrNull { it.key == release.key } ?: release
    var sourceKey by rememberSaveable(release.key) { mutableStateOf<String?>(null) }
    val chosen = current.sources.firstOrNull { it.key == sourceKey } ?: current.bestSource(filter)
    SideEffect { if (sourceKey == null) sourceKey = chosen.key }
    // Only the files the search's filters let through (e.g. just the Atmos ones), when there are any.
    val source = remember(chosen, filter) {
        val matching = chosen.tracks.filter { filter.matches(it.info) }
        if (matching.isEmpty() || matching.size == chosen.tracks.size) chosen else chosen.copy(tracks = matching)
    }
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val inLibrary = remember(library) { library.mapTo(HashSet()) { it.id } }
    fun added(count: Int, next: Boolean) {
        if (count > 0) toast(context, if (next) "Playing next" else "Added $count song${if (count == 1) "" else "s"} to the queue")
    }
    ReleaseLayout(
        release = current,
        source = source,
        filter = filter,
        hidden = chosen.tracks.size - source.tracks.size,
        status = { track ->
            val transfer = transfers.lastOrNull { it.username == track.username && it.filename == track.file.filename }
            when {
                LibraryStore.id(track.username, track.file.filename) in inLibrary -> "In your library"
                transfer != null -> transferText(transfer)
                !track.info.playable -> "Download only: this device can't play ${track.info.label}"
                else -> null
            }
        },
        onPlay = { track, shuffle -> if (container.playFolder(source, track, shuffle)) onPlaying() },
        onEnqueue = { tracks, next -> added(container.enqueue(source, tracks, next), next) },
        onDownload = { tracks ->
            if (container.requireSignIn()) {
                container.download(source, tracks)
                toast(context, "Downloading ${tracks.size} song${if (tracks.size == 1) "" else "s"}. Progress is under Transfers.")
            }
        },
        onChooseSource = { sourceKey = it.key },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReleaseLayout(
    release: Release,
    source: SearchFolder,
    filter: MusicFilter,
    status: (SearchTrack) -> String?,
    onPlay: (SearchTrack, Boolean) -> Unit,
    onEnqueue: (List<SearchTrack>, Boolean) -> Unit,
    onDownload: (List<SearchTrack>) -> Unit,
    onChooseSource: (SearchFolder) -> Unit,
    hidden: Int = 0,
) {
    val playable = source.tracks.filter { it.info.playable }
    var options by remember { mutableStateOf<SearchTrack?>(null) }
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(null, release.album, 132.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(release.album, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                    release.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Note(sourceLine(source))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        source.summary(filter)?.let { FormatBadge(it) }
                        if (source.slotFree) Badge("Slot reported free", LosslessColor)
                        else Badge("${source.queueLength} waiting in their queue", WarningColor)
                    }
                }
            }
        }
        item(key = "actions") {
            FlowRow(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playable.isNotEmpty()) {
                    ActionButton("Play", Icons.Default.PlayArrow, pageDefault = true) { onPlay(playable.first(), false) }
                    ActionButton("Shuffle", Icons.Default.Shuffle, primary = false) { onPlay(playable.random(), true) }
                    ActionButton("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd, primary = false) { onEnqueue(playable, false) }
                }
                ActionButton("Download", Icons.Default.Download, primary = playable.isEmpty(), pageDefault = playable.isEmpty()) {
                    onDownload(source.tracks)
                }
                if (release.sources.size > 1) {
                    ActionButton("Sources (${release.sources.size})", Icons.Default.People, primary = false) { choosingSource = true }
                }
            }
        }
        item(key = "note") {
            Note(
                listOfNotNull(
                    "Showing the ${source.tracks.size} ${filter.label} files; $hidden other${if (hidden == 1) " is" else "s are"} hidden by the search's filters."
                        .takeIf { hidden > 0 },
                    if (playable.isEmpty()) "This device can't play these files; download them to keep them."
                    else "Playing starts while the songs download, and keeps them in your library.",
                ).joinToString(" "),
            )
        }
        items(source.tracks, key = { it.file.filename }) { track ->
            RowWithMore(
                onClick = { if (track.info.playable) onPlay(track, false) else options = track },
                onMore = { options = track },
                key = track.file.filename,
                modifier = Modifier.testTag("track-${track.name.title}"),
            ) {
                TwoLines(
                    listOfNotNull(track.name.trackNumber?.let { "$it." }, track.name.title).joinToString(" "),
                    listOfNotNull(
                        formatDuration(track.file.durationSec?.toLong()).ifEmpty { null },
                        formatSize(track.file.size),
                        status(track),
                    ).joinToString(" · "),
                    Modifier.weight(1f),
                ) { FormatBadge(track.info) }
            }
        }
    }
    options?.let { track ->
        OptionsDialog(
            title = track.name.title,
            subtitle = listOfNotNull(track.info.label, formatSize(track.file.size)).joinToString(" · "),
            onDismiss = { options = null },
            options = if (track.info.playable) {
                listOf(
                    Option("Play from here", Icons.Default.PlayArrow) { onPlay(track, false) },
                    Option("Play next") { onEnqueue(listOf(track), true) },
                    Option("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd) { onEnqueue(listOf(track), false) },
                    Option("Download only", Icons.Default.Download) { onDownload(listOf(track)) },
                )
            } else {
                listOf(Option("Download", Icons.Default.Download) { onDownload(listOf(track)) })
            },
        )
    }
    if (choosingSource) {
        OptionsDialog(
            title = "Choose whose copy to use",
            subtitle = "Every user's copy of this album. Songs you already have stay in your library.",
            onDismiss = { choosingSource = false },
            options = release.sources.map { folder ->
                Option(
                    (if (folder.key == source.key) "✓ " else "") +
                        listOfNotNull(
                            folder.username,
                            folder.summary(filter)?.label,
                            "${folder.tracks.size} songs",
                            if (folder.slotFree) "slot free" else "queue ${folder.queueLength}",
                            formatSpeed(folder.avgSpeed.toLong()).ifEmpty { null },
                        ).joinToString(" · "),
                ) { onChooseSource(folder) }
            },
        )
    }
}

private fun sourceLine(source: SearchFolder): String = listOfNotNull(
    "From ${source.username}",
    "${source.tracks.size} songs",
    formatSize(source.totalSize),
    formatSpeed(source.avgSpeed.toLong()).ifEmpty { null },
).joinToString(" · ")
