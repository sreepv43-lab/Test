package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
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
    val sizeTenths by container.settings.lyricsSize.flow.collectAsStateWithLifecycle()
    val scale = (sizeTenths.toIntOrNull() ?: 12).coerceIn(MIN_SIZE, MAX_SIZE) / 10f
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
                ActionButton("Smaller", Icons.Default.TextDecrease, primary = false) {
                    container.settings.lyricsSize.set(((sizeTenths.toIntOrNull() ?: 12) - 2).coerceAtLeast(MIN_SIZE).toString())
                }
                ActionButton("Larger", Icons.Default.TextIncrease, primary = false) {
                    container.settings.lyricsSize.set(((sizeTenths.toIntOrNull() ?: 12) + 2).coerceAtMost(MAX_SIZE).toString())
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
                SyncedLyrics(result.lyrics, position, shiftMs, scale)
            } else {
                PlainLyrics(result.lyrics, scale)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, position: Long, shiftMs: Long, scale: Float) {
    val lines = lyrics.synced
    // Read afresh at every position update (a remembered derived value would keep the first position).
    val index = Lrc.lineAt(lines, position + shiftMs)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(index, scale) {
            if (index < 0) return@LaunchedEffect
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) {
                // Far away (just opened, or a seek): jump near it, the glide below finishes the job.
                listState.scrollToItem(index)
            }
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
            // Glide so the line being sung ends in the middle of the screen (no jump from line to line).
            // Item offsets are measured from the end of the top padding, so the screen's middle is the
            // middle of viewportStartOffset (minus the padding) and viewportEndOffset.
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val delta = (target.offset + target.size / 2f) - middle
            // A slow, gentle glide (easing in and out) is easier on the eyes than a quick hop.
            if (kotlin.math.abs(delta) > 1f) listState.animateScrollBy(delta, tween(durationMillis = GLIDE_MS, easing = FastOutSlowInEasing))
        }
        LazyColumn(
            Modifier.fillMaxSize().fadeEdges(),
            state = listState,
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = half, bottom = half),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lines) { i, line ->
                val active = i == index
                val base = MaterialTheme.typography.titleLarge
                val color by animateColorAsState(if (active) Accent else AppColors.textDim, tween(GLIDE_MS), label = "lyric-colour")
                val grow by animateFloatAsState(if (active) 1.1f else 1f, tween(GLIDE_MS), label = "lyric-size")
                Text(
                    line.text.ifEmpty { "♪" },
                    style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale),
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = grow
                        scaleY = grow
                    },
                )
            }
            item { Note("Lyrics: ${lyrics.source}", modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, scale: Float) {
    val paragraphs = remember(lyrics) { lyrics.plain.trim().split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() } }
    val base = MaterialTheme.typography.titleLarge
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Note("These lyrics aren't timed, so they don't follow the song. Scroll with the remote.") }
        itemsIndexed(paragraphs) { _, text ->
            ReadableText(text, style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale, textAlign = TextAlign.Center))
        }
        item { Note("Lyrics: ${lyrics.source}") }
    }
}

private const val MIN_SIZE = 8
private const val MAX_SIZE = 22
private const val SHIFT_STEP_MS = 500L
private const val GLIDE_MS = 1_100

/** Lines fade in at the bottom and out at the top instead of being cut off by the edge. */
private fun Modifier.fadeEdges(height: androidx.compose.ui.unit.Dp = 72.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height / 3)
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent),
                startY = 0f,
                endY = h,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
        )
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black),
                startY = size.height - h,
                endY = size.height,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
        )
    }
