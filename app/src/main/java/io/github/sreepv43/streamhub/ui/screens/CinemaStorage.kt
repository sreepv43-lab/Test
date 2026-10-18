package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.torrent.CacheVolume
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.components.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Cinema interface page title: small gold line, large title, one line of description. */
@Composable
internal fun CinemaPageTitle(eyebrow: String, title: String, description: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
        Text(eyebrow, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = AppColors.accent)
        Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = AppColors.textDim, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * One card per storage place (the TV and each connected drive): free space, a usage bar and its
 * state. Drives are looked at in the background and every look is time-limited, so a sleeping
 * USB hard disk never freezes the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageCards() {
    val container = LocalContext.current.container
    val volumes by produceState<List<CacheVolume>?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val storage = container.storage
                storage.cacheVolumes().map { volume ->
                    val dir = volume.dir ?: return@map volume
                    storage.bounded(15, volume) { if (dir.exists()) volume.copy(freeBytes = dir.usableSpace) else volume }
                }
            }
            delay(STORAGE_REFRESH_MS)
        }
    }
    val list = volumes
    if (list == null) {
        Text("Checking the drives…", color = AppColors.textDim)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        list.forEach { StorageCard(it) }
    }
}

@Composable
private fun StorageCard(volume: CacheVolume) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.widthIn(min = 300.dp, max = 420.dp).panel(shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(volume.label, style = MaterialTheme.typography.titleSmall)
            val state = when {
                volume.problem != null -> "Can't write"
                volume.removable -> "Connected"
                else -> null
            }
            state?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = if (volume.problem != null) MaterialTheme.colorScheme.error else Green,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .background(AppColors.panelRaised, RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        if (volume.totalBytes > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(size(volume.freeBytes), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(" free of ${size(volume.totalBytes)}", style = MaterialTheme.typography.bodyMedium, color = AppColors.textDim)
            }
            val used = (1f - volume.freeBytes.toFloat() / volume.totalBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { used },
                color = if (volume.removable) AppColors.accent else AppColors.textDim,
                trackColor = AppColors.panelRaised,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
        Text(
            listOfNotNull(if (volume.removable) "External USB drive" else "TV storage", volume.fileSystem).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.textDim,
        )
        volume.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private val Green = Color(0xFF92D5B5)
private const val STORAGE_REFRESH_MS = 5_000L
