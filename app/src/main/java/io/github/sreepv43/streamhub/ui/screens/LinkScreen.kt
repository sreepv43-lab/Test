package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import io.github.sreepv43.streamhub.ui.components.StreamDialogs
import io.github.sreepv43.streamhub.ui.components.WatchContext
import io.github.sreepv43.streamhub.ui.components.rememberStreamDialogState
import io.github.sreepv43.streamhub.ui.components.rememberStreamHandlers
import io.github.sreepv43.streamhub.ui.components.tvFocus

/** Turns whatever was pasted or shared into a stream the app can play or download. */
fun streamForLink(input: String): Stream? {
    val source = TorrentLinks.normalizeSource(input)
    if (source.isEmpty()) return null
    val scheme = source.substringBefore(':', "").lowercase()
    return if (TorrentLinks.isTorrentSource(source) || scheme in setOf("http", "https", "content", "file")) {
        Stream(url = source)
    } else null
}

private fun watchForLink(source: String): WatchContext {
    val title = TorrentLinks.displayName(source)
        ?: source.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "Video"
    val id = "link:" + (TorrentLinks.infoHashFromMagnet(source) ?: source.hashCode().toString())
    return WatchContext(metaId = id, type = "movie", videoId = id, title = title)
}

/** Play or download any link: a video URL, a magnet link, an info hash or a .torrent file. */
@Composable
fun LinkScreen(initialUrl: String?) {
    var text by rememberSaveable(initialUrl) { mutableStateOf(initialUrl.orEmpty()) }
    val stream = streamForLink(text)
    val watch = stream?.url?.let(::watchForLink)
    val dialogs = rememberStreamDialogState()
    val handlers = rememberStreamHandlers(dialogs) { watch }
    StreamDialogs(dialogs, watch)
    val hint = when {
        text.isBlank() -> null
        stream == null -> "Not a playable link"
        StreamResolver.isTorrent(stream) -> "Torrent · ${watch?.title}"
        else -> "Direct link · ${watch?.title}"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Open a link", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Paste a video URL (mp4, mkv, HLS…), a magnet link, a torrent info hash or a link to a .torrent file. " +
                "Torrents are streamed and downloaded with the built-in torrent engine.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Link") },
            singleLine = true,
            isError = text.isNotBlank() && stream == null,
            supportingText = hint?.let { message -> @Composable { Text(message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp).tvFocus(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { stream?.let(handlers.onPlay) }, enabled = stream != null, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(" Play")
            }
            OutlinedButton(onClick = { stream?.let(handlers.onDownload) }, enabled = stream != null, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text(" Download")
            }
            OutlinedButton(onClick = { stream?.let(handlers.onExternal) }, enabled = stream != null, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Text(" External player")
            }
        }
    }
}
