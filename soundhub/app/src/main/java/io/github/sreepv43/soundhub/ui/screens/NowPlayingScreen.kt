package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.formatDuration
import io.github.sreepv43.soundhub.ui.components.transferText
import io.github.sreepv43.soundhub.ui.components.tvFocus
import kotlinx.coroutines.delay

/** The current song, where its audio goes (bitstream to the receiver or decoded here), and the queue. */
@Composable
fun NowPlayingScreen() {
    val container = LocalContext.current.container
    val playback = container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val buffering by playback.buffering.collectAsStateWithLifecycle()
    val error by playback.error.collectAsStateWithLifecycle()
    val output by playback.output.collectAsStateWithLifecycle()
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    // Recompose when the file header or the player confirms the format.
    val infos by container.downloads.infos.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = playback.player.currentPosition
            duration = playback.player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    val current = item
    if (current == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
            item { ScreenTitle("Now playing") }
            item { Note("Nothing is playing. Press Left for the menu, then Search, Dolby Atmos or Library.") }
        }
        return
    }
    val info = remember(current, infos, library) { playback.infoFor(current) }
    val transfer = current.transferId?.let { id -> transfers.firstOrNull { it.id == id } }

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(current.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(current.artist, current.album).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(info)
                    if (transfer != null && transfer.active) Note(transferText(transfer))
                }
            }
        }
        item(key = "controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlButton(Icons.Default.SkipPrevious, "Previous") { playback.previous() }
                ControlButton(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    large = true,
                ) { playback.togglePlay() }
                ControlButton(Icons.Default.SkipNext, "Next") { playback.next() }
                if (buffering) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Note(if (transfer != null && transfer.active) "Waiting for the download…" else "Loading…")
                }
            }
        }
        item(key = "progress") {
            Column {
                LinearProgressIndicator(
                    progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(position / 1000), style = MaterialTheme.typography.labelMedium)
                    Text(if (duration > 0) formatDuration(duration / 1000) else "", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        error?.let { message ->
            item(key = "error") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    ActionButton("Try again", Icons.Default.Refresh, primary = false) { playback.retry() }
                }
            }
        }
        output?.let { state ->
            item(key = "output") {
                ListRow(onClick = {}, key = "output") {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.output,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.passthrough) AtmosColor else MaterialTheme.colorScheme.onSurface,
                        )
                        Note("File: ${state.input}")
                        state.warning?.let { Note(it, WarningColor) }
                    }
                }
            }
        }
        if (queue.size > 1) {
            item(key = "queue-title") { Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            itemsIndexed(queue, key = { _, it -> "queue-" + it.id }) { index, entry ->
                ListRow(onClick = { playback.jumpTo(index) }, key = "queue-" + entry.id) {
                    Text(
                        (if (entry.id == current.id) "▶ " else "") + entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (entry.id == current.id) Accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FormatBadge(entry.info)
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, large: Boolean = false, onClick: () -> Unit) {
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
        tint = if (focused || large) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(size)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(CircleShape, scale = 1.1f, pageDefault = large)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(size / 4)
            .testTag(if (large) "play-pause" else label),
    )
}
