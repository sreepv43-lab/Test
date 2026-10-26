package io.github.sreepv43.streamhub.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.delay

/** The side panels of the Cinema player. */
enum class HudPanel(val title: String, val subtitle: String) {
    Languages("Audio & subtitles", "Tracks in this video"),
    Sources("Switch source", "Keeps your place in the film"),
    Video("Video & playback", "Picture, speed and buffering"),
    Info("Playback information", "Live numbers from the player"),
    More("More options", "Without leaving playback"),
}

/** An audio or subtitle track; [id] is "group:track" in the player's current tracks. */
data class HudTrack(val id: String, val label: String, val detail: String, val selected: Boolean)

data class HudSource(val badge: String, val headline: String, val detail: String, val note: String, val playing: Boolean, val suggested: Boolean)

/** A message over the picture: still waiting for data, or this source can't play. */
data class HudProblem(val title: String, val message: String, val buffering: Boolean)

/** What the player screen does when the controls are used (implemented by PlayerActivity). */
interface PlayerHudActions {
    fun togglePlay()
    fun seekStep(forward: Boolean, repeat: Boolean)
    fun exit()
    fun selectAudio(id: String)
    fun selectSubtitle(id: String?)
    fun shiftSubtitles(deltaMs: Long?)
    fun setSubtitleScale(scale: Float)
    fun setSubtitleBox(box: Boolean)
    fun loadSources()
    fun switchSource(index: Int)
    fun setResizeMode(mode: Int)
    fun setSpeed(speed: Float)
    fun setStartBuffer(ms: Int)
    fun copyReport()
    fun restart()
    fun download()
    fun setSleep(minutes: Int)
    fun retry()
    fun dismissProblem()
}

/** Everything the Cinema player controls show; PlayerActivity keeps it up to date. */
class PlayerHudState(val actions: PlayerHudActions) {
    var title by mutableStateOf("")
    var subtitleLine by mutableStateOf("")
    var qualityLine by mutableStateOf("")
    var statusText by mutableStateOf("Starting…")
    var statusOk by mutableStateOf(true)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var aheadText by mutableStateOf<String?>(null)
    var cacheText by mutableStateOf<String?>(null)
    var playing by mutableStateOf(true)
    var seekTargetMs by mutableStateOf<Long?>(null)
    var controlsVisible by mutableStateOf(false)
    var panel by mutableStateOf<HudPanel?>(null)
    var audioTracks by mutableStateOf(emptyList<HudTrack>())
    var textTracks by mutableStateOf(emptyList<HudTrack>())
    var subtitleOffsetMs by mutableLongStateOf(0L)
    var subtitleScale by mutableFloatStateOf(1f)
    var subtitleBox by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var resizeMode by mutableIntStateOf(RESIZE_FIT)
    var startBufferMs by mutableIntStateOf(1_000)
    var sources by mutableStateOf<List<HudSource>?>(null)
    var sourcesMessage by mutableStateOf<String?>(null)
    var info by mutableStateOf(emptyList<Pair<String, String>>())
    var sleepMinutes by mutableIntStateOf(0)
    var problem by mutableStateOf<HudProblem?>(null)
    var message by mutableStateOf<String?>(null)
    var seekBackSeconds by mutableIntStateOf(10)
    var seekForwardSeconds by mutableIntStateOf(10)
    /** Bumped to put the selection on Play when the controls appear. */
    var focusPlay by mutableIntStateOf(0)

    companion object {
        // Same values as Media3's AspectRatioFrameLayout resize modes.
        const val RESIZE_FIT = 0
        const val RESIZE_STRETCH = 3
        const val RESIZE_ZOOM = 4
        const val SLEEP_END = -1
    }
}

private val Gold = Color(0xFFE7BD74)
private val Ink = Color(0xFF161B1B)
private val Txt = Color(0xFFF8F7F3)
private val Muted = Color(0xFFBAC3CC)
private val Green = Color(0xFF91DFBA)
private val Warn = Color(0xFFFFCC88)
private val DrawerBg = Color(0xF7101821)
private val ChoiceBg = Color(0xFF1A2631)
private val Line = Color(0x22FFFFFF)

