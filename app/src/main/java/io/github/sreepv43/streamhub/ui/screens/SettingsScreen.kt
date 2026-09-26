package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import android.text.format.Formatter
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.sreepv43.streamhub.ui.components.FlatButton
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
