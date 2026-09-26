package io.github.sreepv43.soundhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.HiResColor
import io.github.sreepv43.soundhub.ui.LosslessColor
import java.util.Locale

@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The format label, coloured: blue for Atmos (✓ once the file itself confirmed it), gold for hi-res. */
@Composable
fun FormatBadge(info: AudioInfo) {
    val color = when {
        info.atmos != Atmos.NONE -> AtmosColor
        info.hiRes -> HiResColor
        info.codec.lossless -> LosslessColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        info.atmos == Atmos.VERIFIED -> "${info.label} ✓"
        info.hiRes && info.hiResGuessed && info.sampleRate == null -> "${info.label}?"
        else -> info.label
    }
    Badge(text, color)
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .tvFocus(shape)
            .clip(shape)
            .background(if (selected) Accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Format chips (only the ones with something in them), each with its count. */
@Composable
fun FilterRow(selected: FormatFilter, counts: Map<FormatFilter, Int>, onSelect: (FormatFilter) -> Unit) {
    val shown = FormatFilter.entries.filter { it == FormatFilter.ALL || it == selected || (counts[it] ?: 0) > 0 }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        items(shown, key = { it.name }) { filter ->
            val count = counts[filter]
            Chip(if (count != null) "${filter.label}  $count" else filter.label, filter == selected) { onSelect(filter) }
        }
    }
}

@Composable
fun ListRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(shape, scale = 1.02f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun TwoLines(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector? = null, primary: Boolean = true, onClick: () -> Unit) {
    val modifier = Modifier.tvFocus(RoundedCornerShape(50))
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
    if (primary) Button(onClick = onClick, modifier = modifier, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, content = content)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1L shl 20).toDouble())
    bytesPerSecond > 0 -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0)
    else -> ""
}

fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return ""
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** "Waiting in alice's queue (#3)", "45% · 1.2 MB/s", … */
fun transferText(transfer: TransferInfo): String = when (transfer.status) {
    TransferStatus.REQUESTING -> transfer.error?.let { "Trying again ($it)" } ?: "Asking ${transfer.username}…"
    TransferStatus.QUEUED -> "Waiting in ${transfer.username}'s queue" + (transfer.queuePosition?.let { " (#$it)" } ?: "")
    TransferStatus.CONNECTING -> "Connecting to ${transfer.username}…"
    TransferStatus.TRANSFERRING -> {
        val percent = if (transfer.size > 0) "${transfer.bytes * 100 / transfer.size}%" else formatSize(transfer.bytes)
        listOf(percent, formatSpeed(transfer.speed)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    TransferStatus.COMPLETED -> "Downloaded"
    TransferStatus.FAILED -> transfer.error ?: "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
}
