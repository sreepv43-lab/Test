package io.github.sreepv43.soundhub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sreepv43.soundhub.audio.Channels
import io.github.sreepv43.soundhub.audio.CodecChoice
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.audio.Quality
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.FocusColor
import io.github.sreepv43.soundhub.ui.LocalTvShell

/**
 * One choice in an [OptionsDialog]. [destructive] ones are shown in red and never selected first;
 * choices that lead to a next step ([closes] false) leave closing to that step.
 */
class Option(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val closes: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * What can be done with a song, album or download: a list the remote moves through, opening on
 * the first safe choice, with Cancel at the end (at the top when every choice removes something).
 * Choosing one closes the dialog first.
 */
@Composable
fun OptionsDialog(title: String, onDismiss: () -> Unit, options: List<Option>, subtitle: String? = null) {
    val first = remember { FocusRequester() }
    // Safe choices first, starting on the first of them; Cancel when every choice removes something.
    val ordered = options.sortedBy { it.destructive }
    val start = ordered.indexOfFirst { !it.destructive }
    var hasFocus by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalTvShell provides null) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = AppColors.panel,
                modifier = Modifier.widthIn(min = 320.dp, max = 560.dp).onFocusChanged { hasFocus = it.hasFocus },
            ) {
                Column(Modifier.padding(vertical = 20.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val cancel: @Composable () -> Unit = {
                            OptionRow(
                                "Cancel",
                                null,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                if (start < 0) Modifier.focusRequester(first) else Modifier,
                                onDismiss,
                            )
                        }
                        // The first row is always a safe one (the system selects it if our request is late).
                        if (start < 0) cancel()
                        ordered.forEachIndexed { index, option ->
                            OptionRow(
                                option.label,
                                option.icon,
                                if (option.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                if (index == start) Modifier.focusRequester(first) else Modifier,
                            ) {
                                if (option.closes) onDismiss()
                                option.onClick()
                            }
                        }
                        if (start >= 0) cancel()
                    }
                }
            }
        }
        FocusWhenShown(first) { hasFocus }
    }
}

@Composable
private fun OptionRow(label: String, icon: ImageVector?, color: Color, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape)
            .clip(shape)
            .background(if (focused) AppColors.rowFocused else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("option-$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/**
 * A small round button with an icon (e.g. "More" at the end of a song row). [flat] ones show only
 * the icon until selected.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    key: Any? = null,
    tag: String = label,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    flat: Boolean = false,
    size: Dp = 48.dp,
    pageDefault: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(CircleShape, scale = 1.08f, key = key, pageDefault = pageDefault)
            .clip(CircleShape)
            .background(
                when {
                    focused -> AppColors.text
                    flat -> Color.Transparent
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) AppColors.onText else tint, modifier = Modifier.size(size / 2))
    }
}

/**
 * A list row with a More button (…) at its end, for songs: OK plays, More (Right, then OK) shows
 * the rest. [compact] rows are for the narrow player panel.
 */
@Composable
fun RowWithMore(
    onClick: () -> Unit,
    onMore: () -> Unit,
    key: String,
    modifier: Modifier = Modifier,
    pageDefault: Boolean = false,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
        ListRow(
            onClick = onClick,
            key = key,
            modifier = modifier.weight(1f),
            pageDefault = pageDefault,
            padding = if (compact) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            content = content,
        )
        IconAction(
            Icons.Default.MoreHoriz,
            "More",
            key = "more-$key",
            tag = "more-$key",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            flat = true,
            size = if (compact) 40.dp else 48.dp,
            onClick = onMore,
        )
    }
}

/** An on/off choice as one row: OK flips the switch at its end. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    key: String,
    icon: ImageVector? = null,
    onToggle: (Boolean) -> Unit,
) {
    ListRow(onClick = { onToggle(!checked) }, key = key, modifier = Modifier.testTag(key)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (checked) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The file types (FLAC, ALAC, MP3, …) as one-press chips, each with how many albums it finds.
 * Chips never move, so the remote learns their places.
 */
@Composable
fun FileTypeFilter(selected: CodecChoice, count: (CodecChoice) -> Int, onSelect: (CodecChoice) -> Unit) {
    FilterRow("File type", "types", "type-${selected.name}") {
        items(CodecChoice.entries, key = { it.name }) { codec ->
            val label = if (codec == CodecChoice.ANY) "All types" else codec.label
            Chip("$label  ${count(codec)}", codec == selected, key = "type-${codec.name}") { onSelect(codec) }
        }
    }
}

/** Quality (lossless, hi-res, lossy) chips, and More filters… for channels and availability. */
@Composable
fun QualityFilter(
    selected: Quality,
    count: (Quality) -> Int,
    more: String?,
    onSelect: (Quality) -> Unit,
    onMore: () -> Unit,
) {
    FilterRow("Quality", "qualities", "quality-${selected.name}") {
        items(Quality.entries, key = { it.name }) { quality ->
            Chip("${quality.label}  ${count(quality)}", quality == selected, key = "quality-${quality.name}") { onSelect(quality) }
        }
        item(key = "more") {
            Chip(more?.let { "More: $it" } ?: "More filters…", selected = more != null, key = "filter-more", onClick = onMore)
        }
    }
}

