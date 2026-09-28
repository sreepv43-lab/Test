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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibrarySort
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.LibraryViews
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.Chip
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.FilterDialog
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.RowWithMore
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.tvEnterAt
import io.github.sreepv43.soundhub.ui.components.tvRow
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup

enum class LibraryTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    FAVOURITES("Favourites"),
}

private enum class LibraryDialog { FIND, SORT, FILTER, NEW_PLAYLIST, REMOVE_MISSING }

/**
 * Everything downloaded, as albums, songs, artists, playlists and favourites, with a search of its
 * own, sorting and the same filters as Search. Songs on an unplugged drive stay listed (marked
 * "Drive disconnected") until the listener removes them.
 */
@Composable
fun LibraryScreen(
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onSearch: () -> Unit,
    onPlaying: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    val filter by container.libraryFilter.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    var dialog by rememberSaveable { mutableStateOf<LibraryDialog?>(null) }
    var menuFor by remember { mutableStateOf<LibraryTrack?>(null) }
    LaunchedEffect(Unit) { container.refreshMissing() }

    val visible = remember(tracks, query, filter) { LibraryViews.filter(LibraryViews.search(tracks, query), filter) }
    val folder = remember { container.musicFolder().path }

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                "Your Library",
                if (tracks.isEmpty()) null else "${tracks.size} songs · ${formatSize(tracks.sumOf { it.size })} · saved in $folder",
            )
        }
        if (tracks.isEmpty()) {
            item(key = "empty") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note("Nothing here yet. Songs you play or download from Search are kept here.", modifier = Modifier.weight(1f))
                    ActionButton("Search Soulseek", Icons.Default.Search, pageDefault = true, onClick = onSearch)
                }
            }
            return@LazyColumn
        }
        item(key = "tabs") {
            LazyRow(
                Modifier.tvRow().tvEnterAt { "tab-${tab.name}" }.testTag("tabs"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(LibraryTab.entries, key = { it.name }) { entry ->
                    Chip(entry.label, entry == tab, key = "tab-${entry.name}") { tab = entry }
                }
            }
        }
        item(key = "tools") {
            LazyRow(
                Modifier.tvRow().tvEnterAt { null }.testTag("tools"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                item(key = "find") {
                    Chip(if (query.isEmpty()) "Find in library" else "Find: $query", query.isNotEmpty(), key = "find") {
                        dialog = LibraryDialog.FIND
                    }
                }
                if (query.isNotEmpty()) item(key = "clear") { Chip("Clear search", false, key = "clear-find") { query = "" } }
                item(key = "sort") { Chip("${sort.label}  ▾", false, key = "sort") { dialog = LibraryDialog.SORT } }
                item(key = "filter") {
                    Chip(if (filter.isDefault) "Filters" else "Filters: ${filter.label}", !filter.isDefault, key = "filter") {
                        dialog = LibraryDialog.FILTER
                    }
                }
                item(key = "shuffle") {
                    Chip("Shuffle", false, key = "shuffle") {
                        if (playOrExplain(context, container, visible.filter { it.id !in missing }, shuffle = true)) onPlaying()
                    }
                }
            }
        }
        val missingCount = tracks.count { it.id in missing }
        if (missingCount > 0) {
            item(key = "missing") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(
                        "$missingCount song${if (missingCount == 1) " is" else "s are"} on a drive that isn't connected. " +
                            "They play again once it is plugged in.",
                        WarningColor,
                        Modifier.weight(1f),
                    )
                    ActionButton("Remove missing…", primary = false) { dialog = LibraryDialog.REMOVE_MISSING }
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "nothing") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note("Nothing in your library matches.", modifier = Modifier.weight(1f))
                    ActionButton("Clear search and filters") {
                        query = ""
                        container.libraryFilter.value = MusicFilter()
                    }
                }
            }
        }
        when (tab) {
            LibraryTab.ALBUMS -> albumRows(LibraryViews.albums(visible, sort), missing, "album", onOpenAlbum)
            LibraryTab.SONGS -> {
                val songs = LibraryViews.songs(visible, sort)
                songRows(songs, missing, "song", onPlay = { if (playOrExplain(context, container, songs, it)) onPlaying() }) { menuFor = it }
            }
            LibraryTab.ARTISTS -> items(LibraryViews.artists(visible), key = { "artist:" + it.name }) { artist ->
                ListRow(onClick = { onOpenArtist(artist.name) }, key = "artist:" + artist.name) {
                    AlbumCover(artist.albums.first().key, artist.name, 52.dp)
                    TwoLines(
                        artist.name,
                        "${artist.albums.size} album${if (artist.albums.size == 1) "" else "s"} · ${artist.trackCount} songs",
                        Modifier.weight(1f),
                    )
                }
            }
            LibraryTab.PLAYLISTS -> {
                item(key = "new-playlist") {
                    Row(Modifier.tvButtonGroup()) {
                        ActionButton("New playlist", Icons.Default.Add, primary = false) { dialog = LibraryDialog.NEW_PLAYLIST }
                    }
                }
                if (collection.playlists.isEmpty()) {
                    item(key = "no-playlists") {
                        Note("No playlists yet. Make one here, or choose More → Add to playlist on any song or album.")
                    }
                }
                items(collection.playlists, key = { "playlist:" + it.id }) { playlist ->
                    ListRow(onClick = { onOpenPlaylist(playlist.id) }, key = "playlist:" + playlist.id) {
                        TwoLines(playlist.name, "${playlist.trackIds.size} songs", Modifier.weight(1f))
                    }
                }
            }
            LibraryTab.FAVOURITES -> {
                val albums = LibraryViews.albums(visible, sort).filter { it.key in collection.favouriteAlbums }
                val songs = LibraryViews.songs(visible.filter { it.id in collection.favouriteTracks }, sort)
                if (albums.isEmpty() && songs.isEmpty()) {
                    item(key = "no-favourites") {
                        Note("No favourites yet. Choose More → Add to favourites on a song or album, or the heart on Now playing.")
                    }
                }
                if (albums.isNotEmpty()) item(key = "fav-albums") { Text("Albums", style = MaterialTheme.typography.titleMedium) }
                albumRows(albums, missing, "fav-album", onOpenAlbum)
                if (songs.isNotEmpty()) item(key = "fav-songs") { Text("Songs", style = MaterialTheme.typography.titleMedium) }
                songRows(songs, missing, "fav-song", onPlay = { if (playOrExplain(context, container, songs, it)) onPlaying() }) { menuFor = it }
            }
        }
    }

    when (dialog) {
        LibraryDialog.FIND -> TextEntryDialog(
            title = "Find in your library",
            initial = query,
            onDismiss = { dialog = null },
            onDone = {
                query = it.trim()
                dialog = null
            },
        )
        LibraryDialog.SORT -> OptionsDialog(
            title = "Sort by",
            onDismiss = { dialog = null },
            options = LibrarySort.entries.map { Option((if (it == sort) "✓ " else "") + it.label) { sort = it } },
        )
        LibraryDialog.FILTER -> FilterDialog(
            filter,
            showAvailability = false,
            onChange = { container.libraryFilter.value = it },
            onDismiss = { dialog = null },
        )
        LibraryDialog.NEW_PLAYLIST -> TextEntryDialog(
            title = "Name the new playlist",
            initial = "",
            onDismiss = { dialog = null },
            onDone = { name ->
                dialog = null
                onOpenPlaylist(container.collection.createPlaylist(name).id)
            },
        )
        LibraryDialog.REMOVE_MISSING -> TvDialog(
            title = "Remove songs that aren't there?",
            onDismiss = { dialog = null },
            buttons = listOf(
                DialogButton("Keep them", primary = true) { dialog = null },
                DialogButton("Remove") {
                    dialog = null
                    container.library.removeMissing()
                },
            ),
        ) {
            Note(
                "If their drive is only unplugged, keep them: they play again once it is back. Removing takes them " +
                    "out of the library; files on the drive aren't touched.",
            )
        }
        null -> Unit
    }
    menuFor?.let { track ->
        SongMenu(
            tracks = listOf(track),
            title = track.title,
            onDismiss = { menuFor = null },
            onPlay = null,
            onGoToAlbum = { onOpenAlbum(LibraryViews.albumKeyOf(track.path)) },
        )
    }
}

