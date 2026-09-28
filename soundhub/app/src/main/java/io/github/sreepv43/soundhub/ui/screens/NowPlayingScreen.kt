package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.player.QueueItem
import io.github.sreepv43.soundhub.player.SleepTimer
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.RowWithMore
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.SeekBar
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.transferText
import io.github.sreepv43.soundhub.ui.components.tvFocus
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup
import kotlinx.coroutines.delay

/**
 * The song playing: artwork, seek bar, transport (Play/Pause selected first), favourite, queue,
 * sleep timer, and what actually reaches the receiver (OK there opens the Sound page).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(onQueue: () -> Unit, onGo: (Section) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val playback = container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val index by playback.currentIndex.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val buffering by playback.buffering.collectAsStateWithLifecycle()
    val error by playback.error.collectAsStateWithLifecycle()
    val output by playback.output.collectAsStateWithLifecycle()
    val shuffle by playback.shuffle.collectAsStateWithLifecycle()
    val repeat by playback.repeatMode.collectAsStateWithLifecycle()
    val sleep by playback.sleep.collectAsStateWithLifecycle()
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    // Recompose when the file header or the player confirms the format.
    val infos by container.downloads.infos.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    var showSleep by remember { mutableStateOf(false) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var downloaded by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = playback.player.currentPosition
            duration = playback.player.duration.coerceAtLeast(0L)
            downloaded = playback.downloadedFraction()
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val current = item
    if (current == null) {
        val session = collection.session
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenTitle("Now playing") }
            item { Note("Nothing is playing.") }
            item {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (session != null) {
                        ActionButton("Resume", Icons.Default.PlayArrow, pageDefault = true) {
                            if (!container.resume()) toast(context, "Those songs aren't available (is their drive connected?)")
                        }
                    }
                    ActionButton("Search", Icons.Default.Search, primary = session == null, pageDefault = session == null) { onGo(Section.SEARCH) }
                    ActionButton("Library", Icons.Default.LibraryMusic, primary = false) { onGo(Section.LIBRARY) }
                }
            }
        }
        return
    }
    val info = remember(current, infos, library) { playback.infoFor(current) }
    val transfer = current.transferId?.let { id -> transfers.firstOrNull { it.id == id } }
    val favourite = current.id in collection.favouriteTracks
    val upNext = queue.drop(index + 1).take(UP_NEXT)

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "song") {
            BoxWithConstraints {
                val art = if (maxWidth > 640.dp) 200.dp else 120.dp
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumCover(current.albumKey, current.album, art)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(current.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(current.artist, current.album).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormatBadge(info)
                            if (queue.size > 1) Note("Song ${index + 1} of ${queue.size}")
                        }
                        when {
                            buffering -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Note(if (transfer != null && transfer.active) "Waiting for the download: ${transferText(transfer)}" else "Loading…")
                            }
                            transfer != null && transfer.active -> Note("Downloading while it plays: ${transferText(transfer)}")
                        }
                    }
                }
            }
        }
        item(key = "seek") {
            SeekBar(
                positionMs = position,
                durationMs = duration,
                downloaded = downloaded,
                seekable = playback.seekable,
                onSeekBy = playback::seekBy,
                onClick = playback::togglePlay,
            )
        }
        item(key = "controls") {
            PlayerControls(
                playing = playing,
                shuffle = shuffle,
                repeatMode = repeat,
                onShuffle = playback::toggleShuffle,
                onPrevious = playback::previous,
                onPlayPause = playback::togglePlay,
                onNext = playback::next,
                onRepeat = playback::cycleRepeat,
            )
        }
        item(key = "more") {
            FlowRow(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    if (favourite) "Favourite" else "Add to favourites",
                    if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    primary = false,
                ) { container.collection.toggleFavouriteTrack(current.id) }
                ActionButton("Queue (${queue.size})", Icons.AutoMirrored.Filled.QueueMusic, primary = false, onClick = onQueue)
                ActionButton(sleepLabel(sleep, now), Icons.Default.Bedtime, primary = false) { showSleep = true }
                ActionButton("Sound", Icons.Default.SurroundSound, primary = false) { onGo(Section.SOUND) }
            }
        }
        error?.let { message ->
            item(key = "error") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    ActionButton("Try again", Icons.Default.Refresh, primary = false) { playback.retry() }
                    if (queue.size > index + 1) ActionButton("Skip", Icons.Default.SkipNext, primary = false) { playback.next() }
                }
            }
        }
        output?.let { state ->
            item(key = "output") {
                ListRow(onClick = { onGo(Section.SOUND) }, key = "output") {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.output,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.passthrough) AtmosColor else MaterialTheme.colorScheme.onSurface,
                        )
                        Note("File: ${state.input} · OK for sound settings")
                        state.warning?.let { Note(it, WarningColor) }
                    }
                }
            }
        }
        if (upNext.isNotEmpty()) {
            item(key = "next-title") { SectionHeader("Up next") }
            itemsIndexed(upNext, key = { i, it -> "next:${index + 1 + i}:${it.id}" }) { i, entry ->
                ListRow(onClick = { playback.jumpTo(index + 1 + i) }, key = "next:${index + 1 + i}:${entry.id}") {
                    TwoLines(entry.title, listOfNotNull(entry.artist, entry.album).joinToString(" · "), Modifier.weight(1f))
                    FormatBadge(entry.info)
                }
            }
        }
    }
    if (showSleep) {
        OptionsDialog(
            title = "Sleep timer",
            subtitle = "Playback pauses by itself.",
            onDismiss = { showSleep = false },
            options = listOfNotNull(
                sleep?.let { Option("Turn off") { playback.cancelSleepTimer() } },
                Option("End of this song") { playback.setSleepTimer(null, endOfSong = true) },
            ) + listOf(15, 30, 45, 60, 90).map { minutes ->
                Option(if (minutes < 60) "$minutes minutes" else "${minutes / 60.0} hours".replace(".0 hours", " hour")) {
                    playback.setSleepTimer(minutes)
                }
            },
        )
    }
}

private fun sleepLabel(sleep: SleepTimer?, now: Long): String = when {
    sleep == null -> "Sleep timer"
    sleep.endOfSong -> "Sleep: end of song"
    else -> "Sleep: ${((sleep.endsAt ?: now) - now).coerceAtLeast(0) / 60_000 + 1} min"
}

/** Shuffle, previous, play/pause (where the page starts), next, repeat. */
@Composable
fun PlayerControls(
    playing: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    // Centred under the seek bar, so Down from it lands on Play/Pause.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Default.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", active = shuffle, tag = "Shuffle", onClick = onShuffle)
            ControlButton(Icons.Default.SkipPrevious, "Previous", onClick = onPrevious)
            ControlButton(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                large = true,
                tag = "play-pause",
                onClick = onPlayPause,
            )
            ControlButton(Icons.Default.SkipNext, "Next", onClick = onNext)
            ControlButton(
                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                repeatLabel(repeatMode) ?: "Repeat off",
                active = repeatMode != Player.REPEAT_MODE_OFF,
                tag = "Repeat",
                onClick = onRepeat,
            )
        }
        Note(
            listOfNotNull("Shuffle on".takeIf { shuffle }, repeatLabel(repeatMode)).joinToString(" · ").ifEmpty { " " },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun repeatLabel(mode: Int): String? = when (mode) {
    Player.REPEAT_MODE_ALL -> "Repeat all"
    Player.REPEAT_MODE_ONE -> "Repeat this song"
    else -> null
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    large: Boolean = false,
    active: Boolean = false,
    tag: String = label,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (large) 72.dp else 56.dp
    val background = when {
        focused -> AppColors.text
        large -> Accent
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Icon(
        icon,
        contentDescription = label,
        tint = when {
            focused || large -> MaterialTheme.colorScheme.onPrimary
            active -> Accent
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .size(size)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(CircleShape, scale = 1.1f, pageDefault = large)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(size / 4)
            .testTag(tag),
    )
}

/** The queue: jump to any song, move songs, remove them, clear what's coming, save it as a playlist. */
@Composable
fun QueueScreen() {
    val context = LocalContext.current
    val container = context.container
    val playback = container.playback
    val queue by playback.queue.collectAsStateWithLifecycle()
    val index by playback.currentIndex.collectAsStateWithLifecycle()
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    var menuAt by remember { mutableStateOf<Int?>(null) }
    var naming by remember { mutableStateOf(false) }
    QueueLayout(
        queue = queue,
        currentIndex = index,
        status = { entry -> entry.transferId?.let { id -> transfers.firstOrNull { it.id == id }?.takeIf { it.active }?.let(::transferText) } },
        onJump = playback::jumpTo,
        onMenu = { menuAt = it },
        onSave = { naming = true },
        onClearUpcoming = {
            playback.clearUpcoming()
            toast(context, "Cleared the songs after this one")
        },
    )
    menuAt?.let { at ->
        val entry = queue.getOrNull(at)
        if (entry != null) {
            OptionsDialog(
                title = entry.title,
                subtitle = listOfNotNull(entry.artist, entry.album).joinToString(" · "),
                onDismiss = { menuAt = null },
                options = listOfNotNull(
                    Option("Play now", Icons.Default.PlayArrow) { playback.jumpTo(at) }.takeIf { at != index },
                    Option("Play next", Icons.Default.SkipNext) { playback.playNext(at) }.takeIf { at != index && at != index + 1 },
                    Option("Move up", Icons.Default.ArrowUpward) { playback.move(at, -1) }.takeIf { at > 0 },
                    Option("Move down", Icons.Default.ArrowDownward) { playback.move(at, +1) }.takeIf { at < queue.lastIndex },
                    Option("Remove from queue", Icons.Default.RemoveCircleOutline) { playback.removeAt(at) },
                ),
            )
        }
    }
    if (naming) {
        TextEntryDialog(
            title = "Save the queue as a playlist",
            initial = queue.firstOrNull()?.album.orEmpty(),
            onDismiss = { naming = false },
            onDone = { name ->
                naming = false
                val playlist = container.collection.createPlaylist(name, queue.map { it.id })
                toast(context, "Saved ${playlist.name} (under Library → Playlists)")
            },
        )
    }
}

@Composable
fun QueueLayout(
    queue: List<QueueItem>,
    currentIndex: Int,
    status: (QueueItem) -> String?,
    onJump: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    onSave: () -> Unit,
    onClearUpcoming: () -> Unit,
) {
    val upcoming = (queue.size - currentIndex - 1).coerceAtLeast(0)
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        // Opens at the song playing (the page starts on it), with a song or two above it in view.
        state = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0)),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle("Queue", if (queue.isEmpty()) null else "${queue.size} songs · $upcoming after this one")
        }
        if (queue.isEmpty()) {
            item(key = "empty") { Note("The queue is empty. Play an album, or choose More → Add to queue on any song.") }
        } else {
            item(key = "actions") {
                Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Save as playlist", Icons.AutoMirrored.Filled.PlaylistAdd, primary = false, onClick = onSave)
                    if (upcoming > 0) ActionButton("Clear upcoming", Icons.Default.ClearAll, primary = false, onClick = onClearUpcoming)
                }
            }
        }
        itemsIndexed(queue, key = { i, _ -> "queue:$i" }) { i, entry ->
            val isCurrent = i == currentIndex
            RowWithMore(
                onClick = { onJump(i) },
                onMore = { onMenu(i) },
                key = "queue:$i",
                modifier = Modifier.testTag("queue-$i"),
                pageDefault = isCurrent,
            ) {
                Text(
                    if (isCurrent) "▶" else "${i + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                TwoLines(
                    entry.title,
                    listOfNotNull(entry.artist, entry.album, status(entry)).joinToString(" · "),
                    Modifier.weight(1f),
                )
                FormatBadge(entry.info)
            }
        }
    }
}

private const val UP_NEXT = 5