/** A labelled row of chips; Left/Right stay in it, and it is entered at the chosen chip. */
@Composable
private fun FilterRow(label: String, tag: String, enterKey: String, content: LazyListScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(78.dp),
        )
        LazyRow(
            modifier = Modifier.tvRow().tvEnterAt { enterKey }.testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 5.dp),
            content = content,
        )
    }
}

/** Quality, channels, format and availability as separate choices; each press applies at once. */
@Composable
fun FilterDialog(filter: MusicFilter, showAvailability: Boolean, onChange: (MusicFilter) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(
        title = "Filters",
        onDismiss = onDismiss,
        buttons = listOf(
            DialogButton("Done", primary = true, onClick = onDismiss),
            DialogButton("Clear all") { onChange(MusicFilter()) },
        ),
        initialFocus = first,
    ) {
        FilterGroup("Quality", Quality.entries.map { it.label to (it == filter.quality) }, first) {
            onChange(filter.copy(quality = Quality.entries[it]))
        }
        FilterGroup("Channels", Channels.entries.map { it.label to (it == filter.channels) }) {
            onChange(filter.copy(channels = Channels.entries[it]))
        }
        FilterGroup("Format", CodecChoice.entries.map { it.label to (it == filter.codec) }) {
            onChange(filter.copy(codec = CodecChoice.entries[it]))
        }
        if (showAvailability) {
            FilterGroup("Availability", listOf("Any" to !filter.freeSlotOnly, "Slot reported free" to filter.freeSlotOnly)) {
                onChange(filter.copy(freeSlotOnly = it == 1))
            }
        }
    }
}

/** A titled set of chips; [focus] goes on the selected one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, chips: List<Pair<String, Boolean>>, focus: FocusRequester? = null, onPick: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEachIndexed { index, (label, selected) ->
            Chip(label, selected, modifier = if (focus != null && selected) Modifier.focusRequester(focus) else Modifier) { onPick(index) }
        }
    }
}

/**
 * The song's position. Selected, Left/Right jump 10 seconds back or forward and OK plays or
 * pauses; the lighter band shows how much of a song that is still downloading has arrived.
 * [compact] (the player panel) puts the times either side of the bar, with the song's length.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    downloaded: Float?,
    seekable: Boolean,
    onSeekBy: (Long) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seekModifier = modifier
        .fillMaxWidth()
        .tvClaimHorizontalKeys()
        .onFocusChanged { focused = it.hasFocus }
        .tvFocus(shape)
        .onKeyEvent { event ->
            val step = when (event.key) {
                Key.DirectionLeft -> -SEEK_STEP_MS
                Key.DirectionRight -> SEEK_STEP_MS
                else -> return@onKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown && seekable) onSeekBy(step)
            true
        }
        .clip(shape)
        .background(if (focused) AppColors.rowFocused else Color.Transparent)
        .clickable(onClick = onClick)
        .testTag("seek-bar")
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) {
        Row(seekModifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(positionMs / 1000), style = MaterialTheme.typography.labelLarge, color = dim)
            SeekTrack(progress, downloaded, focused, Modifier.weight(1f).padding(horizontal = 6.dp))
            Text(formatDuration(durationMs / 1000).ifEmpty { "–" }, style = MaterialTheme.typography.labelLarge, color = dim)
        }
        return
    }
    Column(seekModifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SeekTrack(progress, downloaded, focused, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(positionMs / 1000), style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    !focused -> downloaded?.let { "Downloaded ${(it * 100).toInt()}%" }.orEmpty()
                    seekable -> "◀ 10 s · OK play/pause · 10 s ▶"
                    else -> "Can't skip within this song yet"
                },
                style = MaterialTheme.typography.labelMedium,
                color = dim,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                if (durationMs > 0) "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0) / 1000) else "",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The bar itself: played part in the accent colour, the downloaded part lighter, a round thumb. */
@Composable
private fun SeekTrack(progress: Float, downloaded: Float?, focused: Boolean, modifier: Modifier) {
    val track = AppColors.textDim.copy(alpha = 0.18f)
    val band = AppColors.textDim.copy(alpha = 0.32f)
    val played = Accent
    val ring = if (focused) FocusColor else played
    Canvas(modifier.height(20.dp)) {
        val thumb = (if (focused) 9.dp else 7.dp).toPx()
        val height = (if (focused) 7.dp else 5.dp).toPx()
        val start = thumb
        val width = (size.width - 2 * thumb).coerceAtLeast(1f)
        val top = size.height / 2 - height / 2
        val corner = CornerRadius(height / 2)
        drawRoundRect(track, Offset(start, top), Size(width, height), corner)
        if (downloaded != null) drawRoundRect(band, Offset(start, top), Size(width * downloaded.coerceIn(0f, 1f), height), corner)
        drawRoundRect(played, Offset(start, top), Size(width * progress, height), corner)
        val center = Offset(start + width * progress, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.18f), thumb + 1.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
        drawCircle(Color.White, thumb, center)
        drawCircle(ring, thumb, center, style = Stroke(2.dp.toPx()))
    }
}

/** A heading between parts of a page. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 14.dp, bottom = 2.dp))
}

const val SEEK_STEP_MS = 10_000L

/** One of several choices (a radio button row). */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, key: String, onClick: () -> Unit) {
    ListRow(onClick = onClick, key = key, modifier = Modifier.testTag(key)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
