package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.Album
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.player.AudioOutput
import io.github.sreepv43.soundhub.player.OutputReport
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines

/**
 * Dolby Atmos music: what this device can send to the receiver (one row; details on OK), Atmos
 * searches (DD+ Atmos from streaming rips, TrueHD Atmos from Blu-ray rips) and the Atmos albums
 * already in the library.
 */
@Composable
fun AtmosScreen(onOpenFolder: (SearchFolder) -> Unit, onOpenAlbum: (Album) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val session = container.atmosSearch
    val folders by session.folders.collectAsStateWithLifecycle()
    val query by session.query.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val mode by container.settings.outputMode.flow.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val report = remember(mode, refresh) { AudioOutput.report(context, mode) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val atmosFolders = remember(folders) { folders.filter { it.matches(FormatFilter.ATMOS) } }
    val atmosAlbums = remember(library) { LibraryStore.albums(library.filter { it.info.atmos != Atmos.NONE }) }
    SearchLayout(
        title = "Dolby Atmos",
        hint = "Artist or album (\"atmos\" is added for you)",
        query = query.removeSuffix(" atmos"),
        status = searchStatus(session, atmosFolders.size),
        folders = atmosFolders,
        filter = null,
        onFilter = {},
        recent = emptyList(),
        onSearch = { text -> container.startSearch(text, atmos = true) },
        onOpen = onOpenFolder,
        top = {
            item(key = "setup") {
                OutputRow(report, mode) {
                    refresh++
                    showHelp = true
                }
            }
        },
        bottom = {
            if (atmosAlbums.isNotEmpty()) {
                item(key = "library-title") {
                    Text("In your library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                }
                items(atmosAlbums, key = { "library:" + it.key }) { album ->
                    ListRow(onClick = { onOpenAlbum(album) }, key = "library:" + album.key) {
                        TwoLines(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · "), Modifier.weight(1f))
                        FormatBadge(album.tracks.first().info)
                    }
                }
            }
        },
    )
    if (showHelp) {
        TvDialog(
            title = "Getting Atmos to your receiver",
            onDismiss = { showHelp = false },
            buttons = listOf(DialogButton("Close", primary = true) { showHelp = false }),
        ) {
            Note(advice(report, mode))
            Note(
                "Best: Google TV box → receiver's HDMI input → receiver's HDMI out → TV. The receiver gets every " +
                    "format directly and passes the picture on to the TV.",
            )
            Note(
                "Through the TV: a TV with eARC and a receiver with only ARC connect as plain ARC, which carries " +
                    "DD+ Atmos but not TrueHD Atmos. Set the TV's digital audio output to Passthrough or Auto.",
            )
            Note("Blue badges are Atmos by name; ✓ means the file itself says so.")
        }
    }
}

private fun ddPlus(report: OutputReport, mode: String) = report.ddPlusAtmos || mode != AudioOutput.MODE_AUTO

private fun trueHd(report: OutputReport, mode: String) = report.trueHd || mode == AudioOutput.MODE_HDMI

/** "This box → HDMI · DD+ Atmos ✓ · TrueHD Atmos ✓", press OK for help. */
@Composable
private fun OutputRow(report: OutputReport, mode: String, onClick: () -> Unit) {
    val ddPlus = ddPlus(report, mode)
    val trueHd = trueHd(report, mode)
    ListRow(onClick = onClick, key = "setup") {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This device → ${report.connection}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Press OK for how to get Atmos to your receiver",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(if (ddPlus) "DD+ Atmos ✓" else "DD+ Atmos ✗", if (ddPlus) AtmosColor else WarningColor)
            Badge(if (trueHd) "TrueHD Atmos ✓" else "TrueHD Atmos ✗", if (trueHd) AtmosColor else WarningColor)
            if (report.maxPcmChannels > 0) Badge("PCM ${report.maxPcmChannels} ch", LosslessColor)
        }
    }
}

private fun advice(report: OutputReport, mode: String): String {
    val ddPlus = ddPlus(report, mode)
    val trueHd = trueHd(report, mode)
    val forced = if (mode != AudioOutput.MODE_AUTO) " (forced in Settings → Audio output)" else ""
    return when {
        ddPlus && trueHd -> "Both kinds of Atmos files go to the receiver as they are$forced."
        ddPlus -> "DD+ Atmos (streaming rips, usually .m4a/.ec3) reaches the receiver in Atmos$forced. TrueHD Atmos " +
            "(Blu-ray rips, .mka) needs this box plugged into the receiver's HDMI input, or eARC on both TV and receiver."
        else -> "This output doesn't say it takes Dolby audio. On the Google TV box: Settings → Display & Sound → " +
            "Advanced sound settings → Surround sound: Auto. If the box is plugged into the receiver and this still " +
            "shows ✗, set SoundHub's Settings → Audio output to \"HDMI to the receiver\"."
    }
}
