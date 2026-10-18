package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * One slim strip with a section per storage place (the TV and each connected drive): free space,
 * a usage bar and its state. Drives are looked at in the background and every look is
 * time-limited, so a sleeping USB hard disk never freezes the page.
 */
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
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().panel(shape).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val list = volumes
        if (list == null) {
            Text("Checking the drives…", color = AppColors.textDim)
        } else {
            list.forEach { StoragePlace(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun StoragePlace(volume: CacheVolume, modifier: Modifier) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(volume.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            val state = when {
                volume.problem != null -> "Can't write"
                volume.removable -> volume.fileSystem ?: "USB"
                else -> "TV"
            }
            Text(
                state,
                fontSize = 11.sp,
                color = if (volume.problem != null) MaterialTheme.colorScheme.error else if (volume.removable) Green else AppColors.textDim,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(AppColors.panelRaised, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        if (volume.totalBytes > 0) {
            Text(
                "${size(volume.freeBytes)} free of ${size(volume.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            val used = (1f - volume.freeBytes.toFloat() / volume.totalBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { used },
                color = if (volume.removable) AppColors.accent else AppColors.textDim,
                trackColor = AppColors.panelRaised,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        volume.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1) }
    }
}

private val Green = Color(0xFF92D5B5)
private const val STORAGE_REFRESH_MS = 5_000L
