package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.player.AudioOutput
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ReadableText
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatSize

private enum class Editing { USERNAME, PASSWORD }

/**
 * Every setting is a row the remote can select; text is typed in a dialog that opens with the
 * keyboard, so moving through the page never pops the keyboard up.
 */
@Composable
fun SettingsScreen() {
    val container = LocalContext.current.container
    val settings = container.settings
    val state by container.client.state.collectAsStateWithLifecycle()
    val username by settings.username.flow.collectAsStateWithLifecycle()
    val password by settings.password.flow.collectAsStateWithLifecycle()
    val upnp by settings.upnp.flow.collectAsStateWithLifecycle()
    val portStatus by container.portStatus.collectAsStateWithLifecycle()
    val chosenFolder by settings.musicFolder.flow.collectAsStateWithLifecycle()
    val mode by settings.outputMode.flow.collectAsStateWithLifecycle()
    val folders = remember { container.musicFolders() }
    val currentFolder = remember(chosenFolder) { container.musicFolder().path }
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { ScreenTitle("Settings") }

        item(key = "account-title") { SectionTitle("Soulseek account") }
        item(key = "status") {
            val status = when (val s = state) {
                is ConnectionState.Connected -> "Signed in as ${s.username}"
                ConnectionState.Connecting -> "Connecting…"
                ConnectionState.Disconnected -> "Not signed in"
                is ConnectionState.Failed -> s.message
            }
            Text(status, color = if (state is ConnectionState.Connected) LosslessColor else MaterialTheme.colorScheme.onSurface)
        }
        item(key = "username") {
            ListRow(onClick = { editing = Editing.USERNAME }, key = "username") {
                TwoLines("Username", username.ifEmpty { "Not set — press OK to enter" }, Modifier.weight(1f))
            }
        }
        item(key = "password") {
            ListRow(onClick = { editing = Editing.PASSWORD }, key = "password") {
                TwoLines("Password", if (password.isEmpty()) "Not set — press OK to enter" else "••••••••", Modifier.weight(1f))
            }
        }
        item(key = "sign-in") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (state is ConnectionState.Connected) "Sign in again" else "Sign in") {
                    if (username.isNotBlank() && password.isNotEmpty()) container.signIn(username, password)
                    else editing = if (username.isBlank()) Editing.USERNAME else Editing.PASSWORD
                }
                if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                    ActionButton("Sign out", primary = false) { container.signOut() }
                }
            }
        }
        item(key = "account-note") {
            Note(
                "New to Soulseek? Choose any unused username and a password: the account is created the first " +
                    "time you sign in.",
            )
        }

        item(key = "output-title") { SectionTitle("Audio output") }
        listOf(
            Triple(AudioOutput.MODE_AUTO, "Automatic (recommended)", "Use what the TV or receiver tells Android it can take"),
            Triple(
                AudioOutput.MODE_HDMI,
                "HDMI to the receiver, or eARC",
                "Send Atmos in DD+ and TrueHD, DTS and multichannel audio undecoded",
            ),
            Triple(AudioOutput.MODE_ARC, "Through the TV over ARC", "Send Dolby Digital, DD+ (with Atmos) and DTS undecoded"),
        ).forEach { (value, title, subtitle) ->
            item(key = "output-$value") {
                ChoiceRow(title, subtitle, selected = mode == value, key = "output-$value") {
                    if (value != mode) {
                        settings.outputMode.set(value)
                        container.playback.setOutputMode(value)
                    }
                }
            }
        }

        item(key = "storage-title") { SectionTitle("Where songs are saved") }
        folders.forEach { folder ->
            item(key = "folder-${folder.dir.path}") {
                ChoiceRow(
                    "${folder.label} · ${formatSize(folder.freeBytes)} free",
                    folder.dir.path,
                    selected = folder.dir.path == currentFolder,
                    key = "folder-${folder.dir.path}",
                ) { settings.musicFolder.set(folder.dir.path) }
            }
        }

        item(key = "network-title") { SectionTitle("Network") }
        item(key = "upnp") {
            ListRow(
                onClick = {
                    settings.upnp.set(!upnp)
                    if (!upnp) container.openPort()
                },
                key = "upnp",
            ) {
                TwoLines(
                    "Open the port on the router (UPnP)",
                    portStatus ?: container.client.listenPort?.let { "Other users connect to this device on TCP port $it" },
                    Modifier.weight(1f),
                )
                Switch(checked = upnp, onCheckedChange = null)
            }
        }
        if (upnp && state is ConnectionState.Connected) {
            item(key = "upnp-again") {
                ActionButton("Ask the router again", Icons.Default.Refresh, primary = false) { container.openPort() }
            }
        }

        item(key = "about-title") { SectionTitle("About") }
        item(key = "about") {
            ReadableText(
                "SoundHub is a Soulseek client. Music on Soulseek is shared by its users; only download music you are " +
                    "allowed to have where you live. SoundHub doesn't share your files yet, and some users only send to " +
                    "people who share.",
            )
        }
    }

    when (editing) {
        Editing.USERNAME -> TextEntryDialog(
            title = "Soulseek username",
            initial = username,
            onDismiss = { editing = null },
            onDone = {
                settings.username.set(it.trim())
                editing = if (password.isEmpty()) Editing.PASSWORD else null
            },
        )
        Editing.PASSWORD -> TextEntryDialog(
            title = "Soulseek password",
            initial = "",
            password = true,
            onDismiss = { editing = null },
            onDone = { entered ->
                editing = null
                if (entered.isNotEmpty() && username.isNotBlank()) container.signIn(username, entered)
            },
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, key: String, onClick: () -> Unit) {
    ListRow(onClick = onClick, key = key) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
