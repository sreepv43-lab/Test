package io.github.sreepv43.streamhub.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.CopyMatch
import io.github.sreepv43.streamhub.download.MediaAvailability
import io.github.sreepv43.streamhub.download.TitleRef
import io.github.sreepv43.streamhub.ui.AppColors

/** The movie or episode a title page is about, for finding its downloaded copies. */
fun titleRef(meta: Meta, videoId: String): TitleRef {
    val episode = CopyMatch.episodeOf(videoId)?.takeIf { meta.type == "series" }
    return TitleRef(meta.id, videoId, meta.name, CopyMatch.yearOf(meta.releaseInfo), episode?.first, episode?.second)
}

/** The downloaded copies of [ref] (kept up to date: a download finishing shows up at once). */
@Composable
fun rememberCopies(ref: TitleRef?): List<MediaAvailability.Copy> {
    val availability = LocalContext.current.container.availability
    val all by availability.copies.collectAsState()
    // Re-checks the TV's own files (a drive may have been unplugged since).
    LaunchedEffect(ref) { if (ref != null) availability.recheck() }
    return remember(ref, all) { ref?.let { availability.copiesFor(it, all) }.orEmpty() }
}

/**
 * "Downloaded" above a title's sources: Play downloaded (or Resume) for each copy that can play now,
 * where it is and what it is (quality, codec, HDR, audio, size); copies that can't play now say why,
 * and downloads in progress show how far they are. Nothing plays until a button is pressed.
 */
@Composable
fun DownloadedCopies(copies: List<MediaAvailability.Copy>, watch: WatchContext?, resumeMs: Long?, modifier: Modifier = Modifier) {
    if (copies.isEmpty()) return
    val context = LocalContext.current
    Column(modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Downloaded", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val firstPlayable = copies.firstOrNull { it.playable }
        copies.forEach { copy ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.panel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    when (copy.place) {
                        MediaAvailability.Place.SERVER -> Icons.Default.Dns
                        MediaAvailability.Place.DRIVE -> Icons.Default.Usb
                        MediaAvailability.Place.TV -> Icons.Default.PhoneAndroid
                    },
                    contentDescription = null,
                    tint = AppColors.textDim,
                )
                Column(Modifier.weight(1f, fill = false)) {
                    Text(copy.where, style = MaterialTheme.typography.titleSmall)
                    val line = listOfNotNull(
                        copy.details,
                        copy.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                        copy.downloading?.let { "Downloading ${(it * 100).toInt()}%" },
                        copy.problem?.let { "Unavailable: $it" },
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = AppColors.textDim)
                    }
                }
                if (copy.playable && watch != null) {
                    Box {
                        FlatButton(
                            if (resumeMs != null) "Resume downloaded" else "Play downloaded",
                            onClick = { StreamActions.playCopy(context, copy, watch) },
                            icon = Icons.Default.PlayArrow,
                            prominent = copy === firstPlayable,
                        )
                    }
                }
            }
        }
    }
}
