package io.github.sreepv43.streamhub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.data.LibraryItem
import io.github.sreepv43.streamhub.data.WatchEntry
import io.github.sreepv43.streamhub.ui.AppColors

/** The title a poster's options are for; [inHistory] when it came from Continue watching. */
data class PosterTarget(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val inHistory: Boolean = false,
) {
    companion object {
        fun of(meta: Meta) = PosterTarget(meta.id, meta.type, meta.name, meta.poster)
        fun of(item: LibraryItem) = PosterTarget(item.id, item.type, item.name, item.poster)
        fun of(entry: WatchEntry) = PosterTarget(entry.metaId, entry.type, entry.name, entry.poster, inHistory = true)
    }
}

class PosterMenuState {
    var target by mutableStateOf<PosterTarget?>(null)
}

@Composable
fun rememberPosterMenu() = remember { PosterMenuState() }

/** Options for a long-pressed poster: My List, and removing it from Continue watching. */
@Composable
fun PosterMenu(state: PosterMenuState) {
    val target = state.target ?: return
    val container = LocalContext.current.container
    val myList by container.library.items.collectAsState()
    val inList = myList.any { it.id == target.id }
    val first = remember { FocusRequester() }
    val close = { state.target = null }
    AlertDialog(
        containerColor = AppColors.panel,
        shape = DialogShape,
        onDismissRequest = close,
        title = { Text(target.name) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatButton(
                    text = if (inList) "Remove from My List" else "Add to My List",
                    icon = if (inList) Icons.Default.Check else Icons.Default.Add,
                    prominent = true,
                    modifier = Modifier.focusRequester(first),
                    onClick = {
                        if (inList) {
                            container.library.remove(target.id)
                            container.trakt.removeFromWatchlist(target.type, target.id)
                        } else {
                            container.library.add(LibraryItem(target.id, target.type, target.name, target.poster))
                            container.trakt.addToWatchlist(target.type, target.id)
                        }
                        close()
                    },
                )
                if (target.inHistory) {
                    FlatButton(
                        text = "Remove from Continue watching",
                        icon = Icons.Default.Delete,
                        onClick = {
                            container.history.hide(target.id)
                            close()
                        },
                    )
                }
                FlatButton(text = "Cancel", icon = Icons.Default.Close, onClick = close)
            }
        },
        confirmButton = {},
    )
    LaunchedEffect(target) {
        // The dialog is its own window; give it a frame to be laid out before moving the selection.
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/**
 * "Edit list" for Continue watching: every title with its own Remove button (so nobody has to
 * know about holding OK). Removed titles keep their resume point and return when played again.
 */
@Composable
fun ContinueWatchingEditor(onClose: () -> Unit) {
    val container = LocalContext.current.container
    val entries by container.history.entries.collectAsState()
    val shown = entries.filter { it.inContinueWatching }
    val first = remember { FocusRequester() }
    LaunchedEffect(shown.isEmpty()) { if (shown.isEmpty()) onClose() }
    AlertDialog(
        containerColor = AppColors.panel,
        shape = DialogShape,
        onDismissRequest = onClose,
        title = { Text("Continue watching") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.metaId }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entry.videoTitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        FlatButton(
                            text = "Remove",
                            icon = Icons.Default.Delete,
                            compact = true,
                            modifier = if (entry === shown.first()) Modifier.focusRequester(first) else Modifier,
                            onClick = { container.history.hide(entry.metaId) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatButton(text = "Remove all", icon = Icons.Default.Delete, onClick = {
                    shown.forEach { container.history.hide(it.metaId) }
                })
                FlatButton(text = "Done", icon = Icons.Default.Check, prominent = true, onClick = onClose)
            }
        },
    )
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}
