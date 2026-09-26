package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.transferText

/** Songs being fetched from other users, newest first. */
@Composable
fun DownloadsScreen() {
    val container = LocalContext.current.container
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    val newestFirst = transfers.asReversed()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle(
                "Downloads",
                if (transfers.isEmpty()) "Nothing yet. Songs wait in the other user's queue until they have a free upload slot."
                else "${transfers.count { it.active }} active · finished songs go to your library",
            )
        }
        if (transfers.any { !it.active }) {
            item {
                ActionButton("Clear finished", Icons.Default.Close, primary = false) {
                    transfers.filter { !it.active }.forEach { container.client.remove(it.id, deleteFile = it.status != TransferStatus.COMPLETED) }
                }
            }
        }
        items(newestFirst, key = { it.id }) { transfer ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        transfer.filename.substringAfterLast('\\'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "from ${transfer.username} · ${transferText(transfer)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transfer.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    if (transfer.active && transfer.size > 0) {
                        LinearProgressIndicator(
                            progress = { (transfer.bytes.toFloat() / transfer.size).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                when {
                    transfer.active -> ActionButton("Cancel", primary = false) { container.client.cancel(transfer.id) }
                    transfer.status == TransferStatus.FAILED || transfer.status == TransferStatus.CANCELLED ->
                        ActionButton("Retry", Icons.Default.Refresh, primary = false) { container.client.retry(transfer.id) }
                }
            }
        }
    }
}
