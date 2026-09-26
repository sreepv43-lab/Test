package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.width
import io.github.sreepv43.streamhub.sync.Trakt
import io.github.sreepv43.streamhub.ui.components.flatTextFieldColors
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import android.text.format.Formatter
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.FlatChip
import io.github.sreepv43.streamhub.ui.components.panel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.Palette
import io.github.sreepv43.streamhub.ui.Palettes
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
import io.github.sreepv43.streamhub.update.Updater
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.ui.components.StorageChooser
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val location by settings.downloadLocation.collectAsStateWithLifecycle()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    val themeId by settings.theme.collectAsStateWithLifecycle()
    // Looking up storage volumes touches the disk; never do it on the UI thread.
    val defaultLocation by produceState<DownloadLocation?>(null) {
        value = withContext(Dispatchers.IO) { container.storage.defaultLocation() }
    }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        SettingsSection("Theme") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Palettes.all.forEach { palette ->
                    ThemeSwatch(palette, selected = palette.id == themeId, onClick = { settings.setTheme(palette.id) })
                }
            }
        }

        SettingsSection("Playback") {
            PlaybackSettings()
        }

        SettingsSection("Import from Stremio") {
            StremioImportSection()
        }

        SettingsSection("Trakt") {
            TraktSection()
        }

        SettingsSection("Updates") {
            UpdatesSection()
        }

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
                FlatButton(
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
            FlatButton(
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
private fun PlaybackSettings() {
    val settings = LocalContext.current.container.settings
    val maxResolution by settings.maxResolution.flow.collectAsStateWithLifecycle()
    val audio by settings.audioLanguage.flow.collectAsStateWithLifecycle()
    val subtitles by settings.subtitleLanguage.flow.collectAsStateWithLifecycle()
    val scale by settings.subtitleScale.flow.collectAsStateWithLifecycle()
    val autoplay by settings.autoplayNext.flow.collectAsStateWithLifecycle()
    val skip by settings.introSkipSeconds.flow.collectAsStateWithLifecycle()

    ChoiceRow("Best quality to pick (\"Play best\" and next episode)", listOf(720 to "720p", 1080 to "1080p", 2160 to "4K"), maxResolution) {
        settings.maxResolution.set(it)
    }
    ChoiceRow("Audio language", listOf("" to "Video's default") + languages(audio), audio) { settings.audioLanguage.set(it) }
    ChoiceRow("Subtitles", listOf("" to "Off") + languages(subtitles), subtitles) { settings.subtitleLanguage.set(it) }
    ChoiceRow("Subtitle size", listOf(0.8f to "Small", 1f to "Normal", 1.3f to "Large", 1.6f to "Extra large"), scale) {
        settings.subtitleScale.set(it)
    }
    ChoiceRow("Play the next episode automatically", listOf(true to "On", false to "Off"), autoplay) { settings.autoplayNext.set(it) }
    ChoiceRow("\"Skip intro\" jumps ahead by", listOf(60, 75, 85, 90, 105, 120).map { it to "$it s" }, skip) {
        settings.introSkipSeconds.set(it)
    }
    Text(
        "Languages you pick with the player's own buttons are remembered too. \"Skip intro\" appears in the first " +
            "minutes of an episode; after you use it once, later episodes of that show offer it where the intro started.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Common languages, plus [current] if it was picked in the player and isn't one of them. */
private fun languages(current: String): List<Pair<String, String>> {
    val common = listOf(
        "en", "hi", "ml", "ta", "te", "kn", "bn", "mr", "es", "fr", "de", "it", "pt", "ar", "ja", "ko", "zh",
    )
    val codes = if (current.isEmpty() || current in common) common else common + current
    return codes.map { code -> code to java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { code } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, name) -> FlatChip(name, selected = value == selected, onClick = { onSelect(value) }) }
    }
}

@Composable
private fun StremioImportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text(
        "Copy the addons, library (into My List) and titles in progress from your Stremio account. " +
            "Your password is only sent to Stremio and isn't saved.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Stremio email") },
            singleLine = true,
            colors = flatTextFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.width(320.dp).tvFocus(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            colors = flatTextFieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.width(260.dp).tvFocus(),
        )
        FlatButton(
            text = if (busy) "Importing…" else "Import",
            prominent = true,
            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            onClick = {
                busy = true
                status = null
                scope.launch {
                    status = runCatching { context.container.stremioImport.run(email.trim(), password) }.fold(
                        { "Imported ${it.addons} addons, ${it.library} titles into My List and ${it.inProgress} into Continue watching." },
                        { "Import failed: ${it.message}" },
                    )
                    password = ""
                    busy = false
                }
            },
        )
    }
    status?.let { Text(it) }
}

