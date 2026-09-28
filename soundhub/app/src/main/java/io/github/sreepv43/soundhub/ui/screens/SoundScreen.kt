package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.Release
import io.github.sreepv43.soundhub.player.AudioOutput
import io.github.sreepv43.soundhub.player.OutputReport
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.LosslessColor
import io.github.sreepv43.soundhub.ui.WarningColor
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.Badge
import io.github.sreepv43.soundhub.ui.components.Chip
import io.github.sreepv43.soundhub.ui.components.ChoiceRow
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.FormatBadge
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.components.tvEnterAt
import io.github.sreepv43.soundhub.ui.components.tvRow

private enum class SoundTab(val label: String) { ATMOS("Atmos music"), OUTPUT("Audio output") }

/**
 * Dolby Atmos music (its own search, and the Atmos albums in the library) and where the sound
 * goes: what the playing file is, what this device says its output takes, the output mode chosen
 * here, and what the player actually sends. Reported facts and forced settings are kept apart.
 */
@Composable
fun SoundScreen(onOpenRelease: (Release) -> Unit, onOpenAlbum: (String) -> Unit, onSignIn: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    var tab by rememberSaveable { mutableStateOf(SoundTab.ATMOS) }
    val releases by container.atmosReleases.collectAsStateWithLifecycle()
    val query by container.atmosSearch.query.collectAsStateWithLifecycle()
    val library by container.library.tracks.collectAsStateWithLifecycle()
    val mode by container.settings.outputMode.flow.collectAsStateWithLifecycle()
    val output by container.playback.output.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val report = remember(refresh) { AudioOutput.report(context, AudioOutput.MODE_AUTO) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val atmosAlbums = remember(library) { LibraryStore.albums(library.filter { it.info.atmos != Atmos.NONE }) }
    val status = searchStatus(container.atmosSearch, releases.size, onSignIn)

    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { ScreenTitle("Atmos & sound") }
        item(key = "tabs") {
            LazyRow(
                Modifier.tvRow().tvEnterAt { "sound-tab-${tab.name}" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(SoundTab.entries, key = { it.name }) { entry ->
                    Chip(entry.label, entry == tab, key = "sound-tab-${entry.name}") { tab = entry }
                }
            }
        }
        when (tab) {
            SoundTab.ATMOS -> {
                item(key = "reach") {
                    ListRow(onClick = { tab = SoundTab.OUTPUT }, key = "reach") {
                        TwoLines(
                            "This device → ${report.connection}",
                            "DD+ Atmos ${reported(report.ddPlusAtmos)} · TrueHD ${reported(report.trueHd)} · " +
                                "output mode: ${modeName(mode)} · OK for details",
                            Modifier.weight(1f),
                        )
                    }
                }
                searchItems(
                    hint = "Artist or album (\"atmos\" is added for you)",
                    query = query.removeSuffix(" atmos"),
                    status = status,
                    releases = releases,
                    shown = releases,
                    filter = null,
                    count = { 0 },
                    onFilter = {},
                    onMoreFilters = {},
                    recent = emptyList(),
                    onSearch = { container.startSearch(it, atmos = true) },
                    onOpen = onOpenRelease,
                )
                if (atmosAlbums.isNotEmpty()) {
                    item(key = "library-title") { SectionHeader("Atmos in your library") }
                    items(atmosAlbums, key = { "library:" + it.key }) { album ->
                        ListRow(onClick = { onOpenAlbum(album.key) }, key = "library:" + album.key) {
                            AlbumCover(album.key, album.title, 52.dp)
                            TwoLines(album.title, listOfNotNull(album.artist, "${album.tracks.size} songs").joinToString(" · "), Modifier.weight(1f))
                            FormatBadge(album.tracks.first().info)
                        }
                    }
                }
            }
            SoundTab.OUTPUT -> {
                item(key = "now-title") { SectionHeader("Playing now") }
                item(key = "now") {
                    val state = output
                    if (state == null) {
                        Note("Play a song to see its format and what reaches your receiver.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("File: ${state.input}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Sent: ${state.output}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (state.passthrough) AtmosColor else MaterialTheme.colorScheme.onSurface,
                            )
                            state.warning?.let { Note(it, WarningColor) }
                        }
                    }
                }
                item(key = "device-title") { SectionHeader("What this device reports") }
                item(key = "device") { DeviceReport(report) }
                item(key = "device-refresh") {
                    ActionButton("Check again", Icons.Default.Refresh, primary = false, pageDefault = true) { refresh++ }
                }
                item(key = "mode-title") { SectionHeader("Output mode") }
                MODES.forEach { (value, title, subtitle) ->
                    item(key = "mode-$value") {
                        ChoiceRow(title, subtitle, selected = mode == value, key = "output-$value") {
                            if (value != mode) {
                                container.settings.outputMode.set(value)
                                container.playback.setOutputMode(value)
                            }
                        }
                    }
                }
                item(key = "mode-note") { Note(modeNote(mode)) }
                item(key = "help") {
                    ListRow(onClick = { showHelp = true }, key = "help") {
                        TwoLines("How to get Atmos to your receiver", "Connections, TV and box settings", Modifier.weight(1f))
                    }
                }
            }
        }
    }
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

private val MODES = listOf(
    Triple(AudioOutput.MODE_AUTO, "Automatic (recommended)", "Send what this device reports its output takes"),
    Triple(AudioOutput.MODE_HDMI, "HDMI to the receiver, or eARC", "Always send DD+ Atmos, TrueHD, DTS and multichannel undecoded"),
    Triple(AudioOutput.MODE_ARC, "Through the TV over ARC", "Always send Dolby Digital, DD+ (with Atmos) and DTS undecoded"),
)

private fun modeName(mode: String) = when (mode) {
    AudioOutput.MODE_HDMI -> "HDMI (forced)"
    AudioOutput.MODE_ARC -> "ARC (forced)"
    else -> "automatic"
}

private fun modeNote(mode: String) = when (mode) {
    AudioOutput.MODE_AUTO -> "SoundHub sends Dolby and DTS undecoded only when the device reports it can."
    else -> "Forced: SoundHub sends these formats undecoded whatever the device reports. If you hear silence or " +
        "noise, choose Automatic."
}

private fun reported(yes: Boolean) = if (yes) "reported" else "not reported"

/** The device's own answers, worded as reports (a forced output mode doesn't change them). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceReport(report: OutputReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Connection: ${report.connection}", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Dolby Digital" to report.dolbyDigital,
                "DD+ / Atmos" to report.ddPlusAtmos,
                "TrueHD" to report.trueHd,
                "DTS" to report.dts,
            ).forEach { (name, yes) ->
                Badge("$name: ${reported(yes)}", if (yes) AtmosColor else WarningColor)
            }
            if (report.maxPcmChannels > 0) Badge("PCM up to ${report.maxPcmChannels} channels", LosslessColor)
        }
    }
}

private fun advice(report: OutputReport, mode: String): String {
    val ddPlus = report.ddPlusAtmos || mode != AudioOutput.MODE_AUTO
    val trueHd = report.trueHd || mode == AudioOutput.MODE_HDMI
    val forced = if (mode != AudioOutput.MODE_AUTO) " (because the output mode is forced)" else ""
    return when {
        ddPlus && trueHd -> "Both kinds of Atmos files are sent to the receiver as they are$forced."
        ddPlus -> "DD+ Atmos (streaming rips, usually .m4a/.ec3) is sent to the receiver as it is$forced. TrueHD Atmos " +
            "(Blu-ray rips, .mka) needs this box plugged into the receiver's HDMI input, or eARC on both TV and receiver."
        else -> "This output doesn't report that it takes Dolby audio. On the Google TV box: Settings → Display & Sound → " +
            "Advanced sound settings → Surround sound: Auto. If the box is plugged into the receiver and this still " +
            "says not reported, choose \"HDMI to the receiver\" under Output mode."
    }
}
