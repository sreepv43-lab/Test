package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.player.AudioOutput
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.tvFocus

@Composable
fun SettingsScreen() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ScreenTitle("Settings") }
        item { AccountSection() }
        item { NetworkSection() }
        item { StorageSection() }
        item { OutputSection() }
        item { AboutSection() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AccountSection() {
    val container = LocalContext.current.container
    val state by container.client.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf(container.settings.username.value) }
    var password by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Soulseek account")
        val status = when (val s = state) {
            is ConnectionState.Connected -> "Signed in as ${s.username}"
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Disconnected -> "Not signed in"
            is ConnectionState.Failed -> s.message
        }
        Text(status, color = if (state is ConnectionState.Connected) LosslessColor else MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(6.dp), scale = 1f),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(6.dp), scale = 1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Sign in") {
                val pass = password.ifEmpty { container.settings.password.value }
                if (username.isNotBlank() && pass.isNotEmpty()) container.signIn(username, pass)
            }
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                ActionButton("Sign out", primary = false) { container.signOut() }
            }
        }
        Note(
            "New to Soulseek? Choose any unused username and a password: the account is created the first " +
                "time you sign in. Soulseek is a community of people sharing their own music collections.",
        )
    }
}

@Composable
private fun NetworkSection() {
    val container = LocalContext.current.container
    val state by container.client.state.collectAsStateWithLifecycle()
    val upnp by container.settings.upnp.flow.collectAsStateWithLifecycle()
    val portStatus by container.portStatus.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Network")
        val port = (state as? ConnectionState.Connected)?.listenPort ?: container.client.listenPort
        Note(if (port != null) "Other users connect to this device on TCP port $port." else "No listening port yet.")
        ToggleRow("Open the port on the router automatically (UPnP)", upnp) {
            container.settings.upnp.set(it)
            if (it) container.openPort()
        }
        portStatus?.let { Note(it) }
        if (upnp && state is ConnectionState.Connected) {
            ActionButton("Ask the router again", Icons.Default.Refresh, primary = false) { container.openPort() }
        }
    }
}

@Composable
private fun StorageSection() {
    val container = LocalContext.current.container
    val chosen by container.settings.musicFolder.flow.collectAsStateWithLifecycle()
    val folders = remember { container.musicFolders() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Where songs are saved")
        val current = container.musicFolder().path
        folders.forEach { folder ->
            ChoiceRow(
                "${folder.label} · ${formatSize(folder.freeBytes)} free",
                folder.dir.path,
                selected = folder.dir.path == current || (chosen.isEmpty() && folder == folders.first()),
            ) { container.settings.musicFolder.set(folder.dir.path) }
        }
        Note("Songs already downloaded stay where they are. Plug in a USB drive before opening SoundHub to see it here.")
    }
}

@Composable
private fun OutputSection() {
    val container = LocalContext.current.container
    val mode by container.settings.outputMode.flow.collectAsStateWithLifecycle()
    fun choose(value: String) {
        if (value == mode) return
        container.settings.outputMode.set(value)
        container.playback.setOutputMode(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Audio output")
        ChoiceRow(
            "Automatic (recommended)",
            "Use what the TV or receiver tells Android it can take",
            mode == AudioOutput.MODE_AUTO,
        ) { choose(AudioOutput.MODE_AUTO) }
        ChoiceRow(
            "HDMI to the receiver, or eARC",
            "Send Dolby Atmos in DD+ and TrueHD, DTS and multichannel audio undecoded",
            mode == AudioOutput.MODE_HDMI,
        ) { choose(AudioOutput.MODE_HDMI) }
        ChoiceRow(
            "Through the TV over ARC",
            "Send Dolby Digital, DD+ (with Atmos) and DTS undecoded; TrueHD is decoded here",
            mode == AudioOutput.MODE_ARC,
        ) { choose(AudioOutput.MODE_ARC) }
        Note(
            "Only force a mode when the Atmos page shows ✗ although your receiver supports the format: a forced " +
                "format the connection can't carry plays as silence.",
        )
    }
}

@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("About")
        Note(
            "SoundHub is a Soulseek client. Music on Soulseek is shared by its users; only download music you are " +
                "allowed to have where you live. SoundHub doesn't share your files yet, and some users only send to " +
                "people who share.",
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(shape, scale = 1.02f)
            .clip(shape)
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(shape, scale = 1.02f)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
