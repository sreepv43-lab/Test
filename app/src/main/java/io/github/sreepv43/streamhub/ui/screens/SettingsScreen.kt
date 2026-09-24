package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.streamhub.BuildConfig
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.ui.components.StorageChooser
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val location by settings.downloadLocation.collectAsStateWithLifecycle()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Download location", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick an internal folder, SD card or any connected USB drive. New drives appear here when plugged in.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 640.dp)) {
            StorageChooser(
                selected = location ?: container.storage.defaultLocation(),
                onSelect = settings::setDownloadLocation,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Torrents", style = MaterialTheme.typography.titleLarge)
        Text(
            "Torrent streams are played and downloaded by the built-in torrent engine: only the chosen file " +
                "is fetched, in order, so playback starts after a few seconds when there are enough peers. " +
                "Streaming data is kept in a temporary cache on the drive with the most free space and deleted " +
                "a few minutes after you stop watching.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { container.torrents.cacheSizeBytes() } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cache: " + (cacheSize?.let { Formatter.formatShortFileSize(context, it) } ?: "…"))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        cacheSize = withContext(Dispatchers.IO) {
                            container.torrents.clearCache()
                            container.torrents.cacheSizeBytes()
                        }
                        StreamActions.toast(context, "Torrent cache cleared (torrents in use were kept)")
                    }
                },
                modifier = Modifier.padding(start = 16.dp).tvFocus(),
            ) { Text("Clear torrent cache") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Addons", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            onClick = {
                scope.launch {
                    container.addons.refreshAll()
                    StreamActions.toast(context, "Addons updated")
                }
            },
            modifier = Modifier.tvFocus(),
        ) { Text("Update all addon manifests") }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "StreamHub ${BuildConfig.VERSION_NAME}. Compatible with Stremio addons. " +
                "Only stream and download content you have the rights to.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
