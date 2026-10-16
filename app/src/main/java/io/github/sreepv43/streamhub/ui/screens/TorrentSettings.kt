package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.streamhub.AppContainer
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.torrent.CacheChoice
import io.github.sreepv43.streamhub.torrent.CachePolicy
import io.github.sreepv43.streamhub.torrent.CacheVolume
import io.github.sreepv43.streamhub.torrent.TorrentSummary
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.FlatChip
import io.github.sreepv43.streamhub.ui.components.StreamActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the storage status shows; gathered off the UI thread. */
private class StorageStatus(
    val volumes: List<CacheVolume>,
    val choice: CacheChoice,
    /** Torrent cache size on each volume (by volume id). */
    val cacheBytes: Map<String, Long>,
    val torrents: List<TorrentSummary>,
    val engineProblem: String?,
)

private fun storageStatus(container: AppContainer, fresh: Boolean): StorageStatus {
    // Free space changes while downloading: read it live, the slower checks are remembered.
    // Every look at a drive is time-limited: a drive that stops answering must not freeze this page.
    val storage = container.storage
    val volumes = storage.cacheVolumes(fresh).map { volume ->
        val dir = volume.dir ?: return@map volume
        storage.bounded(15, volume) { if (dir.exists()) volume.copy(freeBytes = dir.usableSpace) else volume }
    }
    return StorageStatus(
        volumes = volumes,
        choice = container.chooseTorrentCache(volumes),
        cacheBytes = volumes.mapNotNull { v ->
            v.dir?.let { dir -> storage.bounded(15, -1L) { if (dir.exists()) container.torrents.sizeOf(dir) else 0L } }
                ?.takeIf { it >= 0 }?.let { v.id to it }
        }.toMap(),
        torrents = container.torrents.summaries(),
        engineProblem = container.torrents.storageProblem,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TorrentsTab() {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val mode by settings.torrentCacheMode.flow.collectAsStateWithLifecycle()
    val volumeId by settings.torrentCacheVolumeId.flow.collectAsStateWithLifecycle()
    val volumeLabel by settings.torrentCacheVolumeLabel.flow.collectAsStateWithLifecycle()
    val cleanup by settings.torrentCacheCleanup.flow.collectAsStateWithLifecycle()
    val status by produceState<StorageStatus?>(null, mode, volumeId, cleanup) {
        var fresh = true
        while (true) {
            value = withContext(Dispatchers.IO) { storageStatus(container, fresh) }
            fresh = false
            delay(STATUS_REFRESH_MS)
        }
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    SettingsSection("Torrent cache location") {
        Text(
            "Torrent streams are written here while you watch, and read straight from here by the player. " +
                "With a USB drive chosen, torrent data only ever goes to that drive: if it is disconnected or " +
                "full, torrents stop with a message instead of filling the TV's own storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val drives = status?.volumes.orEmpty().filter { it.removable }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatChip("Internal storage (like Stremio)", selected = mode == CachePolicy.INTERNAL, onClick = { settings.torrentCacheMode.set(CachePolicy.INTERNAL) })
            FlatChip("Automatic", selected = mode == CachePolicy.AUTO, onClick = { settings.torrentCacheMode.set(CachePolicy.AUTO) })
            drives.forEach { drive ->
                FlatChip(
                    "USB: ${drive.label}",
                    selected = mode == CachePolicy.USB && volumeId == drive.id,
                    onClick = {
                        settings.torrentCacheVolumeId.set(drive.id)
                        settings.torrentCacheVolumeLabel.set(drive.label)
                        settings.torrentCacheMode.set(CachePolicy.USB)
                    },
                )
            }
            if (mode == CachePolicy.USB && drives.none { it.id == volumeId } && volumeId.isNotEmpty()) {
                val state = if (status == null) "checking…" else "not connected"
                FlatChip("USB: ${volumeLabel.ifEmpty { "drive" }} ($state)", selected = true, onClick = {})
            }
        }
        Text(
            "Automatic uses a connected drive with enough room (preferring one that can hold files over 4 GB), " +
                "otherwise internal storage. Drives are remembered by their ID, so any USB port works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsSection("Storage status") {
        val s = status
        if (s == null) {
            Text("Checking the drives…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            when (val choice = s.choice) {
                is CacheChoice.Ready -> StatusLine(
                    "Torrent data goes to",
                    choice.volume.label + (choice.volume.fileSystem?.let { " ($it)" } ?: "") + " · " + choice.dir.path,
                )
                is CacheChoice.Unavailable -> Warning(choice.message)
            }
            s.volumes.forEach { volume ->
                val details = buildList {
                    if (volume.totalBytes > 0) add("${size(volume.freeBytes)} free of ${size(volume.totalBytes)}")
                    volume.fileSystem?.let(::add)
                    add("cache ${size(s.cacheBytes[volume.id] ?: 0)}")
                    add("keeps ${CachePolicy.gb(CachePolicy.reserveFor(volume))} free")
                }.joinToString(" · ")
                StatusLine(if (volume.removable) "USB: ${volume.label}" else volume.label, details)
                volume.problem?.let { Warning("${volume.label}: $it") }
                if (volume.removable && volume.problem == null && volume.fileSystem == "FAT32") {
                    Warning("${volume.label} is FAT32: torrents and downloads over 4 GB can't be stored on it. Format it as exFAT.")
                }
                if (volume.dir != null && !CachePolicy.hasRoom(volume)) {
                    Warning("${volume.label} is low on space (${size(volume.freeBytes)} free). New torrents won't be stored on it.")
                }
            }
            if (mode == CachePolicy.USB && s.volumes.none { it.id == volumeId }) {
                Warning("${volumeLabel.ifEmpty { "The chosen USB drive" }} isn't connected: torrents can't play until it is.")
            }
            s.engineProblem?.let { problem -> if ((s.choice as? CacheChoice.Unavailable)?.message != problem) Warning(problem) }
            StatusLine("Download speed", size(s.torrents.sumOf { it.downloadRate.toLong() }) + "/s")
            if (s.torrents.isEmpty()) {
                StatusLine("Active torrents", "none")
            } else {
                s.torrents.forEach { t ->
                    val state = when {
                        t.problem != null -> "stopped"
                        t.readers > 0 -> "in use"
                        else -> "idle (removed when space is needed)"
                    }
                    StatusLine(
                        t.name,
                        "%.1f%% · %s/s · %d peers · %s".format(t.progress * 100, size(t.downloadRate.toLong()), t.peers, state),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatButton(
                text = "Clear torrent cache",
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { container.torrents.clearCache() }
                        StreamActions.toast(context, "Torrent cache cleared (torrents in use were kept; downloads are never touched)")
                    }
                },
            )
        }
    }

    SettingsSection("Cache cleanup") {
        ChoiceRow("Automatic cleanup of the temporary torrent cache", listOf(true to "On", false to "Off"), cleanup) {
            settings.torrentCacheCleanup.set(it)
        }
        val usbTarget by settings.torrentCacheUsbTargetGb.flow.collectAsStateWithLifecycle()
        ChoiceRow(
            "Cleanup target on a USB drive",
            listOf(50 to "50 GB", 100 to "100 GB", 200 to "200 GB", 500 to "500 GB", 1000 to "1 TB"),
            usbTarget,
        ) {
            settings.torrentCacheUsbTargetGb.set(it)
            scope.launch(Dispatchers.IO) { container.torrents.trimCache() }
        }
        val internalTarget by settings.torrentCacheLimitGb.flow.collectAsStateWithLifecycle()
        ChoiceRow(
            "Cache size on internal storage (like Stremio's Caching setting)",
            listOf(0 to "No cache", 2 to "2 GB", 5 to "5 GB", 10 to "10 GB", -1 to "Unlimited"),
            internalTarget,
        ) {
            settings.torrentCacheLimitGb.set(it)
            scope.launch(Dispatchers.IO) { container.torrents.trimCache() }
        }
        Text(
            "Once a minute, cached torrents nobody is watching are removed, oldest first, while the cache is over " +
                "its target or the drive has less free space than it keeps in reserve (100 GB on a large USB drive, " +
                "3 GB on internal storage). It is a target, not a hard limit: a torrent being played or downloaded " +
                "is never removed, so the cache can be larger while it is in use. Downloads are never removed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsSection("Torrents") {
        val wholeFile by settings.torrentDownloadWholeFile.flow.collectAsStateWithLifecycle()
        ChoiceRow(
            "While streaming, download",
            listOf(false to "Only what you watch (like Stremio)", true to "The whole file"),
            wholeFile,
        ) { settings.torrentDownloadWholeFile.set(it) }
        Text(
            "Like Stremio, only the part being watched and the next one to two minutes are downloaded, so the TV " +
                "writes far less and the cache stays small. Jumping ahead downloads from there. \"The whole file\" " +
                "keeps downloading the rest in the background (more writing, but seeking anywhere is instant later).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val prepare by settings.prepareTorrents.flow.collectAsStateWithLifecycle()
        ChoiceRow(
            "Get the best torrents ready as soon as a stream list opens (starts faster, uses some data)",
            listOf(true to "On", false to "Off"),
            prepare,
        ) { settings.prepareTorrents.set(it) }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Warning(text: String) {
    Text("⚠ $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private const val STATUS_REFRESH_MS = 2_000L
