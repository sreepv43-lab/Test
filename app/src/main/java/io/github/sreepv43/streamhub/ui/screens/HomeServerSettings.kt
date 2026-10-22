package io.github.sreepv43.streamhub.ui.screens

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.TvTextField
import kotlinx.coroutines.launch

/**
 * Settings → Downloads → Home Server: pairs StreamHub with a StreamHub Server (address + the code it
 * shows) and shows its storages. Choosing one of them as the download location happens in the
 * location list above, like any drive.
 */
@Composable
internal fun HomeServerSection() {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val homeServer = container.homeServer
    val scope = rememberCoroutineScope()
    val address by settings.homeServerAddress.flow.collectAsStateWithLifecycle()
    val token by settings.homeServerToken.flow.collectAsStateWithLifecycle()
    val name by settings.homeServerName.flow.collectAsStateWithLifecycle()
    val online by homeServer.online.collectAsStateWithLifecycle()
    val status by homeServer.status.collectAsStateWithLifecycle()
    val paired = address.isNotEmpty() && token.isNotEmpty()
    var configuring by remember { mutableStateOf(false) }
    var newAddress by remember(address) { mutableStateOf(address) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Text(
        "A computer or NAS at home running StreamHub Server can do the downloading, so it continues with the TV " +
            "off. Its downloads show in Downloads like the TV's own, and finished films play from it.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (paired && !configuring) {
        StatusLine(
            name.ifEmpty { "Home Server" },
            homeServer.baseUrl().removePrefix("http://") + " · " + when (online) {
                true -> "connected"
                false -> "not reachable now (is the computer on?)"
                null -> "checking…"
            },
        )
        status?.storages?.forEach { storage ->
            StatusLine(
                storage.label,
                if (storage.available) {
                    "${Formatter.formatShortFileSize(context, storage.freeBytes)} free of ${Formatter.formatShortFileSize(context, storage.totalBytes)}"
                } else {
                    "not connected to the server"
                },
            )
        }
        status?.smbPath?.let { StatusLine("Other players (VLC…)", it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlatButton("Use it for downloads", icon = Icons.Default.Dns, onClick = {
                val storage = status?.let { s -> s.storages.firstOrNull { it.id == s.defaultStorage } ?: s.storages.firstOrNull() }
                settings.setDownloadLocation(
                    DownloadLocation(DownloadLocation.Kind.SERVER, storage?.id.orEmpty(), listOfNotNull(name.ifEmpty { "Home Server" }, storage?.label).joinToString(" · ")),
                )
                StreamActions.toast(context, "New downloads go to the Home Server")
            })
            FlatButton("Configure server", icon = Icons.Default.Refresh, onClick = { configuring = true })
        }
    } else {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(
                value = newAddress,
                onValueChange = { newAddress = it },
                label = "Server address",
                placeholder = "192.168.1.100",
                keyboardType = KeyboardType.Uri,
                supportingText = "The computer's address on your home network (port 8095 unless you changed it).",
            )
            TvTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = "Pairing code",
                placeholder = "6 digits shown by StreamHub Server",
                keyboardType = KeyboardType.NumberPassword,
            )
            problem?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlatButton(
                    text = if (busy) "Connecting…" else "Connect",
                    prominent = true,
                    enabled = !busy && newAddress.isNotBlank() && code.length == 6,
                    onClick = {
                        busy = true
                        problem = null
                        scope.launch {
                            runCatching { homeServer.pair(newAddress, code, "StreamHub on ${Build.MODEL}") }
                                .onSuccess { serverName ->
                                    configuring = false
                                    code = ""
                                    StreamActions.toast(context, "Paired with $serverName. Choose it as the download location above.")
                                }
                                .onFailure { problem = it.message }
                            busy = false
                        }
                    },
                )
                if (paired) FlatButton("Cancel", onClick = { configuring = false })
            }
            Text(
                "Get StreamHub Server from the same place as the app's updates (Linux PC: streamhub-server-linux.deb, " +
                    "double-click to install; other computers: streamhub-server.zip). Its control page on that computer " +
                    "shows the address and pairing code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
