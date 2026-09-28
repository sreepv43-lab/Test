package io.github.sreepv43.soundhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.ScreenTitle
import io.github.sreepv43.soundhub.ui.components.SectionHeader
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.formatSize
import io.github.sreepv43.soundhub.ui.components.transferText

enum class TransferAction {
    STOP,
    RETRY,
    FIND_SOURCE,
    REMOVE,
}

@Composable
fun TransfersScreen(onSearch: () -> Unit) {
    val container = LocalContext.current.container
    val transfers by container.client.transfers.collectAsStateWithLifecycle()
    TransfersLayout(
        transfers = transfers,
        onAction = { transfer, action ->
            when (action) {
                TransferAction.STOP -> container.client.cancel(transfer.id)
                TransferAction.RETRY -> container.client.retry(transfer.id)
                TransferAction.FIND_SOURCE -> {
                    container.searchAgainFor(transfer)
                    onSearch()
                }
                // Finished songs stay in the library; only unfinished files are deleted.
                TransferAction.REMOVE -> container.client.remove(transfer.id, deleteFile = transfer.status != TransferStatus.COMPLETED)
            }
        },
        onClearCompleted = {
            transfers.filter { it.status == TransferStatus.COMPLETED }.forEach { container.client.remove(it.id, deleteFile = false) }
        },
        onSearch = onSearch,
    )
}

private enum class Group(val title: String) {
    DOWNLOADING("Downloading"),
    WAITING("Waiting in queues"),
    ATTENTION("Needs attention"),
    COMPLETED("Completed"),
}

private fun groupOf(transfer: TransferInfo) = when (transfer.status) {
    TransferStatus.REQUESTING, TransferStatus.CONNECTING, TransferStatus.TRANSFERRING -> Group.DOWNLOADING
    TransferStatus.QUEUED -> Group.WAITING
    TransferStatus.FAILED, TransferStatus.CANCELLED -> Group.ATTENTION
    TransferStatus.COMPLETED -> Group.COMPLETED
}

/**
 * Songs being fetched, grouped by what is happening to them, newest first. OK on a song shows what
 * can be done with it, starting on the safe choice; Clear completed only tidies finished songs
 * off the list (they stay in the library).
 */
@Composable
fun TransfersLayout(
    transfers: List<TransferInfo>,
    onAction: (TransferInfo, TransferAction) -> Unit,
    onClearCompleted: () -> Unit,
    onSearch: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmRemoveId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Album covers are fetched alongside the songs; they aren't worth a row.
    val songs = remember(transfers) {
        transfers.filterNot { AudioFormats.extensionOf(it.filename) in IMAGE_EXTENSIONS }.asReversed()
    }
    val groups = remember(songs) { songs.groupBy(::groupOf) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("page-list"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            ScreenTitle(
                "Transfers",
                if (songs.isEmpty()) null
                else listOfNotNull(
                    groups[Group.DOWNLOADING]?.size?.let { "$it downloading" },
                    groups[Group.WAITING]?.size?.let { "$it waiting" },
                    groups[Group.ATTENTION]?.size?.let { "$it need attention" },
                    groups[Group.COMPLETED]?.size?.let { "$it completed" },
                ).joinToString(" · "),
            )
        }
        if (songs.isEmpty()) {
            item(key = "empty") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Note(
                        "Nothing is downloading. Songs you play or download from Search show here until they arrive; " +
                            "a song starts once its owner has a free upload slot.",
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton("Search", Icons.Default.Search, pageDefault = true, onClick = onSearch)
                }
            }
        }
        Group.entries.forEach { group ->
            val list = groups[group].orEmpty()
            if (list.isNotEmpty()) {
                item(key = "group-${group.name}") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeader("${group.title} (${list.size})", Modifier.weight(1f))
                        if (group == Group.COMPLETED) {
                            ActionButton("Clear completed", Icons.Default.ClearAll, primary = false, onClick = onClearCompleted)
                        }
                    }
                }
                transferRows(list) { selectedId = it.id }
            }
        }
    }

    val selected = transfers.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val name = selected.filename.substringAfterLast('\\')
        OptionsDialog(
            title = name,
            subtitle = "from ${selected.username} · ${transferText(selected)}" +
                if (selected.status == TransferStatus.COMPLETED) " · the song is in your library" else "",
            onDismiss = { selectedId = null },
            options = when (groupOf(selected)) {
                Group.DOWNLOADING, Group.WAITING -> listOf(
                    Option("Stop download", Icons.Default.Stop, destructive = true) { onAction(selected, TransferAction.STOP) },
                )
                Group.ATTENTION -> listOf(
                    Option("Try again", Icons.Default.Refresh) { onAction(selected, TransferAction.RETRY) },
                    Option("Find another source", Icons.Default.Search) { onAction(selected, TransferAction.FIND_SOURCE) },
                    Option("Remove…", Icons.Default.Delete, destructive = true) {
                        if (selected.bytes > 0) confirmRemoveId = selected.id else onAction(selected, TransferAction.REMOVE)
                    },
                )
                Group.COMPLETED -> listOf(
                    Option("Remove from this list") { onAction(selected, TransferAction.REMOVE) },
                )
            },
        )
    }
    val removing = transfers.firstOrNull { it.id == confirmRemoveId }
    if (removing != null) {
        TvDialog(
            title = "Remove this download?",
            onDismiss = { confirmRemoveId = null },
            buttons = listOf(
                DialogButton("Keep", primary = true) { confirmRemoveId = null },
                DialogButton("Remove") {
                    confirmRemoveId = null
                    onAction(removing, TransferAction.REMOVE)
                },
            ),
        ) {
            Note("The ${formatSize(removing.bytes)} downloaded so far of ${removing.filename.substringAfterLast('\\')} will be deleted.")
        }
    }
}

private fun LazyListScope.transferRows(list: List<TransferInfo>, onSelect: (TransferInfo) -> Unit) {
    items(list, key = { it.id }) { transfer ->
        ListRow(
            onClick = { onSelect(transfer) },
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
                if (transfer.active && transfer.size > 0 && transfer.bytes > 0) {
                    LinearProgressIndicator(
                        progress = { (transfer.bytes.toFloat() / transfer.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
