package io.github.sreepv43.soundhub.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sreepv43.soundhub.audio.Channels
import io.github.sreepv43.soundhub.audio.CodecChoice
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.audio.Quality
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
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
                shape = RoundedCornerShape(16.dp),
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

/** A small round button with an icon (e.g. "More" at the end of a song row). */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    key: Any? = null,
    tag: String = label,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(48.dp)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(CircleShape, scale = 1.08f, key = key)
            .clip(CircleShape)
            .background(if (focused) AppColors.text else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) AppColors.onText else tint, modifier = Modifier.size(24.dp))
    }
}

/** A list row with a More button at its end, for songs: OK plays, More (Right, then OK) shows the rest. */
@Composable
fun RowWithMore(
    onClick: () -> Unit,
    onMore: () -> Unit,
    key: String,
    modifier: Modifier = Modifier,
    pageDefault: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ListRow(onClick = onClick, key = key, modifier = modifier.weight(1f), pageDefault = pageDefault, content = content)
        IconAction(Icons.Default.MoreVert, "More", key = "more-$key", tag = "more-$key", onClick = onMore)
    }
}

/**
 * One-press filter shortcuts with how many albums each finds, and "Filters" for the full panel
 * (quality, channels, format, availability). Chips never move, so the remote learns their places.
 */
@Composable
fun FilterShortcuts(filter: MusicFilter, count: (MusicFilter) -> Int, onSelect: (MusicFilter) -> Unit, onMore: () -> Unit) {
    val shortcut = MusicFilter.SHORTCUTS.firstOrNull { it.second == filter }?.first
    LazyRow(
        modifier = Modifier.tvRow().tvEnterAt { "filter-${shortcut ?: "more"}" }.testTag("filters"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(MusicFilter.SHORTCUTS, key = { it.first }) { (label, value) ->
            Chip("$label  ${count(value)}", value == filter, key = "filter-$label") { onSelect(value) }
        }
        item(key = "more") {
            Chip(
                if (shortcut == null) "Filters: ${filter.label}  ${count(filter)}" else "More filters…",
                selected = shortcut == null,
                key = "filter-more",
                onClick = onMore,
            )
        }
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
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier
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
            .background(if (focused) AppColors.row else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("seek-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (downloaded != null) {
                Box(Modifier.fillMaxWidth(downloaded).fillMaxHeight().background(AppColors.textDim.copy(alpha = 0.35f)))
            }
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(positionMs / 1000), style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    !focused -> downloaded?.let { "Downloaded ${(it * 100).toInt()}%" }.orEmpty()
                    seekable -> "◀ 10 s · OK play/pause · 10 s ▶"
                    else -> "Can't skip within this song yet"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
