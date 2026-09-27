package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.transferText

enum class TransferAction(val label: String) {
    CANCEL("Stop download"),
    RETRY("Try again"),
    REMOVE("Remove from list"),
}

@Composable
fun DownloadsScreen() {
    val container = LocalContext.current.container
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    DownloadsLayout(
        transfers = transfers,
        onAction = { transfer, action ->
            when (action) {
                TransferAction.CANCEL -> container.client.cancel(transfer.id)
                TransferAction.RETRY -> container.client.retry(transfer.id)
                TransferAction.REMOVE -> container.client.remove(transfer.id, deleteFile = transfer.status != TransferStatus.COMPLETED)
            }
        },
        onClearFinished = {
            transfers.filter { !it.active }.forEach {
                container.client.remove(it.id, deleteFile = it.status != TransferStatus.COMPLETED)
            }
        },
    )
}

/** Songs being fetched, newest first. Every row can be selected (OK shows what can be done with it). */
@Composable
fun DownloadsLayout(
    transfers: List<TransferInfo>,
    onAction: (TransferInfo, TransferAction) -> Unit,
    onClearFinished: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val newestFirst = transfers.asReversed()
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                "Downloads",
                if (transfers.isEmpty()) null
                else "${transfers.count { it.active }} active · finished songs go to your library · OK on a song for options",
            )
        }
        if (transfers.isEmpty()) {
            item(key = "empty") {
                Note("Nothing yet. A song starts when its owner has a free upload slot; until then it waits in their queue.")
            }
        }
        if (transfers.any { !it.active }) {
            item(key = "clear") { ActionButton("Clear finished", Icons.Default.Close, primary = false, onClick = onClearFinished) }
        }
        items(newestFirst, key = { it.id }) { transfer ->
            ListRow(
                onClick = { selectedId = transfer.id },
                key = "transfer-${transfer.id}",
                modifier = Modifier.testTag("transfer-${transfer.id}"),
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
            }
        }
    }
    val selected = transfers.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val actions = when {
            selected.active -> listOf(TransferAction.CANCEL)
            selected.status == TransferStatus.COMPLETED -> listOf(TransferAction.REMOVE)
            else -> listOf(TransferAction.RETRY, TransferAction.REMOVE)
        }
        TvDialog(
            title = selected.filename.substringAfterLast('\\'),
            onDismiss = { selectedId = null },
            buttons = actions.map { action ->
                DialogButton(action.label, primary = action == actions.first()) {
                    selectedId = null
                    onAction(selected, action)
                }
            } + DialogButton("Close") { selectedId = null },
        ) {
            Note("from ${selected.username} · ${transferText(selected)}")
        }
    }
}