/** The Cinema player's controls, drawn over the video (from the StreamHub TV player demo). */
@Composable
fun PlayerHud(state: PlayerHudState) {
    val playFocus = remember { FocusRequester() }
    val toolFocus = remember { HudPanel.entries.associateWith { FocusRequester() } }
    var lastPanel by remember { mutableStateOf<HudPanel?>(null) }
    LaunchedEffect(state.focusPlay) {
        if (state.focusPlay > 0) runCatching { playFocus.requestFocus() }
    }
    // Closing a panel puts the selection back on its button.
    LaunchedEffect(state.panel) {
        val closed = lastPanel
        lastPanel = state.panel
        if (state.panel == null && closed != null && state.controlsVisible) runCatching { toolFocus.getValue(closed).requestFocus() }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_MS)
            state.message = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        val showControls = state.controlsVisible && state.panel == null && state.problem == null
        if (state.controlsVisible || state.panel != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color(0x99010409), 0.14f to Color.Transparent, 0.72f to Color.Transparent, 1f to Color(0xD9020508))),
            )
        }
        if (showControls) {
            Header(state, Modifier.align(Alignment.TopStart))
            Controls(state, playFocus, toolFocus, Modifier.align(Alignment.BottomStart))
        }
        state.panel?.let { Drawer(state, it, Modifier.align(Alignment.CenterEnd)) }
        state.problem?.let { ProblemBox(state, it, Modifier.align(Alignment.Center)) }
        state.message?.let {
            Text(
                it,
                color = Color(0xFF17221E),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
                    .background(Color(0xFFEAF1E8), RoundedCornerShape(8.dp))
                    .padding(horizontal = 17.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Header(state: PlayerHudState, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .tvFocus(RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xA30E1721))
                .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(12.dp))
                .clickable { state.actions.exit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the film's page", tint = Txt)
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(state.title, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.subtitleLine.isNotEmpty()) {
                Text(state.subtitleLine, color = Color(0xFFD0D8D9), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier
                .background(Color(0x85081813), RoundedCornerShape(50))
                .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (state.statusOk) Green else Warn, CircleShape))
            Text(state.statusText, color = Color(0xFFEFFCF1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Controls(state: PlayerHudState, playFocus: FocusRequester, toolFocus: Map<HudPanel, FocusRequester>, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (state.qualityLine.isNotEmpty()) Text(state.qualityLine, color = Color(0xFFDFE7E5), fontSize = 12.sp)
            state.aheadText?.let {
                Text(
                    it,
                    color = Color(0xFFB4E5C7),
                    fontSize = 12.sp,
                    modifier = Modifier.background(Color(0x18A6F6C8), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            state.cacheText?.let { Text(it, color = Color(0xFFDFE7E5), fontSize = 12.sp) }
        }
        Timeline(state, Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                HudButton(Icons.Default.FastRewind, "Back ${state.seekBackSeconds}s") { state.actions.seekStep(forward = false, repeat = false) }
                HudButton(
                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.playing) "Pause" else "Play",
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus),
                ) { state.actions.togglePlay() }
                HudButton(Icons.Default.FastForward, "Forward ${state.seekForwardSeconds}s") { state.actions.seekStep(forward = true, repeat = false) }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(
                    HudPanel.Languages to (Icons.Default.Subtitles to "Languages"),
                    HudPanel.Sources to (Icons.AutoMirrored.Filled.List to "Sources"),
                    HudPanel.Video to (Icons.Default.OndemandVideo to "Video"),
                    HudPanel.Info to (Icons.Default.Info to "Info"),
                    HudPanel.More to (Icons.Default.MoreHoriz to "More"),
                ).forEach { (panel, look) ->
                    HudButton(look.first, look.second, modifier = Modifier.focusRequester(toolFocus.getValue(panel))) { state.panel = panel }
                }
            }
        }
    }
}

/** Elapsed time, the bar (played in gold, buffered lighter) and the time left; ← / → seek while it is selected. */
@Composable
private fun Timeline(state: PlayerHudState, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shown = state.seekTargetMs ?: state.positionMs
    val duration = state.durationMs
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(shown), color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier
                .weight(1f)
                .height(26.dp)
                .padding(horizontal = 12.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val forward = when (event.key) {
                        Key.DirectionLeft -> false
                        Key.DirectionRight -> true
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) state.actions.seekStep(forward, event.nativeKeyEvent.repeatCount > 0)
                    true
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val barHeight = (if (focused) 7.dp else 5.dp).toPx()
                val top = (size.height - barHeight) / 2
                val radius = CornerRadius(barHeight / 2)
                fun part(from: Float, to: Float, color: Color) {
                    if (to > from) drawRoundRect(color, Offset(size.width * from, top), Size(size.width * (to - from), barHeight), radius)
                }
                val played = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val buffered = if (duration > 0) (state.bufferedMs.toFloat() / duration).coerceIn(played, 1f) else played
                part(0f, 1f, Color(0x42FFFFFF))
                part(played, buffered, Color(0x79E6E5DF))
                part(0f, played, Gold)
                val thumb = (if (focused) 8.dp else 6.dp).toPx()
                val center = Offset(size.width * played, size.height / 2)
                if (focused) drawCircle(Gold.copy(alpha = 0.4f), thumb + 4.dp.toPx(), center)
                drawCircle(Color.White, thumb, center)
            }
            if (focused) {
                Text(
                    "← / →  seek",
                    color = Color(0xFF151A1E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(bottom = 30.dp)
                        .background(Color(0xFFECF1E9), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            if (duration > 0) "−" + formatClock((duration - shown).coerceAtLeast(0)) else "",
            color = Color(0xFFD2D7DA),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HudButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .widthIn(min = if (primary) 58.dp else 54.dp)
            .height(44.dp)
            .tvFocus(shape)
            .clip(shape)
            .background(if (primary) Gold else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        val color = if (primary) Ink else Color(0xFFEEEEEE)
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (primary) 20.dp else 18.dp))
        Text(label, color = color, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Drawer(state: PlayerHudState, panel: HudPanel, modifier: Modifier) {
    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { runCatching { first.requestFocus() } }
    // Info shows many numbers at once: a wider drawer fits them all without scrolling.
    Column(modifier.width(if (panel == HudPanel.Info) 640.dp else 400.dp).fillMaxHeight().background(DrawerBg)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(panel.title, color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(panel.subtitle, color = Muted, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .size(38.dp)
                    .tvFocus(RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .clickable { state.panel = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Txt) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (panel) {
                HudPanel.Languages -> LanguagesPanel(state, first)
                HudPanel.Sources -> SourcesPanel(state, first)
                HudPanel.Video -> VideoPanel(state, first)
                HudPanel.Info -> InfoPanel(state, first)
                HudPanel.More -> MorePanel(state, first)
            }
        }
        Text(
            "Back closes this panel without leaving the film.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tabs(tabs: List<String>, chosen: String, first: FocusRequester, onChoose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF070C12), RoundedCornerShape(11.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, tab ->
            val on = tab == chosen
            Text(
                tab,
                color = if (on) Color.White else Color(0xFFABB7C2),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .tvFocus(RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) Color(0xFF314050) else Color.Transparent)
                    .clickable { onChoose(tab) }
                    .padding(vertical = 10.dp),
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = Color(0xFF9FACBA), fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Choice(label: String, detail: String?, on: Boolean, tag: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(shape, scale = 1.02f)
            .clip(shape)
            .background(if (on) Color(0x16E7BD74) else ChoiceBg)
            .border(1.dp, if (on) Color(0x98D6B878) else Color(0x17FFFFFF), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(if (on) 5.dp else 2.dp, if (on) Gold else Color(0xFF728092), CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, color = Color(0xFFAEBBC6), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        tag?.let { Text(it, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Pills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val shape = RoundedCornerShape(8.dp)
            Text(
                label,
                color = if (on) Color(0xFF181D20) else Txt,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .tvFocus(shape)
                    .clip(shape)
                    .background(if (on) Gold else ChoiceBg)
                    .border(1.dp, if (on) Gold else Color(0x25FFFFFF), shape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Action(text: String, modifier: Modifier = Modifier, accent: Boolean = false, full: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        color = if (accent) Color(0xFF171C20) else Txt,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .then(if (full) Modifier.fillMaxWidth() else Modifier)
            .tvFocus(shape)
            .clip(shape)
            .background(if (accent) Gold else Color(0xFF1C2C3B))
            .border(1.dp, if (accent) Gold else Color(0x24FFFFFF), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color(0xFFEFD4A8), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
        .background(Color(0x10E7BD74), RoundedCornerShape(10.dp)).border(1.dp, Color(0x40E7BD74), RoundedCornerShape(10.dp)).padding(12.dp))
}

@Composable
private fun LanguagesPanel(state: PlayerHudState, first: FocusRequester) {
    var tab by remember { mutableStateOf("Audio") }
    Tabs(listOf("Audio", "Subtitles", "Style"), tab, first) { tab = it }
    when (tab) {
        "Audio" -> {
            GroupLabel("Audio tracks in this video")
            if (state.audioTracks.isEmpty()) Text("No audio tracks reported yet.", color = Muted, fontSize = 13.sp)
            state.audioTracks.forEachIndexed { i, track ->
                Choice(track.label, track.detail, track.selected, tag = "FIRST".takeIf { i == 0 && state.audioTracks.size > 1 }) {
                    state.actions.selectAudio(track.id)
                }
            }
            Note("The language you pick here is remembered for the next films.")
        }
        "Subtitles" -> {
            GroupLabel("Subtitles")
            Choice("Off", "No subtitles", state.textTracks.none { it.selected }) { state.actions.selectSubtitle(null) }
            state.textTracks.forEach { track -> Choice(track.label, track.detail, track.selected) { state.actions.selectSubtitle(track.id) } }
            GroupLabel("Subtitle timing")
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Action("−0.5 s") { state.actions.shiftSubtitles(-SUBTITLE_STEP_MS) }
                Action("+0.5 s") { state.actions.shiftSubtitles(SUBTITLE_STEP_MS) }
                Action("Reset") { state.actions.shiftSubtitles(null) }
            }
            Text(
                "Offset: " + if (state.subtitleOffsetMs == 0L) "none" else "%+.1f s".format(state.subtitleOffsetMs / 1000.0),
                color = Muted,
                fontSize = 12.sp,
            )
            Note("Timing can be changed for subtitles from addons (not for subtitles inside the video file).")
        }
        else -> {
            GroupLabel("Text size")
            Pills(listOf(0.8f to "Small", 1f to "Normal", 1.3f to "Large", 1.6f to "Extra large"), state.subtitleScale) { state.actions.setSubtitleScale(it) }
            GroupLabel("Background")
            Pills(listOf(false to "Outline", true to "Box"), state.subtitleBox) { state.actions.setSubtitleBox(it) }
            Note("Style changes apply straight away.")
        }
    }
}

@Composable
private fun SourcesPanel(state: PlayerHudState, first: FocusRequester) {
    LaunchedEffect(Unit) { state.actions.loadSources() }
    val sources = state.sources
    if (sources == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 3.dp)
            Text(state.sourcesMessage ?: "Asking your addons for sources…", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        }
        // Something must hold the selection while the list loads.
        Action("Close", modifier = Modifier.focusRequester(first)) { state.panel = null }
        return
    }
    if (sources.isEmpty()) {
        Text(state.sourcesMessage ?: "No other sources found.", color = Muted, fontSize = 13.sp)
        Action("Close", modifier = Modifier.focusRequester(first)) { state.panel = null }
        return
    }
    sources.forEachIndexed { i, source ->
        val shape = RoundedCornerShape(11.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                .tvFocus(shape, scale = 1.02f)
                .clip(shape)
                .background(ChoiceBg)
                .border(1.dp, if (source.playing) Gold else Color(0x19FFFFFF), shape)
                .clickable { state.actions.switchSource(i) }
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.badge, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(Color(0xFF273947), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
                if (source.suggested) Tag("SUGGESTED")
                if (source.playing) Tag("PLAYING ✓")
            }
            Text(source.headline, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (source.detail.isNotEmpty()) Text(source.detail, color = Color(0xFFB3C0C9), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(source.note, color = if (source.note.startsWith("No ")) Warn else Green, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
    Note("Switching keeps your place: the new source starts where you are now.")
}

@Composable
private fun Tag(text: String) {
    Text(text, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color(0x20E7BD74), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
}

@Composable
private fun VideoPanel(state: PlayerHudState, first: FocusRequester) {
    var tab by remember { mutableStateOf("Picture") }
    Tabs(listOf("Picture", "Speed", "Buffering"), tab, first) { tab = it }
    when (tab) {
        "Picture" -> {
            GroupLabel("Picture size")
            Choice("Fit to screen", "The whole picture, original shape", state.resizeMode == PlayerHudState.RESIZE_FIT) { state.actions.setResizeMode(PlayerHudState.RESIZE_FIT) }
            Choice("Fill screen", "Crops the edges to fill the screen", state.resizeMode == PlayerHudState.RESIZE_ZOOM) { state.actions.setResizeMode(PlayerHudState.RESIZE_ZOOM) }
            Choice("Stretch", "Fills the screen by stretching the picture", state.resizeMode == PlayerHudState.RESIZE_STRETCH) { state.actions.setResizeMode(PlayerHudState.RESIZE_STRETCH) }
            Note("Quality is chosen in Sources; picture size doesn't change the resolution.")
        }
        "Speed" -> {
            GroupLabel("Playback speed")
            Pills(listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "${it.toString().removeSuffix(".0")}×" }, state.speed) { state.actions.setSpeed(it) }
        }
        else -> {
            GroupLabel("Start playing after")
            Choice("Start sooner", "0.5 s loaded; may pause more often", state.startBufferMs == 500) { state.actions.setStartBuffer(500) }
            Choice("Balanced", "1 s loaded", state.startBufferMs == 1_000, tag = "SUGGESTED") { state.actions.setStartBuffer(1_000) }
            Choice("Buffer more", "5 s loaded; fewer pauses on slow streams", state.startBufferMs == 5_000) { state.actions.setStartBuffer(5_000) }
            Note("Applies the next time a video starts.")
        }
    }
}

@Composable
private fun InfoPanel(state: PlayerHudState, first: FocusRequester) {
    // Short numbers in a three-column grid, the formats side by side, the hash last: everything fits
    // on a TV screen without scrolling.
    val byLabel = state.info.toMap()
    val formats = listOf("Video", "Audio", "Source")
    val order = listOf("Position", "Playback speed", "Buffered", "Ready ahead", "Peers", "Speed", "Uploading", "Downloaded", "Completed", "Torrent")
    val tiles = order.mapNotNull { label -> byLabel[label]?.let { label to it } } +
        state.info.filter { (label, _) -> label !in order && label !in formats && label != "Info hash" }
    @Composable
    fun grid(items: List<Pair<String, String>>, columns: Int) = items.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) -> InfoTile(label, value, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    grid(tiles, 3)
    grid(formats.mapNotNull { label -> byLabel[label]?.let { label to it } }, 2)
    byLabel["Info hash"]?.let { InfoTile("Info hash", it, Modifier.fillMaxWidth(), small = true) }
    Action("Copy playback report", full = true, modifier = Modifier.padding(top = 4.dp).focusRequester(first)) { state.actions.copyReport() }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier, small: Boolean = false) {
    Column(modifier.background(ChoiceBg, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = Txt, fontSize = if (small) 12.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun MorePanel(state: PlayerHudState, first: FocusRequester) {
    GroupLabel("This video")
    Action("↺   Start from the beginning", full = true, modifier = Modifier.focusRequester(first)) { state.actions.restart() }
    Action("↓   Download this version", full = true) { state.actions.download() }
    Action("ⓘ   Back to the film's page", full = true) { state.actions.exit() }
    GroupLabel("Sleep timer")
    Pills(listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "60 min", PlayerHudState.SLEEP_END to "End of video"), state.sleepMinutes) { state.actions.setSleep(it) }
    if (state.qualityLine.isNotEmpty()) {
        GroupLabel("Current file")
        Text(state.qualityLine, color = Color(0xFFC8D3D6), fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1118), RoundedCornerShape(11.dp)).padding(13.dp))
    }
}

@Composable
private fun ProblemBox(state: PlayerHudState, problem: HudProblem, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(problem) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xAD050A0E)))
    Column(
        modifier
            .widthIn(max = 560.dp)
            .background(Color(0xE80E1821), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (problem.buffering) CircularProgressIndicator(Modifier.size(28.dp), color = Gold, strokeWidth = 3.dp)
        Text(problem.title, color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(problem.message, color = Color(0xFFBFCBD1), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (problem.buffering) {
                Action("Keep waiting", accent = true, modifier = Modifier.focusRequester(first)) { state.actions.dismissProblem() }
            } else {
                Action("Try again", accent = true, modifier = Modifier.focusRequester(first)) { state.actions.retry() }
            }
            Action("Change source") { state.problem = null; state.panel = HudPanel.Sources }
            if (problem.buffering) Action("Diagnostics") { state.problem = null; state.panel = HudPanel.Info }
            else Action("Back to the film") { state.actions.exit() }
        }
    }
}

internal fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private const val MESSAGE_MS = 3_500L
private const val SUBTITLE_STEP_MS = 500L
