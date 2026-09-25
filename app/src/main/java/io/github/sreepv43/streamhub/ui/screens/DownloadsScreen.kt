package io.github.sreepv43.streamhub.ui.screens

import io.github.sreepv43.streamhub.ui.AppColors
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadItem
import io.github.sreepv43.streamhub.ui.components.CenteredMessage
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.WatchContext
import io.github.sreepv43.streamhub.ui.components.DialogShape
import io.github.sreepv43.streamhub.ui.components.FlatIconButton
import io.github.sreepv43.streamhub.ui.components.panel
import io.github.sreepv43.streamhub.ui.components.tvFocus

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val downloader = context.container.downloader
    val items by downloader.items.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<DownloadItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        if (items.isEmpty()) {
            CenteredMessage("Nothing downloaded yet. Use the download button next to a stream.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadRow(
                        item,
                        onPlay = {
                            item.fileUri?.let { uri ->
                                StreamActions.playFile(
                                    context,
                                    uri,
                                    WatchContext(
                                        metaId = item.metaId ?: item.id,
                                        type = item.type ?: "movie",
                                        videoId = item.videoId ?: item.id,
                                        title = item.title,
                                        episodeTitle = item.subtitle,
                                        poster = item.poster,
                                    ),
                                )
                            }
                        },
                        onPause = { downloader.pause(item.id) },
                        onResume = { downloader.resume(item.id) },
                        onDelete = { toDelete = item },
                    )
                }
            }
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            containerColor = AppColors.panel,
            shape = DialogShape,
            onDismissRequest = { toDelete = null },
            title = { Text("Remove download?") },
            text = { Text(item.fileName ?: item.title) },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    downloader.remove(item.id, deleteFile = true)
                    toDelete = null
                }) { Text("Delete file") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    downloader.remove(item.id, deleteFile = false)
                    toDelete = null
                }) { Text("Remove from list only") }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .panel(RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
            item.poster?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                listOfNotNull(item.title, item.subtitle).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (item.fileName ?: "") + "  →  " + item.location.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.status != DownloadItem.Status.COMPLETED) {
                if (item.totalBytes > 0) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                } else if (item.status == DownloadItem.Status.RUNNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
            val size = Formatter.formatShortFileSize(context, item.downloadedBytes) +
                if (item.totalBytes > 0) " / " + Formatter.formatShortFileSize(context, item.totalBytes) else ""
            val status = when (item.status) {
                DownloadItem.Status.QUEUED -> "Waiting"
                DownloadItem.Status.RUNNING -> "Downloading ${(item.progress * 100).toInt()}%"
                DownloadItem.Status.PAUSED -> "Paused"
                DownloadItem.Status.COMPLETED -> "Completed"
                DownloadItem.Status.FAILED -> "Failed: ${item.error.orEmpty()}"
            }
            Text(
                "$status · $size",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadItem.Status.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (item.status) {
            DownloadItem.Status.COMPLETED -> FlatIconButton(Icons.Default.PlayArrow, "Play", onPlay, Modifier.padding(start = 8.dp))
            DownloadItem.Status.RUNNING, DownloadItem.Status.QUEUED -> FlatIconButton(Icons.Default.Pause, "Pause", onPause, Modifier.padding(start = 8.dp))
            DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> FlatIconButton(Icons.Default.Refresh, "Resume", onResume, Modifier.padding(start = 8.dp))
        }
        FlatIconButton(Icons.Default.Delete, "Delete", onDelete, Modifier.padding(start = 8.dp))
    }
}
