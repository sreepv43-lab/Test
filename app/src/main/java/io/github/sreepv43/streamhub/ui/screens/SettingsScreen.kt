package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import okhttp3.Request

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val server by settings.streamingServerUrl.collectAsStateWithLifecycle()
    val location by settings.downloadLocation.collectAsStateWithLifecycle()
    var serverText by rememberSaveable { mutableStateOf(server.orEmpty()) }
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
        Text("Torrent streaming server", style = MaterialTheme.typography.titleLarge)
        Text(
            "Many addons return torrent streams. To play or download them, run the Stremio streaming server " +
                "(Stremio Service / Stremio desktop) on a computer or NAS in your network and enter its address, " +
                "e.g. http://192.168.1.20:11470. Leave empty to only use direct (HTTP) streams.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = serverText,
                onValueChange = { serverText = it },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.widthIn(min = 320.dp, max = 520.dp).tvFocus(),
            )
            Button(
                onClick = {
                    settings.setStreamingServerUrl(serverText)
                    serverText = settings.streamingServerUrl.value.orEmpty()
                    val url = settings.streamingServerUrl.value
                    if (url != null) scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                container.http.newCall(Request.Builder().url("$url/settings").build()).execute()
                                    .use { it.isSuccessful }
                            }.getOrDefault(false)
                        }
                        StreamActions.toast(context, if (ok) "Streaming server reachable" else "Saved, but the server did not respond")
                    }
                },
                modifier = Modifier.padding(start = 12.dp).tvFocus(),
            ) { Text("Save & test") }
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