/** The format most of an album's songs are in. */
private fun Album.mainFormat(): AudioInfo? = tracks.map { it.info }.groupBy { it.label }.maxByOrNull { it.value.size }?.value?.first()

private fun LazyListScope.albumRows(albums: List<Album>, missing: Set<String>, prefix: String, onOpen: (String) -> Unit) {
    items(albums, key = { "$prefix:" + it.key }) { album ->
        val gone = album.tracks.all { it.id in missing }
        ListRow(onClick = { onOpen(album.key) }, key = "$prefix:" + album.key, modifier = Modifier.testTag("album-${album.title}")) {
            AlbumCover(album.key, album.title, 52.dp)
            TwoLines(
                album.title,
                listOfNotNull(album.artist, "${album.tracks.size} songs", if (gone) "Drive disconnected" else null).joinToString(" · "),
                Modifier.weight(1f),
            )
            album.mainFormat()?.let { FormatBadge(it) }
        }
    }
}

private fun LazyListScope.songRows(
    songs: List<LibraryTrack>,
    missing: Set<String>,
    prefix: String,
    onPlay: (LibraryTrack) -> Unit,
    onMore: (LibraryTrack) -> Unit,
) {
    items(songs, key = { "$prefix:" + it.id }) { track ->
        RowWithMore(onClick = { onPlay(track) }, onMore = { onMore(track) }, key = "$prefix:" + track.id) {
            AlbumCover(LibraryViews.albumKeyOf(track.path), track.album, 44.dp)
            TwoLines(track.title, songLine(track, track.id in missing, withAlbum = true), Modifier.weight(1f))
            FormatBadge(track.info)
        }
    }
}

