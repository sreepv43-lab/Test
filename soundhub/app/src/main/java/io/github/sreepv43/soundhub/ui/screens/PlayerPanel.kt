package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.player.QueueItem
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.FavouriteColor
import io.github.sreepv43.soundhub.ui.card
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.IconAction
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.RowWithMore
import io.github.sreepv43.soundhub.ui.components.SeekBar
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.toast
import io.github.sreepv43.soundhub.ui.components.tvFocus
import kotlinx.coroutines.delay

/** A song as the player panel shows it. */
data class PanelSong(
    val title: String,
    val artist: String?,
    val durationSec: Long?,
    val album: String,
    val albumKey: String?,
)

/** The player panel's width (wide screens: beside every page while something is loaded). */
val PanelWidth = 330.dp

/**
 * Beside every page on wide screens while something is loaded: what plays next (OK plays it, …
 * moves or removes it, Clear empties it) and the song playing, with favourite, the transport and
 * the seek bar. OK on the song opens Now playing.
 */
@Composable
fun PlayerPanel(onOpen: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val playback = container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val index by playback.currentIndex.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val shuffle by playback.shuffle.collectAsStateWithLifecycle()
    val repeat by playback.repeatMode.collectAsStateWithLifecycle()
    val collection by container.collection.data.collectAsStateWithLifecycle()
    val current = item ?: return
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var downloaded by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = playback.player.currentPosition
            duration = playback.player.duration.coerceAtLeast(0L)
            downloaded = playback.downloadedFraction()
            delay(500)
        }
    }
    var menuAt by remember { mutableStateOf<Int?>(null) }
    val first = index + 1
    val upNext = remember(queue, first) { queue.drop(first).map { it.toPanelSong() } }
    PlayerPanelLayout(
        song = current.toPanelSong(),
        upNext = upNext,
        favourite = current.id in collection.favouriteTracks,
        playing = playing,
        shuffle = shuffle,
        repeatMode = repeat,
        positionMs = position,
        durationMs = duration,
        downloaded = downloaded,
        seekable = playback.seekable,
        onOpen = onOpen,
        onFavourite = { container.collection.toggleFavouriteTrack(current.id) },
        onShuffle = playback::toggleShuffle,
        onPrevious = playback::previous,
        onPlayPause = playback::togglePlay,
        onNext = playback::next,
        onRepeat = playback::cycleRepeat,
        onSeekBy = playback::seekBy,
        onJump = { playback.jumpTo(first + it) },
        onMenu = { menuAt = first + it },
        onClear = {
            playback.clearUpcoming()
            toast(context, "Cleared the songs after this one")
        },
        art = { song, size -> AlbumCover(song.albumKey, song.album, size) },
    )
    menuAt?.let { at -> QueueItemMenu(at) { menuAt = null } }
}

private fun QueueItem.toPanelSong() = PanelSong(title, artist, info.durationSec?.toLong(), album, albumKey)

/** The panel itself, with no app state (the navigation tests drive it). */
@Composable
fun PlayerPanelLayout(
    song: PanelSong,
    upNext: List<PanelSong>,
    favourite: Boolean,
    playing: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    positionMs: Long,
    durationMs: Long,
    downloaded: Float?,
    seekable: Boolean,
    onOpen: () -> Unit,
    onFavourite: () -> Unit,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onJump: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    onClear: () -> Unit,
    art: @Composable (PanelSong, Dp) -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.width(PanelWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .card()
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Up Next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (upNext.isNotEmpty()) TextAction("Clear", tag = "panel-clear", onClick = onClear)
            }
            if (upNext.isEmpty()) {
                Note("Nothing after this song. Choose More → Add to queue on any song.", modifier = Modifier.padding(4.dp))
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("up-next"),
                    state = rememberLazyListState(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Keyed by place, so after OK the selection stays on the same line.
                    itemsIndexed(upNext, key = { i, _ -> "upnext:$i" }) { i, entry ->
                        RowWithMore(
                            onClick = { onJump(i) },
                            onMore = { onMenu(i) },
                            key = "upnext:$i",
                            modifier = Modifier.testTag("upnext-$i"),
                            compact = true,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = dim, modifier = Modifier.width(20.dp))
                            art(entry, 42.dp)
                            TwoLines(
                                entry.title,
                                listOfNotNull(entry.artist, entry.durationSec?.let(::formatDuration)).joinToString(" · "),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().card().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                ListRow(
                    onClick = onOpen,
                    key = "panel-song",
                    modifier = Modifier.weight(1f).testTag("player-card"),
                    padding = PaddingValues(6.dp),
                ) {
                    art(song, 80.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 19.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        song.artist?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconAction(
                    if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favourite) "Favourite" else "Add to favourites",
                    tag = "panel-favourite",
                    tint = if (favourite) FavouriteColor else dim,
                    flat = true,
                    size = 44.dp,
                    onClick = onFavourite,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(
                    Icons.Default.Shuffle,
                    if (shuffle) "Shuffle on" else "Shuffle off",
                    active = shuffle,
                    tag = "panel-shuffle",
                    size = 44.dp,
                    onClick = onShuffle,
                )
                ControlButton(Icons.Default.SkipPrevious, "Previous", tag = "panel-previous", size = 44.dp, onClick = onPrevious)
                ControlButton(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    large = true,
                    tag = "panel-play",
                    size = 60.dp,
                    onClick = onPlayPause,
                )
                ControlButton(Icons.Default.SkipNext, "Next", tag = "panel-next", size = 44.dp, onClick = onNext)
                ControlButton(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    if (repeatMode == Player.REPEAT_MODE_OFF) "Repeat off" else "Repeat on",
                    active = repeatMode != Player.REPEAT_MODE_OFF,
                    tag = "panel-repeat",
                    size = 44.dp,
                    onClick = onRepeat,
                )
            }
            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                downloaded = downloaded,
                seekable = seekable,
                onSeekBy = onSeekBy,
                onClick = onPlayPause,
                compact = true,
            )
        }
    }
}

/** A text button in the accent colour (Clear on Up Next). */
@Composable
private fun TextAction(text: String, tag: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontSize = 16.sp,
        color = if (focused) AppColors.onText else Accent,
        modifier = Modifier
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape)
            .clip(shape)
            .background(if (focused) AppColors.text else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    )
}
