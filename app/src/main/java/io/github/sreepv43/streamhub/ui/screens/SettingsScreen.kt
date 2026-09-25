package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import io.github.sreepv43.streamhub.ui.components.GlassButton
import io.github.sreepv43.streamhub.ui.components.glass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.github.sreepv43.streamhub.download.DownloadLocation
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
    // Looking up storage volumes touches the disk; never do it on the UI thread.
    val defaultLocation by produceState<DownloadLocation?>(null) {
        value = withContext(Dispatchers.IO) { container.storage.defaultLocation() }
    }
    val scope = rememberCoroutineScope()

    val glassBlur by settings.glassBlur.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        SettingsSection("Download location") {
            Text(
                "Pick internal storage or any connected USB drive or SD card. New drives appear here when plugged in.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.widthIn(max = 720.dp)) {
                StorageChooser(
                    selected = location ?: defaultLocation,
                    onSelect = settings::setDownloadLocation,
                )
            }
        }

        SettingsSection("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glass blur", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "Frosted blur behind the menu. Turn off if scrolling feels slow on this device."
                        } else {
                            "Needs Android 12 or newer; a tinted glass look is used instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = glassBlur,
                    onCheckedChange = settings::setGlassBlur,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    modifier = Modifier.tvFocus(RoundedCornerShape(50)),
                )
            }
        }

        SettingsSection("Torrents") {
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
                GlassButton(
                    text = "Clear torrent cache",
                    onClick = {
                        scope.launch {
                            cacheSize = withContext(Dispatchers.IO) {
                                container.torrents.clearCache()
                                container.torrents.cacheSizeBytes()
                            }
                            StreamActions.toast(context, "Torrent cache cleared (torrents in use were kept)")
                        }
                    },
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }

        SettingsSection("Addons") {
            GlassButton(
                text = "Update all addon manifests",
                onClick = {
                    scope.launch {
                        container.addons.refreshAll()
                        StreamActions.toast(context, "Addons updated")
                    }
                },
            )
        }

        Text(
            "StreamHub ${BuildConfig.VERSION_NAME}. Compatible with Stremio addons. " +
                "Only stream and download content you have the rights to.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().glass(shape, fillAlpha = 0.05f).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}
