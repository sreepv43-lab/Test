package io.github.sreepv43.streamhub.ui.components

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.ServerLibraryItem
import io.github.sreepv43.streamhub.ui.AppColors

/** Where the Home Server files browser is: a storage (null = the list of storages) and a folder in it. */
class ServerFilesPlace(storage: String? = null, folder: String = "") {
    var storage by mutableStateOf(storage)
    var folder by mutableStateOf(folder)

    fun up() {
        if (folder.isEmpty()) storage = null else folder = folder.substringBeforeLast('/', "")
    }
}

/** The browser's place, kept while the page is open; [active] when it shows (Back goes up a folder then). */
@Composable
fun rememberServerFilesPlace(active: Boolean): ServerFilesPlace {
    var saved by rememberSaveable { mutableStateOf("") }
    val place = androidx.compose.runtime.remember {
        ServerFilesPlace(saved.substringBefore('\n').ifEmpty { null }, saved.substringAfter('\n', ""))
    }
    LaunchedEffect(place.storage, place.folder) { saved = (place.storage ?: "") + "\n" + place.folder }
    // Back goes up a folder before it leaves the page.
    BackHandler(enabled = active && place.storage != null) { place.up() }
    return place
}

/** A folder or file of the browser (computed from the server's library). */
private sealed interface Entry {
    val key: String

    data class Storage(val id: String, val label: String, val count: Int, val available: Boolean) : Entry {
        override val key get() = "storage-$id"
    }

    data class Folder(val name: String, val path: String, val count: Int) : Entry {
        override val key get() = "folder-$path"
    }

    data class File(val item: ServerLibraryItem) : Entry {
        override val key get() = "file-" + item.id
    }
}

/**
 * The Home Server's files like a file explorer: its drives, then their folders and films (every
 * video the server has, also ones it didn't download itself). OK on a film plays it from the
 * server; Back goes up a folder.
 */
fun LazyListScope.serverFiles(place: ServerFilesPlace, library: List<ServerLibraryItem>, online: Boolean?, onPlay: (ServerLibraryItem) -> Unit) {
    val storageId = place.storage
    val entries: List<Entry> = if (storageId == null) {
        library.groupBy { it.storageId.ifEmpty { it.storageLabel } }.map { (id, files) ->
            Entry.Storage(id, files.first().storageLabel.ifEmpty { "Home Server" }, files.size, files.any { it.available })
        }.sortedBy { it.label.lowercase() }
    } else {
        val inStorage = library.filter { it.storageId.ifEmpty { it.storageLabel } == storageId }
        val prefix = if (place.folder.isEmpty()) "" else place.folder + "/"
        val below = inStorage.filter { place.folder.isEmpty() || it.folder == place.folder || it.folder.startsWith(prefix) }
        val folders = below.filter { it.folder != place.folder }
            .groupBy { it.folder.removePrefix(prefix).substringBefore('/') }
            .map { (name, files) -> Entry.Folder(name, prefix + name, files.size) }
            .sortedBy { it.name.lowercase() }
        val files = below.filter { it.folder == place.folder }.sortedBy { it.fileName.lowercase() }.map { Entry.File(it) }
        folders + files
    }
    item(key = "server-files-bar") { BrowserBar(place, library, online) }
    if (entries.isEmpty()) {
        item(key = "server-files-empty") {
            Text(
                when {
                    online == false -> "The Home Server isn't reachable now. Is the computer on?"
                    library.isEmpty() -> "No films on the Home Server yet (or it is still looking through its drives)."
                    else -> "This folder is empty."
                },
                color = AppColors.textDim,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
    items(entries, key = { it.key }) { entry ->
        when (entry) {
            is Entry.Storage -> EntryRow(
                icon = Icons.Default.Dns,
                title = entry.label,
                detail = "${entry.count} film(s)" + if (entry.available) "" else " · drive not connected to the server",
                key = entry.key,
                dim = !entry.available,
            ) {
                place.storage = entry.id
                place.folder = ""
            }
            is Entry.Folder -> EntryRow(Icons.Default.Folder, entry.name, "${entry.count} film(s)", entry.key) { place.folder = entry.path }
            is Entry.File -> {
                val context = LocalContext.current
                val file = entry.item
                val media = file.media
                val detail = listOfNotNull(
                    file.fileName.takeIf { it != file.title },
                    Formatter.formatShortFileSize(context, file.sizeBytes),
                    media?.height?.let { h -> if (h >= 2000) "4K" else "${h}p" },
                    media?.hdr,
                    media?.audio,
                    "not available now".takeIf { !file.available },
                ).joinToString(" · ")
                EntryRow(Icons.Default.Movie, file.title, detail, entry.key, dim = !file.available) {
                    if (file.available) onPlay(file)
                    else StreamActions.toast(context, "This film's drive isn't connected to the Home Server now")
                }
            }
        }
    }
}

@Composable
private fun BrowserBar(place: ServerFilesPlace, library: List<ServerLibraryItem>, online: Boolean?) {
    val context = LocalContext.current
    val homeServer = context.container.homeServer
    LaunchedEffect(Unit) { homeServer.refresh() }
    val storageLabel = place.storage?.let { id -> library.firstOrNull { it.storageId.ifEmpty { it.storageLabel } == id }?.storageLabel }
    val path = listOfNotNull(homeServer.name(), storageLabel) + place.folder.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().tvRow().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (place.storage != null) FlatButton("Up", onClick = { place.up() }, icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true)
        Text(
            path.joinToString("  ›  "),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (online == false) Text("not reachable", color = AppColors.textDim, style = MaterialTheme.typography.bodySmall)
        FlatButton("Look for new films", onClick = {
            homeServer.rescan()
            StreamActions.toast(context, "The Home Server is looking through its drives…")
        }, icon = Icons.Default.Refresh, compact = true)
    }
}

@Composable
private fun EntryRow(icon: ImageVector, title: String, detail: String, key: String, dim: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .tvFocus(shape, key = key)
            .clip(shape)
            .panel(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .alpha(if (dim) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.accent)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
