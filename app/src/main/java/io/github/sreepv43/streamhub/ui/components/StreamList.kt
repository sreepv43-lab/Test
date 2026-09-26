package io.github.sreepv43.streamhub.ui.components

import io.github.sreepv43.streamhub.ui.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.AddonStreams
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StreamsState(
    val results: List<AddonStreams> = emptyList(),
    val pending: Int = 0,
    val noAddons: Boolean = false,
)

/** Queries every addon that can serve streams for a video, showing results as each one answers. */
class StreamsLoader(private val repository: AddonRepository, private val viewModel: ViewModel) {
    private val _state = MutableStateFlow(StreamsState())
    val state: StateFlow<StreamsState> = _state.asStateFlow()
    private var jobs = emptyList<Job>()
    private var loadedFor: Pair<String, String>? = null

    fun load(type: String, videoId: String, force: Boolean = false) {
        if (!force && loadedFor == type to videoId) return
        loadedFor = type to videoId
        jobs.forEach { it.cancel() }
        val addons = repository.streamAddons(type, videoId)
        _state.value = StreamsState(pending = addons.size, noAddons = addons.isEmpty())
        jobs = addons.map { addon ->
            viewModel.viewModelScope.launch {
                val result = repository.streams(addon, type, videoId)
                _state.update { current ->
                    val ordered = (current.results + result).sortedBy { r ->
                        repository.addons.value.indexOfFirst { it.transportUrl == r.addon.transportUrl }
                    }
                    current.copy(results = ordered, pending = current.pending - 1)
                }
            }
        }
    }
}

/** Adds the stream list (grouped by addon) to a LazyColumn. */
fun LazyListScope.streamItems(
    state: StreamsState,
    onPlay: (Stream) -> Unit,
    onDownload: (Stream) -> Unit,
    onExternal: (Stream) -> Unit,
) {
    if (state.noAddons) {
        item {
            Text(
                "No installed addon provides streams for this title. Install a stream addon in Addons.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    state.results.forEach { result ->
        item(key = "header-" + result.addon.transportUrl) {
            Text(
                result.addon.manifest.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (result.error != null) {
            item(key = "error-" + result.addon.transportUrl) {
                Text(
                    "Failed: ${result.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else if (result.streams.isEmpty()) {
            item(key = "empty-" + result.addon.transportUrl) {
                Text(
                    "No streams found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        items(result.streams.withIndex().toList(), key = { "s-" + result.addon.transportUrl + it.index }) { (_, stream) ->
            StreamRow(stream, onPlay = { onPlay(stream) }, onDownload = { onDownload(stream) }, onExternal = { onExternal(stream) })
        }
    }
    if (state.pending > 0) {
        item(key = "loading") {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("  Asking ${state.pending} addon(s) for streams…")
            }
        }
    }
}

@Composable
private fun StreamRow(stream: Stream, onPlay: () -> Unit, onDownload: () -> Unit, onExternal: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val torrent = StreamResolver.isTorrent(stream)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .tvFocus(shape, scale = 1.02f, key = streamKey(stream))
                .clip(shape)
                .panel(shape)
                .clickable(onClick = onPlay)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            Text(
                (stream.name ?: "Stream") + if (torrent) "\n⇅ torrent" else "",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(150.dp).padding(horizontal = 12.dp),
            )
            Text(
                stream.details.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
        }
        FlatIconButton(Icons.Default.Download, "Download", onDownload, Modifier.padding(start = 12.dp))
        FlatIconButton(Icons.Default.OpenInNew, "Open in external player", onExternal, Modifier.padding(start = 10.dp))
    }
}

private fun streamKey(stream: Stream): String =
    stream.infoHash + ":" + stream.fileIdx + stream.url + stream.externalUrl + stream.ytId + stream.name

/** State of the "where to download to" dialog. */
class StreamDialogState {
    var downloadStream by mutableStateOf<Stream?>(null)
}

@Composable
fun rememberStreamDialogState() = remember { StreamDialogState() }

@Composable
fun StreamDialogs(state: StreamDialogState, watch: WatchContext?) {
    val context = LocalContext.current
    val settings = context.container.settings

    state.downloadStream?.let { stream ->
        val saved by settings.downloadLocation.collectAsState()
        var selected by remember { mutableStateOf(saved ?: context.container.storage.defaultLocation()) }
        AlertDialog(
            containerColor = AppColors.panel,
            shape = DialogShape,
            onDismissRequest = { state.downloadStream = null },
            title = { Text("Download to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StorageChooser(selected = selected, onSelect = { selected = it })
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.tvFocus(),
                    onClick = {
                        if (watch != null && StreamActions.download(context, stream, watch, selected)) {
                            settings.setDownloadLocation(selected)
                        }
                        state.downloadStream = null
                    },
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = { state.downloadStream = null }) { Text("Cancel") }
            },
        )
    }
}

/** Standard handlers for stream rows: play, download (asks where) and open externally. */
@Composable
fun rememberStreamHandlers(dialogs: StreamDialogState, watch: () -> WatchContext?): StreamHandlers {
    val context = LocalContext.current
    val currentWatch by rememberUpdatedState(watch)
    return remember(dialogs) {
        StreamHandlers(
            onPlay = { stream -> currentWatch()?.let { StreamActions.play(context, stream, it) } },
            onDownload = { stream -> dialogs.downloadStream = stream },
            onExternal = { stream ->
                StreamActions.openInExternalPlayer(context, stream, currentWatch()?.title.orEmpty())
            },
        )
    }
}

class StreamHandlers(
    val onPlay: (Stream) -> Unit,
    val onDownload: (Stream) -> Unit,
    val onExternal: (Stream) -> Unit,
)

/** "Resume from 42:10" note shown above the streams of a title that was partly watched. */
fun LazyListScope.resumeNote(positionMs: Long?) {
    if (positionMs == null) return
    item(key = "resume") {
        val total = positionMs / 1000
        val time = if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
        Text(
            "▶ Resumes from $time with any stream below",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}
