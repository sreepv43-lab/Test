package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.player.AudioOutput
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TwoLines

/**
 * Dolby Atmos music: what this device can send to the receiver, Atmos searches (DD+ Atmos from
 * streaming rips, TrueHD Atmos from Blu-ray rips) and the Atmos songs already downloaded.
 */
@Composable
fun AtmosScreen(onPlaying: () -> Unit) {
    val container = LocalContext.current.container
    val session = container.atmosSearch
    val folders by session.folders.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<SearchFolder?>(null) }

    open?.let { folder ->
        FolderScreen(folder, onBack = { open = null }, onPlaying = onPlaying)
        return
    }
    val atmosFolders = remember(folders) { folders.filter { it.matches(FormatFilter.ATMOS) } }
    val atmosAlbums = remember(library) {
        LibraryStore.albums(library.filter { it.info.atmos != Atmos.NONE })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle("Dolby Atmos", "Sent to your receiver untouched, never decoded by this device") }
        item { OutputCard() }
        item {
            SearchBox(session, hint = "Artist or album (\"atmos\" is added for you)") { text ->
                session.start(if (text.contains("atmos", ignoreCase = true)) text else "$text atmos")
            }
        }
        item { SearchStatus(session, atmosFolders.size) }
        folderItems(atmosFolders, FormatFilter.ATMOS) { open = it }
        if (atmosAlbums.isNotEmpty()) {
            item {
                Text(
                    "In your library",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(atmosAlbums, key = { it.key }) { album ->
                ListRow(onClick = {
                    container.playLibrary(album.tracks)
                    onPlaying()
                }) {
                    TwoLines(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · "), Modifier.weight(1f))
                    FormatBadge(album.tracks.first().info)
                }
            }
        }
        item { AtmosGuide() }
    }
}

/** What Android reports for the HDMI output, and what that means for Atmos. */
@Composable
private fun OutputCard() {
    val context = LocalContext.current
    val container = context.container
    val mode by container.settings.outputMode.flow.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val report = remember(mode, refresh) { AudioOutput.report(context, mode) }
    val forced = mode != AudioOutput.MODE_AUTO
    val ddPlus = report.ddPlusAtmos || forced
    val trueHd = report.trueHd || mode == AudioOutput.MODE_HDMI
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("This device → ${report.connection}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(if (ddPlus) "DD+ Atmos ✓" else "DD+ Atmos ✗", if (ddPlus) AtmosColor else WarningColor)
            Badge(if (trueHd) "TrueHD Atmos ✓" else "TrueHD Atmos ✗", if (trueHd) AtmosColor else WarningColor)
            if (report.maxPcmChannels > 0) Badge("PCM up to ${report.maxPcmChannels} ch", LosslessColor)
            if (forced) Badge("Forced in Settings", WarningColor)
        }
        val advice = when {
            ddPlus && trueHd -> "Both kinds of Atmos files go to the receiver as they are. Pick any Atmos result."
            ddPlus -> "DD+ Atmos (rips from streaming services, usually .m4a/.ec3) reaches the receiver in Atmos. " +
                "TrueHD Atmos (Blu-ray rips, .mka/.thd) needs this box plugged into the receiver's HDMI input, " +
                "or eARC on both the TV and the receiver."
            else -> "This output doesn't say it takes Dolby audio. On the Google TV box: Settings → Display & Sound → " +
                "Advanced sound settings → Surround sound: Auto. If the box is plugged into the receiver and it still " +
                "shows ✗, set SoundHub's Settings → Audio output to \"HDMI to the receiver\"."
        }
        Text(advice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionButton("Check again", Icons.Default.Refresh, primary = false) { refresh++ }
    }
}

@Composable
private fun AtmosGuide() {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Getting Atmos to the receiver", style = MaterialTheme.typography.titleLarge)
        listOf(
            "Best: Google TV box → receiver's HDMI input → receiver's HDMI out → TV. The receiver gets every " +
                "format directly (DD+ Atmos, TrueHD Atmos, multichannel PCM) and passes the picture on to the TV.",
            "Through the TV: a TV with eARC and a receiver with only ARC connect as plain ARC. ARC carries DD+ Atmos " +
                "but not TrueHD Atmos or multichannel PCM. Set the TV's digital audio output to Passthrough/Auto.",
            "Atmos in FLAC/WAV folders is a multichannel PCM mix: surround, but no Atmos height objects.",
            "Blue badges are Atmos by name; ✓ means the file itself says so (checked when the download starts, " +
                "and again when it plays).",
        ).forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
