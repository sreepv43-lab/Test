package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadItem
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.FlatChip
import io.github.sreepv43.streamhub.ui.components.panel
import io.github.sreepv43.streamhub.ui.components.tvRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DownloadFilter(val label: String) { All("All"), Active("Active"), Completed("Completed") }

private val DownloadItem.active: Boolean
    get() = status == DownloadItem.Status.RUNNING || status == DownloadItem.Status.QUEUED ||
        status == DownloadItem.Status.PAUSED || status == DownloadItem.Status.FAILED

/**
 * Downloads in the Cinema interface: where new downloads are saved and how much room is left,
 * All / Active / Completed, and a card per download with its progress, state and actions.
 */
@Composable
internal fun CinemaDownloads(
    items: List<DownloadItem>,
    speeds: Map<String, Long>,
    waitingForWifi: Boolean,
    onPlay: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onMove: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.All) }
    val shown = when (filter) {
        DownloadFilter.All -> items
        DownloadFilter.Active -> items.filter { it.active }
        DownloadFilter.Completed -> items.filter { it.status == DownloadItem.Status.COMPLETED }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    CinemaPageTitle("READY WHEN YOU ARE", "Downloads", "Your films, with a clear view of every transfer.")
                }
                Row(Modifier.tvRow().padding(end = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items.any { it.status == DownloadItem.Status.RUNNING || it.status == DownloadItem.Status.QUEUED }) {
                        FlatButton("Pause all", onClick = onPauseAll, icon = Icons.Default.Pause, compact = true)
                    }
                    if (items.any { it.status == DownloadItem.Status.PAUSED || it.status == DownloadItem.Status.FAILED }) {
                        FlatButton("Resume all", onClick = onResumeAll, icon = Icons.Default.PlayArrow, compact = true)
                    }
                }
            }
        }
        item(key = "saving-to") { SavingTo(waitingForWifi, onOpenStorage) }
        item(key = "filters") {
            Row(Modifier.tvRow().padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DownloadFilter.entries.forEach { f -> FlatChip(f.label, selected = filter == f, onClick = { filter = f }) }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (items.isEmpty()) "Nothing downloaded yet. Use Download on a movie's page or next to a source."
                    else "Nothing here.",
                    color = AppColors.textDim,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(shown, key = { it.id }) { item ->
            DownloadCard(
                item,
                speed = speeds[item.id] ?: item.downloadRate,
                onPlay = { onPlay(item) },
                onPause = { onPause(item) },
                onResume = { onResume(item) },
                onMove = { onMove(item) },
                onDelete = { onDelete(item) },
            )
        }
    }
}

/** "Saving to Elements · 1.8 TB available", read off the UI thread with a time limit. */
@Composable
private fun SavingTo(waitingForWifi: Boolean, onOpenStorage: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val chosen by container.settings.downloadLocation.collectAsStateWithLifecycle()
    val place by produceState<Pair<DownloadLocation, Long?>?>(null, chosen) {
        value = withContext(Dispatchers.IO) {
            val location = chosen ?: container.storage.defaultLocation()
            location to container.storage.bounded(10, null) { container.storage.freeBytes(location) }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .panel(shape)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (waitingForWifi) AppColors.accent else Green, CircleShape))
                Text(
                    if (waitingForWifi) "Waiting for Wi-Fi (Settings: downloads use Wi-Fi only)"
                    else "Saving to " + (place?.first?.label ?: "…"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (waitingForWifi) AppColors.accent else Green,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                listOfNotNull(
                    place?.second?.let { Formatter.formatShortFileSize(context, it) + " available" },
                    "Downloads never move to another drive on their own",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textDim,
            )
        }
        FlatButton("Storage settings", onClick = onOpenStorage, icon = Icons.Default.Settings, compact = true)
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    speed: Long?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .panel(shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.panelRaised)) {
            item.poster?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.subtitle, item.fileName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val percent = (item.progress * 100).toInt()
            if (item.status == DownloadItem.Status.RUNNING && item.totalBytes <= 0) {
                LinearProgressIndicator(color = AppColors.accent, trackColor = AppColors.panelRaised, modifier = Modifier.fillMaxWidth(0.8f).height(4.dp))
            } else {
                LinearProgressIndicator(
                    progress = { if (item.status == DownloadItem.Status.COMPLETED) 1f else item.progress },
                    color = if (item.status == DownloadItem.Status.COMPLETED) Green else AppColors.accent,
                    trackColor = AppColors.panelRaised,
                    modifier = Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
            val (state, color) = when (item.status) {
                DownloadItem.Status.QUEUED -> (item.detail ?: "Queued") + " · $percent%" to AppColors.textDim
                DownloadItem.Status.RUNNING -> (item.detail ?: "Downloading") + " · $percent%" to AppColors.accent
                DownloadItem.Status.PAUSED -> "Paused · $percent%" to AppColors.textDim
                DownloadItem.Status.COMPLETED -> "Completed ✓" to Green
                DownloadItem.Status.FAILED -> (item.detail ?: "Stopped") + ": ${item.error.orEmpty()}" to MaterialTheme.colorScheme.error
            }
            Text(state, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val amount = if (item.totalBytes > 0) "${size(item.downloadedBytes)} / ${size(item.totalBytes)}" else size(item.downloadedBytes)
            val rate = if (item.status == DownloadItem.Status.RUNNING) speed?.let { size(it) + "/s" + timeLeft(item, it) } else null
            val peers = if (item.status == DownloadItem.Status.RUNNING) item.peers?.let { "$it peers" + (item.seeds?.let { s -> " ($s seeds)" } ?: "") } else null
            Text(
                listOfNotNull(amount, rate, peers, item.location.label).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.tvRow(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (item.status) {
                DownloadItem.Status.COMPLETED -> FlatButton("Play", onClick = onPlay, icon = Icons.Default.PlayArrow, compact = true)
                DownloadItem.Status.RUNNING, DownloadItem.Status.QUEUED -> FlatButton("Pause", onClick = onPause, icon = Icons.Default.Pause, compact = true)
                DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> {
                    FlatButton("Resume", onClick = onResume, icon = Icons.Default.Refresh, compact = true)
                    FlatButton("Change drive", onClick = onMove, compact = true)
                }
            }
            FlatButton("Remove", onClick = onDelete, icon = Icons.Default.Delete, compact = true)
        }
    }
}

private val Green = Color(0xFF92D5B5)
