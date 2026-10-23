package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.ChoiceRow
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ReadableText
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup
import io.github.sreepv43.soundhub.server.SmbMusicServer
import io.github.sreepv43.soundhub.server.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ServerField(val title: String, val hint: String) {
    HOST("Server address", "The server's name or address, like nas or 192.168.1.20"),
    SHARE("Share name", "The shared folder on the server, like music"),
    FOLDER("Folder in the share", "Where SoundHub keeps its music inside the share"),
    USER("Username", "Leave empty for a share anyone can open"),
    PASSWORD("Password", "Kept on this device only"),
}

/** Settings → Home server: an SMB share (NAS, PC, Samba) that songs are saved on. */
@Composable
internal fun ServerSettings() {
    val container = LocalContext.current.container
    val settings = container.settings
    val mode by settings.serverMode.flow.collectAsStateWithLifecycle()
    val host by settings.serverHost.flow.collectAsStateWithLifecycle()
    val share by settings.serverShare.flow.collectAsStateWithLifecycle()
    val folder by settings.serverFolder.flow.collectAsStateWithLifecycle()
    val user by settings.serverUser.flow.collectAsStateWithLifecycle()
    val password by settings.serverPassword.flow.collectAsStateWithLifecycle()
    val status by container.serverSync.status.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<ServerField?>(null) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configured = host.isNotBlank() && share.isNotBlank()

    SettingsList {
        item(key = "title") { ScreenTitle("Home server", "Save your music on a NAS or PC on your network") }
        item(key = "mode-title") { SectionHeader("What to do with songs") }
        listOf(
            Triple("off", "Keep songs on this device only", "The server isn't used"),
            Triple("copy", "Save a copy on the server", "Songs stay here too, and play from here"),
            Triple("only", "Keep songs only on the server", "After saving, the song is removed from this device and plays from the server over the network"),
        ).forEach { (value, title, subtitle) ->
            item(key = "mode-$value") {
                ChoiceRow(title, subtitle, selected = mode == value, key = "server-mode-$value") { settings.serverMode.set(value) }
            }
        }
        if (mode != "off" && !configured) {
            item(key = "mode-warn") { Note("Enter the server below to start saving.", WarningColor) }
        }
        item(key = "server-title") { SectionHeader("The server (an SMB / Windows share)") }
        fieldRow(ServerField.HOST, host, key = "server-host") { editing = it }
        fieldRow(ServerField.SHARE, share, key = "server-share") { editing = it }
        fieldRow(ServerField.FOLDER, folder, key = "server-folder") { editing = it }
        fieldRow(ServerField.USER, user, key = "server-user") { editing = it }
        item(key = "server-password") {
            ListRow(onClick = { editing = ServerField.PASSWORD }, key = "server-password", modifier = Modifier.testTag("server-password")) {
                TwoLines("Password", if (password.isNotEmpty()) "••••••••" else "Not set: press OK to enter", Modifier.weight(1f))
            }
        }
        item(key = "test") {
            Row(Modifier.tvButtonGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (testing) "Testing…" else "Test connection", Icons.Default.Refresh, primary = false) {
                    if (!configured) {
                        resultOk = false
                        result = "Enter the server address and share name first."
                    } else if (!testing) {
                        testing = true
                        result = null
                        scope.launch {
                            val config = ServerConfig(host, share, folder, user, password)
                            val problem = withContext(Dispatchers.IO) { SmbMusicServer(config).test() }
                            resultOk = problem == null
                            result = problem ?: "Connected. SoundHub can save music there."
                            testing = false
                        }
                    }
                }
            }
        }
        result?.let { text -> item(key = "result") { Text(text, color = if (resultOk) LosslessColor else WarningColor, style = MaterialTheme.typography.titleMedium) } }
        if (mode != "off" && configured) {
            item(key = "status-title") { SectionHeader("Saving") }
            item(key = "status") {
                Text(
                    when {
                        status.sending != null -> "Sending \"${status.sending}\"… ${status.waiting} waiting"
                        status.waiting > 0 -> "${status.waiting} songs waiting to be saved"
                        else -> "Everything is saved"
                    } + " · ${status.saved} songs on the server",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            status.error?.let { error -> item(key = "error") { Note("Couldn't reach the server: $error. SoundHub tries again by itself.", WarningColor) } }
        }
        item(key = "help") {
            ReadableText(
                "Works with any SMB share: a NAS (Synology, QNAP, TrueNAS…), a Windows or Mac shared folder, or a Linux " +
                    "Samba server. Songs download here first, then go to the server in the background. Songs kept only on " +
                    "the server need it to be on and reachable to play. The password is kept on this device only.",
            )
        }
    }
    editing?.let { field ->
        TextEntryDialog(
            title = field.title,
            initial = when (field) {
                ServerField.HOST -> host
                ServerField.SHARE -> share
                ServerField.FOLDER -> folder
                ServerField.USER -> user
                ServerField.PASSWORD -> ""
            },
            password = field == ServerField.PASSWORD,
            onDismiss = { editing = null },
            onDone = { text ->
                when (field) {
                    ServerField.HOST -> settings.serverHost.set(text.trim().removePrefix("\\\\").removePrefix("smb://").trim('/', '\\'))
                    ServerField.SHARE -> settings.serverShare.set(text.trim().trim('/', '\\'))
                    ServerField.FOLDER -> settings.serverFolder.set(text.trim().trim('/', '\\'))
                    ServerField.USER -> settings.serverUser.set(text.trim())
                    ServerField.PASSWORD -> settings.serverPassword.set(text)
                }
                result = null
                editing = null
            },
        )
    }
}

private fun LazyListScope.fieldRow(field: ServerField, value: String, key: String, onEdit: (ServerField) -> Unit) {
    item(key = key) {
        ListRow(onClick = { onEdit(field) }, key = key, modifier = Modifier.testTag(key)) {
            TwoLines(field.title, value.ifEmpty { "Not set: ${field.hint}" }, Modifier.weight(1f))
        }
    }
}
