package io.github.sreepv43.streamhub.ui.components

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
import androidx.compose.material3.IconButton
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
import io.github.sreepv43.streamhub.addon.PlaybackTarget
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
    val shape = RoundedCornerShape(10.dp)
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
                .tvFocus(shape, scale = 1.02f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPlay)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            Text(
                (stream.name ?: "Stream") + if (torrent) "\n[torrent]" else "",
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
        IconButton(onClick = onDownload, modifier = Modifier.tvFocus()) {
            Icon(Icons.Default.Download, contentDescription = "Download")
        }
        IconButton(onClick = onExternal, modifier = Modifier.tvFocus()) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open in external player")
        }
    }
}

/**
 * Handles the dialogs around stream actions: where to download to, and what to do with torrent
 * streams when no streaming server is configured.
 */
class StreamDialogState {
    var downloadStream by mutableStateOf<Stream?>(null)
    var torrentStream by mutableStateOf<Stream?>(null)
}

@Composable
fun rememberStreamDialogState() = remember { StreamDialogState() }

@Composable
fun StreamDialogs(state: StreamDialogState, watch: WatchContext?, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val settings = context.container.settings

    state.downloadStream?.let { stream ->
        val saved by settings.downloadLocation.collectAsState()
        var selected by remember { mutableStateOf(saved ?: context.container.storage.defaultLocation()) }
        AlertDialog(
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

    state.torrentStream?.let { stream ->
        AlertDialog(
            onDismissRequest = { state.torrentStream = null },
            title = { Text("Torrent stream") },
            text = {
                Text(
                    "This is a torrent (P2P) stream. To play or download it inside the app, set a Stremio " +
                        "streaming server address in Settings (for example Stremio Service running on your " +
                        "PC or NAS). You can also open it in an installed torrent app.",
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    state.torrentStream = null
                    onOpenSettings()
                }) { Text("Settings") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    state.torrentStream = null
                    (StreamActions.target(context, stream) as? PlaybackTarget.External)?.let {
                        StreamActions.openUrl(context, it.url)
                    }
                }) { Text("Open torrent app") }
            },
        )
    }
}

/** Standard handlers wired to the dialogs; torrent streams without a server go to the explainer. */
@Composable
fun rememberStreamHandlers(dialogs: StreamDialogState, watch: () -> WatchContext?): StreamHandlers {
    val context = LocalContext.current
    val currentWatch by rememberUpdatedState(watch)
    return remember(dialogs) {
        fun needsServer(stream: Stream) =
            StreamResolver.isTorrent(stream) && context.container.settings.streamingServerUrl.value == null
        StreamHandlers(
            onPlay = { stream ->
                val w = currentWatch()
                when {
                    needsServer(stream) -> dialogs.torrentStream = stream
                    w != null -> StreamActions.play(context, stream, w)
                }
            },
            onDownload = { stream ->
                if (needsServer(stream)) dialogs.torrentStream = stream else dialogs.downloadStream = stream
            },
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
