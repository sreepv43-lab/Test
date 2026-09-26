package io.github.sreepv43.streamhub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
                            container.history.remove(target.id)
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