private fun songLine(track: LibraryTrack, missing: Boolean, withAlbum: Boolean): String = listOfNotNull(
    track.artist.takeIf { withAlbum },
    track.album.takeIf { withAlbum },
    formatDuration(track.info.durationSec?.toLong()).ifEmpty { null },
    when {
        missing -> "Drive disconnected"
        !track.info.playable -> "Can't be played on this device"
        else -> null
    },
).joinToString(" · ")

/** A library album: play, shuffle, favourite, queue, and every song with its own menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumScreen(albumKey: String, onPlaying: () -> Unit, onGone: () -> Unit, onOpenArtist: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    val album = remember(tracks, albumKey) { LibraryStore.albums(tracks).firstOrNull { it.key == albumKey } }
    var menuFor by remember { mutableStateOf<LibraryTrack?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    if (album == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Note("This album is no longer in your library.") }
            item { ActionButton("Back", pageDefault = true, onClick = onGone) }
        }
        return
    }
    val favourite = album.key in collection.favouriteAlbums
    val gone = album.tracks.count { it.id in missing }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumCover(album.key, album.title, 150.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(album.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                    album.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Note(
                        listOfNotNull(
                            "${album.tracks.size} songs",
                            formatDuration(album.tracks.sumOf { it.info.durationSec ?: 0 }.toLong()).takeIf { album.tracks.any { it.info.durationSec != null } },
                            formatSize(album.tracks.sumOf { it.size }),
                        ).joinToString(" · "),
                    )
                    album.mainFormat()?.let { FormatBadge(it) }
                    if (gone > 0) {
                        Note(
                            if (gone == album.tracks.size) "Drive disconnected: plug it in to play this album."
                            else "$gone songs are on a drive that isn't connected.",
                            WarningColor,
                        )
                    }
                }
            }
        }
        item(key = "actions") {
            FlowRow(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Play", Icons.Default.PlayArrow, pageDefault = true) {
                    if (playOrExplain(context, container, album.tracks, album.tracks.firstOrNull { it.id !in missing && it.info.playable })) onPlaying()
                }
                ActionButton("Shuffle", Icons.Default.Shuffle, primary = false) {
                    if (playOrExplain(context, container, album.tracks, shuffle = true)) onPlaying()
                }
                ActionButton(
                    if (favourite) "Favourite" else "Add to favourites",
                    if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    primary = false,
                ) { container.collection.toggleFavouriteAlbum(album.key) }
                ActionButton("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd, primary = false) {
                    addToQueue(context, container, album.tracks, next = false)
                }
                album.artist?.let { artist ->
                    ActionButton(artist, Icons.Default.Person, primary = false) { onOpenArtist(artist) }
                }
                ActionButton("More", Icons.Default.MoreHoriz, primary = false) { albumMenu = true }
            }
        }
        items(album.tracks, key = { it.id }) { track ->
            RowWithMore(
                onClick = { if (playOrExplain(context, container, album.tracks, track)) onPlaying() },
                onMore = { menuFor = track },
                key = track.id,
                modifier = Modifier.testTag("track-${track.title}"),
            ) {
                TwoLines(
                    listOfNotNull(track.trackNumber?.let { "$it." }, track.title).joinToString(" "),
                    songLine(track, track.id in missing, withAlbum = false).ifEmpty { null },
                    Modifier.weight(1f),
                )
                if (track.id in collection.favouriteTracks) {
                    Icon(Icons.Default.Favorite, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.primary)
                }
                FormatBadge(track.info)
            }
        }
    }
    menuFor?.let { track ->
        SongMenu(
            tracks = listOf(track),
            title = track.title,
            onDismiss = { menuFor = null },
            onPlay = { if (playOrExplain(context, container, album.tracks, track)) onPlaying() },
        )
    }
    if (albumMenu) {
        SongMenu(
            tracks = album.tracks,
            title = album.title,
            onDismiss = { albumMenu = false },
            onPlay = { if (playOrExplain(context, container, album.tracks, album.tracks.firstOrNull { it.id !in missing })) onPlaying() },
            albumKey = album.key,
        )
    }
}

/** All albums by one artist. */
@Composable
fun ArtistScreen(name: String, onOpenAlbum: (String) -> Unit, onPlaying: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    val artist = remember(tracks, name) { LibraryViews.artists(tracks).firstOrNull { it.name == name } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(name, artist?.let { "${it.albums.size} albums · ${it.trackCount} songs" } ?: "No longer in your library")
        }
        if (artist != null) {
            val all = artist.albums.flatMap { it.tracks }
            item(key = "actions") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Play all", Icons.Default.PlayArrow, pageDefault = true) {
                        if (playOrExplain(context, container, all, all.firstOrNull { it.id !in missing && it.info.playable })) onPlaying()
                    }
                    ActionButton("Shuffle", Icons.Default.Shuffle, primary = false) {
                        if (playOrExplain(context, container, all, shuffle = true)) onPlaying()
                    }
                }
            }
            albumRows(artist.albums, missing, "album", onOpenAlbum)
        }
    }
}

