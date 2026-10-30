package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.lyrics.Lrc
import io.github.sreepv43.soundhub.lyrics.Lyrics
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ReadableText
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.tvButtonGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface LyricsState {
    data object Loading : LyricsState

    data object Missing : LyricsState

    data class Found(val lyrics: Lyrics) : LyricsState
}

/** Full-screen lyrics for the song playing: the line being sung is highlighted and the page follows along. */
@Composable
fun LyricsScreen() {
    val container = LocalContext.current.container
    val playback = container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val current = item
    var refresh by remember { mutableIntStateOf(0) }
    // Shifts the lines earlier (+) or later (−), for a TV or receiver that delays the sound.
    var shiftMs by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = playback.player.currentPosition
            delay(200)
        }
    }
    val state by produceState<LyricsState>(LyricsState.Loading, current?.id, refresh) {
        value = LyricsState.Loading
        shiftMs = 0L
        value = current?.let { song ->
            withContext(Dispatchers.IO) { container.findLyrics(song, refresh > 0) }
        }?.let { LyricsState.Found(it) } ?: LyricsState.Missing
    }

    Column(Modifier.fillMaxSize().testTag("lyrics")) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
            ScreenTitle(
                current?.title ?: "Lyrics",
                current?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") },
            )
            Row(Modifier.tvButtonGroup().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val synced = (state as? LyricsState.Found)?.lyrics?.isSynced == true
                if (synced) {
                    ActionButton("Earlier", Icons.Default.Remove, primary = false, pageDefault = true) { shiftMs -= SHIFT_STEP_MS }
                    ActionButton("Later", Icons.Default.Add, primary = false) { shiftMs += SHIFT_STEP_MS }
                }
                ActionButton("Search again", Icons.Default.Refresh, primary = false, pageDefault = !synced) { refresh++ }
                if (synced && shiftMs != 0L) Note("Lyrics shifted ${"%+.1f".format(shiftMs / 1000.0)} s", modifier = Modifier.padding(top = 10.dp))
            }
        }
        when (val result = state) {
            LyricsState.Loading -> Note("Looking for the lyrics…", modifier = Modifier.padding(24.dp))
            LyricsState.Missing -> Note(
                if (current == null) "Play a song to see its lyrics." else "No lyrics found for this song. It may be an instrumental, or its name may not match.",
                modifier = Modifier.padding(24.dp),
            )
            is LyricsState.Found -> if (result.lyrics.isSynced) {
                SyncedLyrics(result.lyrics, position, shiftMs)
            } else {
                PlainLyrics(result.lyrics)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, position: Long, shiftMs: Long) {
    val lines = lyrics.synced
    val index by remember(lines, shiftMs) { derivedStateOf { Lrc.lineAt(lines, position + shiftMs) } }
    val listState = rememberLazyListState()
    LaunchedEffect(index) {
        if (index >= 0) {
            // Keep the line being sung a third of the way down, with the next ones in view.
            listState.animateScrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val active = i == index
            Text(
                line.text.ifEmpty { "♪" },
                style = if (active) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Accent else AppColors.textDim,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Note("Lyrics: ${lyrics.source}", modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    val paragraphs = remember(lyrics) { lyrics.plain.trim().split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Note("These lyrics aren't timed, so they don't follow the song. Scroll with the remote.") }
        itemsIndexed(paragraphs) { _, text -> ReadableText(text) }
        item { Note("Lyrics: ${lyrics.source}") }
    }
}

private const val SHIFT_STEP_MS = 500L
