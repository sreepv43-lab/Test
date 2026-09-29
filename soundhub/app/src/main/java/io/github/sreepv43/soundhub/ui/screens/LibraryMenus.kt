package io.github.sreepv43.soundhub.ui.screens

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.AppContainer
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.Option
import io.github.sreepv43.soundhub.ui.components.OptionsDialog
import io.github.sreepv43.soundhub.ui.components.TextEntryDialog
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.toast
import java.io.File

/**
 * Plays library songs from [start] (the whole list stays in the queue). When [start] can't be
 * played, says why instead of playing some other song.
 */
fun playOrExplain(
    context: Context,
    container: AppContainer,
    tracks: List<LibraryTrack>,
    start: LibraryTrack? = null,
    shuffle: Boolean = false,
): Boolean {
    if (container.playLibrary(tracks, start, shuffle)) return true
    toast(
        context,
        when {
            start == null -> "None of these songs can be played right now (is their drive connected?)"
            !File(start.path).isFile -> "\"${start.title}\" is on a drive that isn't connected."
            !start.info.playable -> "This device can't play ${start.info.label}. It stays in your library."
            else -> "\"${start.title}\" can't be played right now."
        },
    )
    return false
}

fun addToQueue(context: Context, container: AppContainer, tracks: List<LibraryTrack>, next: Boolean) {
    val added = container.enqueueLibrary(tracks, next)
    toast(
        context,
        when {
            added == 0 -> "Nothing added: these songs can't be played right now."
            next -> "Playing next"
            else -> "Added $added song${if (added == 1) "" else "s"} to the queue"
        },
    )
}

private enum class MenuStep { MAIN, PLAYLIST, DELETE }

/**
 * Chooses a playlist (or makes one) for songs given by their library ids: OK on a playlist adds
 * them, "New playlist…" asks for a name first. [beforeAdding] runs first and can stop it (e.g. when
 * songs that still have to be downloaded can't be, because nobody is signed in).
 */
@Composable
fun AddToPlaylistDialog(
    ids: List<String>,
    suggestedName: String,
    onDismiss: () -> Unit,
    beforeAdding: () -> Boolean = { true },
) {
    val context = LocalContext.current
    val container = context.container
    val collection by container.collection.data.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextEntryDialog(
            title = "Name the new playlist",
            initial = suggestedName,
            onDismiss = onDismiss,
            onDone = { name ->
                if (beforeAdding()) {
                    val playlist = container.collection.createPlaylist(name, ids)
                    toast(context, "Made playlist ${playlist.name}")
                }
                onDismiss()
            },
        )
    } else {
        OptionsDialog(
            title = "Add to playlist",
            subtitle = "Playlists are under Library → Playlists.",
            onDismiss = onDismiss,
            options = listOf(Option("New playlist…", closes = false) { naming = true }) +
                collection.playlists.map { playlist ->
                    Option("${playlist.name} (${playlist.trackIds.size})") {
                        if (beforeAdding()) {
                            val added = container.collection.addToPlaylist(playlist.id, ids)
                            toast(
                                context,
                                if (added == 0) "Already in ${playlist.name}"
                                else "Added ${if (added == 1) "1 song" else "$added songs"} to ${playlist.name}",
                            )
                        }
                    }
                },
        )
    }
}

/**
 * What can be done with library songs (one song, or an album's): play, queue, add to a playlist,
 * favourite, open the album, delete from the device (asked again first). [extra] adds choices
 * for where it was opened (e.g. Move up on a playlist).
 */
@Composable
fun SongMenu(
    tracks: List<LibraryTrack>,
    title: String,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)? = null,
    albumKey: String? = null,
    extra: List<Option> = emptyList(),
) {
    val context = LocalContext.current
    val container = context.container
    val collection by container.collection.data.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(MenuStep.MAIN) }
    val single = tracks.singleOrNull()
    when (step) {
        MenuStep.MAIN -> {
            val favourite = if (albumKey != null) albumKey in collection.favouriteAlbums
            else single != null && single.id in collection.favouriteTracks
            OptionsDialog(
                title = title,
                subtitle = single?.let { listOfNotNull(it.artist, it.album, it.info.label).joinToString(" · ") }
                    ?: "${tracks.size} songs",
                onDismiss = onDismiss,
                options = listOfNotNull(
                    onPlay?.let { Option(if (single != null) "Play from here" else "Play", Icons.Default.PlayArrow, onClick = it) },
                    Option("Play next", Icons.Default.SkipNext) { addToQueue(context, container, tracks, next = true) },
                    Option("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd) { addToQueue(context, container, tracks, next = false) },
                    Option("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd, closes = false) { step = MenuStep.PLAYLIST },
                    when {
                        albumKey != null -> Option(
                            if (favourite) "Remove album from favourites" else "Add album to favourites",
                            if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        ) { container.collection.toggleFavouriteAlbum(albumKey) }
                        single != null -> Option(
                            if (favourite) "Remove from favourites" else "Add to favourites",
                            if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        ) { container.collection.toggleFavouriteTrack(single.id) }
                        else -> null
                    },
                    onGoToAlbum?.let { Option("Go to album", Icons.Default.Album, onClick = it) },
                ) + extra + Option("Delete from this device…", Icons.Default.Delete, destructive = true, closes = false) {
                    step = MenuStep.DELETE
                },
            )
        }
        MenuStep.PLAYLIST -> AddToPlaylistDialog(tracks.map { it.id }, single?.album ?: title, onDismiss)
        MenuStep.DELETE -> TvDialog(
            title = if (single != null) "Delete \"${single.title}\"?" else "Delete ${tracks.size} songs?",
            onDismiss = onDismiss,
            // "Keep" first, so a quick OK never deletes anything.
            buttons = listOf(
                DialogButton("Keep", primary = true, onClick = onDismiss),
                DialogButton("Delete") {
                    container.library.remove(tracks.map { it.id }, deleteFiles = true)
                    onDismiss()
                },
            ),
        ) {
            Note("The downloaded files are removed from this device and from your library.")
        }
    }
}