private enum class PlaylistDialog { RENAME, DELETE }

/** A playlist: play it, and reorder or remove its songs. */
@Composable
fun PlaylistScreen(id: String, onPlaying: () -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<PlaylistDialog?>(null) }
    var menuAt by remember { mutableStateOf<Int?>(null) }
    val playlist = collection.playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Note("This playlist was deleted.") }
            item { ActionButton("Back", pageDefault = true, onClick = onGone) }
        }
        return
    }
    val byId = remember(library) { library.associateBy { it.id } }
    val entries = playlist.trackIds.map { byId[it] }
    val tracks = entries.filterNotNull()
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { ScreenTitle(playlist.name, "${playlist.trackIds.size} songs") }
        item(key = "actions") {
            Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tracks.isNotEmpty()) {
                    ActionButton("Play", Icons.Default.PlayArrow, pageDefault = true) {
                        if (playOrExplain(context, container, tracks, tracks.firstOrNull { it.id !in missing && it.info.playable })) onPlaying()
                    }
                    ActionButton("Shuffle", Icons.Default.Shuffle, primary = false) {
                        if (playOrExplain(context, container, tracks, shuffle = true)) onPlaying()
                    }
                }
                ActionButton("Rename", Icons.Default.Edit, primary = false, pageDefault = tracks.isEmpty()) { dialog = PlaylistDialog.RENAME }
                ActionButton("Delete playlist", Icons.Default.Delete, primary = false) { dialog = PlaylistDialog.DELETE }
            }
        }
        if (playlist.trackIds.isEmpty()) {
            item(key = "empty") { Note("Empty. Choose More → Add to playlist on any song or album in your library.") }
        }
        itemsIndexed(entries, key = { index, _ -> "entry:$index" }) { index, track ->
            RowWithMore(
                onClick = { if (track != null && playOrExplain(context, container, tracks, track)) onPlaying() },
                onMore = { menuAt = index },
                key = "entry:$index",
            ) {
                if (track == null) {
                    TwoLines("Removed from the library", "Choose More → Remove from playlist", Modifier.weight(1f))
                } else {
                    AlbumCover(LibraryViews.albumKeyOf(track.path), track.album, 44.dp)
                    TwoLines(track.title, songLine(track, track.id in missing, withAlbum = true), Modifier.weight(1f))
                    FormatBadge(track.info)
                }
            }
        }
    }
    menuAt?.let { index ->
        val move = listOf(
            Option("Move up", Icons.Default.ArrowUpward) { container.collection.movePlaylistItem(id, index, -1) },
            Option("Move down", Icons.Default.ArrowDownward) { container.collection.movePlaylistItem(id, index, +1) },
            Option("Remove from playlist", Icons.Default.RemoveCircleOutline) { container.collection.removeFromPlaylist(id, index) },
        )
        val track = entries.getOrNull(index)
        if (track == null) {
            OptionsDialog(title = "Song removed from the library", onDismiss = { menuAt = null }, options = move)
        } else {
            SongMenu(
                tracks = listOf(track),
                title = track.title,
                onDismiss = { menuAt = null },
                onPlay = { if (playOrExplain(context, container, tracks, track)) onPlaying() },
                extra = move,
            )
        }
    }
    when (dialog) {
        PlaylistDialog.RENAME -> TextEntryDialog(
            title = "Rename playlist",
            initial = playlist.name,
            onDismiss = { dialog = null },
            onDone = {
                container.collection.renamePlaylist(id, it)
                dialog = null
            },
        )
        PlaylistDialog.DELETE -> TvDialog(
            title = "Delete ${playlist.name}?",
            onDismiss = { dialog = null },
            buttons = listOf(
                DialogButton("Keep", primary = true) { dialog = null },
                DialogButton("Delete playlist") {
                    dialog = null
                    container.collection.deletePlaylist(id)
                    toast(context, "Deleted ${playlist.name}. The songs stay in your library.")
                    onGone()
                },
            ),
        ) {
            Note("Only the playlist goes; its songs stay in your library.")
        }
        null -> Unit
    }
}