@Composable
private fun TraktSection() {
    val trakt = LocalContext.current.container.trakt
    val state by trakt.state.collectAsStateWithLifecycle()
    when (val s = state) {
        Trakt.State.NotConfigured -> Text(
            "Trakt sync isn't included in this build: it needs a Trakt API app (TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Trakt.State.Disconnected -> {
            Text(
                "Mark what you finish watching on Trakt, and keep your Trakt watchlist in My List.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlatButton(text = "Connect Trakt", prominent = true, onClick = trakt::startConnect)
        }
        is Trakt.State.WaitingForCode -> {
            Text("On your phone or computer, open ${s.url} and enter this code:")
            Text(s.code, style = MaterialTheme.typography.displaySmall, color = AppColors.accent)
            Text("Waiting for Trakt…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is Trakt.State.Connected -> {
            Text(s.message ?: "Connected. Finished movies and episodes are marked as watched on Trakt.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlatButton(text = "Sync watchlist", onClick = trakt::startSync)
                FlatButton(text = "Disconnect", onClick = trakt::disconnect)
            }
        }
        is Trakt.State.Failed -> {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            FlatButton(text = "Try again", onClick = trakt::startConnect)
        }
    }
}

@Composable
private fun UpdatesSection() {
    val context = LocalContext.current
    val updater = context.container.updater
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsStateWithLifecycle()
    val install = { file: java.io.File -> updater.install(file)?.let { StreamActions.toast(context, it) } }
    Text("You have StreamHub ${BuildConfig.VERSION_NAME}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (val s = state) {
        Updater.State.Idle -> {}
        Updater.State.Checking -> Text("Checking for updates…")
        Updater.State.UpToDate -> Text("You have the newest version.")
        is Updater.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
        is Updater.State.Available ->
            Text("Build ${s.update.build} is available (${Formatter.formatShortFileSize(context, s.update.size)}).")
        is Updater.State.Downloading ->
            Text("Downloading build ${s.update.build}… ${(s.progress * 100).toInt()}%")
        is Updater.State.Ready -> Text("Build ${s.update.build} is downloaded.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            is Updater.State.Available -> FlatButton(
                text = "Download and install",
                prominent = true,
                onClick = {
                    scope.launch {
                        updater.download(s.update)
                        (updater.state.value as? Updater.State.Ready)?.let { install(it.file) }
                    }
                },
            )
            is Updater.State.Ready -> FlatButton(text = "Install", prominent = true, onClick = { install(s.file) })
            Updater.State.Checking, is Updater.State.Downloading -> {}
            else -> FlatButton(text = "Check for updates", onClick = { scope.launch { updater.check() } })
        }
    }
    Text(
        "If Android says the app can't be installed, uninstall StreamHub once and install the new version " +
            "from the download link. That's only needed until the app is signed with its permanent key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().panel(shape).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

/** A small preview of a theme: its background, panel and accent colours, with the name below. */
@Composable
private fun ThemeSwatch(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 150.dp, height = 90.dp)
                .tvFocus(shape)
                .clip(shape)
                .background(palette.background)
                .border(if (selected) 3.dp else 1.dp, if (selected) AppColors.accent else AppColors.panelRaised, shape)
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(palette.panel))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(width = 64.dp, height = 22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.accent),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.text),
            )
        }
        Text(
            palette.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) AppColors.accent else AppColors.text,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
