package io.github.sreepv43.soundhub.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.SettingsKind
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.ChoiceRow
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ReadableText
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.formatSize

/** Settings, one row per area; each opens its own page. */
@Composable
fun SettingsScreen(onOpen: (SettingsKind) -> Unit, onSound: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val state by container.client.state.collectAsStateWithLifecycle()
    val chosenFolder by container.settings.musicFolder.flow.collectAsStateWithLifecycle()
    val upnp by container.settings.upnp.flow.collectAsStateWithLifecycle()
    val mode by container.settings.outputMode.flow.collectAsStateWithLifecycle()
    val folder = remember(chosenFolder) { container.musicFolders().firstOrNull { it.dir.path == container.musicFolder().path } }
    var allowed by remember { mutableStateOf(container.notificationsAllowed) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        container.settings.notificationsAsked.set(true)
    }
    SettingsList {
        item(key = "title") { ScreenTitle("Settings") }
        item(key = "account") {
            SettingsRow(
                "Soulseek account",
                when (val s = state) {
                    is ConnectionState.Connected -> "Signed in as ${s.username}"
                    ConnectionState.Connecting -> "Connecting…"
                    ConnectionState.Disconnected -> "Not signed in"
                    is ConnectionState.Failed -> "Not connected: ${s.message}"
                },
                key = "settings-ACCOUNT",
                pageDefault = true,
            ) { onOpen(SettingsKind.ACCOUNT) }
        }
        item(key = "storage") {
            SettingsRow(
                "Storage",
                folder?.let { "Saving to ${it.label} · ${formatSize(it.freeBytes)} free" } ?: "Where songs are saved",
                key = "settings-STORAGE",
            ) { onOpen(SettingsKind.STORAGE) }
        }
        item(key = "sound") {
            SettingsRow(
                "Atmos & sound",
                when (mode) {
                    "hdmi" -> "Output mode: HDMI to the receiver (forced)"
                    "arc" -> "Output mode: through the TV over ARC (forced)"
                    else -> "Output mode: automatic"
                },
                key = "settings-sound",
                onClick = onSound,
            )
        }
        item(key = "notifications") {
            SettingsRow(
                "Notifications",
                if (allowed) "Allowed: playback controls and download progress show in notifications"
                else "Off: press OK to allow playback controls and download progress",
                key = "settings-notifications",
            ) {
                when {
                    allowed || Build.VERSION.SDK_INT < 33 -> openNotificationSettings(context)
                    // Asked before and refused: Android won't ask again, so open its settings instead.
                    container.settings.notificationsAsked.value -> openNotificationSettings(context)
                    else -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        item(key = "network") {
            SettingsRow(
                "Network (advanced)",
                if (upnp) "Router port opening (UPnP) on" else "Router port opening (UPnP) off",
                key = "settings-NETWORK",
            ) { onOpen(SettingsKind.NETWORK) }
        }
        item(key = "about") { SettingsRow("About", "Version, and what SoundHub is", key = "settings-ABOUT") { onOpen(SettingsKind.ABOUT) } }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SettingsList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, key: String, pageDefault: Boolean = false, onClick: () -> Unit) {
    ListRow(onClick = onClick, key = key, pageDefault = pageDefault, modifier = Modifier.testTag(key)) {
        TwoLines(title, subtitle, Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsDetailScreen(kind: SettingsKind) {
    when (kind) {
        SettingsKind.ACCOUNT -> AccountSettings()
        SettingsKind.STORAGE -> StorageSettings()
        SettingsKind.NETWORK -> NetworkSettings()
        SettingsKind.ABOUT -> AboutSettings()
    }
}

private enum class Editing { USERNAME, PASSWORD }

/**
 * Username and password are only saved and used when Sign in is pressed, so a half-typed name
 * never triggers a sign-in attempt.
 */
@Composable
private fun AccountSettings() {
    val container = LocalContext.current.container
    val settings = container.settings
    val state by container.client.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf(settings.username.value) }
    // Not saved with the page's state, which Android may write to disk.
    var password by remember { mutableStateOf(settings.password.value) }
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }
    val changed = username.trim() != settings.username.value || password != settings.password.value
    fun signIn() {
        when {
            username.isBlank() -> editing = Editing.USERNAME
            password.isEmpty() -> editing = Editing.PASSWORD
            else -> container.signIn(username, password)
        }
    }
    SettingsList {
        item(key = "title") { ScreenTitle("Soulseek account") }
        item(key = "status") {
            val (text, color) = when (val s = state) {
                is ConnectionState.Connected -> "Signed in as ${s.username}" to LosslessColor
                ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurface
                ConnectionState.Disconnected -> "Not signed in" to MaterialTheme.colorScheme.onSurface
                is ConnectionState.Failed -> "Couldn't sign in: ${s.message}" to WarningColor
            }
            Text(text, color = color, style = MaterialTheme.typography.titleMedium)
        }
        item(key = "username") {
            ListRow(onClick = { editing = Editing.USERNAME }, key = "username", modifier = Modifier.testTag("username")) {
                TwoLines("Username", username.ifEmpty { "Not set: press OK to enter" }, Modifier.weight(1f))
            }
        }
        item(key = "password") {
            ListRow(onClick = { editing = Editing.PASSWORD }, key = "password", modifier = Modifier.testTag("password")) {
                TwoLines("Password", if (password.isEmpty()) "Not set: press OK to enter" else "••••••••", Modifier.weight(1f))
            }
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val connected = state is ConnectionState.Connected
                ActionButton(
                    if (connected && !changed) "Sign in again" else "Sign in",
                    Icons.AutoMirrored.Filled.Login,
                    pageDefault = !connected || changed,
                    onClick = ::signIn,
                )
                if (connected || state is ConnectionState.Connecting) {
                    ActionButton("Sign out", Icons.AutoMirrored.Filled.Logout, primary = false) { container.signOut() }
                }
            }
        }
        if (changed) item(key = "unsaved") { Note("Press Sign in to use the new details.", WarningColor) }
        item(key = "note") {
            ReadableText(
                "New to Soulseek? Choose any unused username and a password: the account is created the first time " +
                    "you sign in. The password is kept on this device only.",
            )
        }
    }
    when (editing) {
        Editing.USERNAME -> TextEntryDialog(
            title = "Soulseek username",
            initial = username,
            onDismiss = { editing = null },
            onDone = {
                username = it.trim()
                editing = if (password.isEmpty()) Editing.PASSWORD else null
            },
        )
        Editing.PASSWORD -> TextEntryDialog(
            title = "Soulseek password",
            initial = "",
            password = true,
            onDismiss = { editing = null },
            onDone = {
                if (it.isNotEmpty()) password = it
                editing = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun StorageSettings() {
    val container = LocalContext.current.container
    val settings = container.settings
    val chosenFolder by settings.musicFolder.flow.collectAsStateWithLifecycle()
    val tracks by container.library.tracks.collectAsStateWithLifecycle()
    val missing by container.missing.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val folders = remember(refresh) { container.musicFolders() }
    val currentFolder = remember(chosenFolder, refresh) { container.musicFolder().path }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(refresh) { container.refreshMissing() }
    SettingsList {
        item(key = "title") {
            ScreenTitle("Storage", "${tracks.size} songs · ${formatSize(tracks.sumOf { it.size })} in your library")
        }
        item(key = "where") { Text("New songs are saved to", style = MaterialTheme.typography.titleMedium) }
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
        item(key = "rescan") {
            ActionButton("Look for drives again", Icons.Default.Refresh, primary = false) { refresh++ }
        }
        if (missing.isNotEmpty()) {
            item(key = "missing") {
                Note(
                    "${missing.size} songs are on a drive that isn't connected. They stay in the library and play " +
                        "again once it is plugged in.",
                    WarningColor,
                )
            }
            item(key = "remove-missing") {
                ActionButton("Remove missing songs from the library…", primary = false) { confirm = true }
            }
        }
    }
    if (confirm) {
        TvDialog(
            title = "Remove ${missing.size} missing songs?",
            onDismiss = { confirm = false },
            buttons = listOf(
                DialogButton("Keep them", primary = true) { confirm = false },
                DialogButton("Remove") {
                    confirm = false
                    container.library.removeMissing()
                },
            ),
        ) {
            Note("If their drive is only unplugged, keep them. Files on the drive aren't touched either way.")
        }
    }
}

@Composable
private fun NetworkSettings() {
    val container = LocalContext.current.container
    val settings = container.settings
    val state by container.client.state.collectAsStateWithLifecycle()
    val upnp by settings.upnp.flow.collectAsStateWithLifecycle()
    val portStatus by container.portStatus.collectAsStateWithLifecycle()
    SettingsList {
        item(key = "title") { ScreenTitle("Network (advanced)") }
        item(key = "upnp") {
            ListRow(
                onClick = {
                    settings.upnp.set(!upnp)
                    if (!upnp) container.openPort()
                },
                key = "upnp",
                pageDefault = true,
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
        item(key = "explain") {
            ReadableText(
                "Soulseek users connect to each other directly. When the router forwards this port to this device, " +
                    "more users can send to you and results arrive faster. Most home routers do this by themselves " +
                    "when UPnP is on.",
            )
        }
    }
}

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    SettingsList {
        item(key = "title") { ScreenTitle("About SoundHub", "Version $version") }
        item(key = "about") {
            ReadableText(
                "SoundHub is a Soulseek client for listening on a TV or tablet, with Dolby Atmos sent untouched to " +
                    "your receiver. Music on Soulseek is shared by its users; only download music you are allowed to " +
                    "have where you live. SoundHub doesn't share your files yet, and some users only send to people " +
                    "who share.",
            )
        }
    }
}
