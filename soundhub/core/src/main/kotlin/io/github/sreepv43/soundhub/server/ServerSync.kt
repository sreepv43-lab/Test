package io.github.sreepv43.soundhub.server

import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.PathNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the home server is doing, for Settings. */
data class ServerStatus(
    /** Songs waiting to be saved on the server. */
    val waiting: Int = 0,
    /** The song being sent now, if any. */
    val sending: String? = null,
    /** Songs on the server. */
    val saved: Int = 0,
    /** Why the last attempt failed (retried by itself). */
    val error: String? = null,
)

/**
 * Saves the library on the home server. [mode] is "off", "copy" (a copy goes to the server, the song
 * stays here too) or "only" (once saved on the server the file here is deleted and the song plays
 * from the server). Songs and their album covers are sent one at a time, newest downloads first.
 */
class ServerSync(
    private val library: LibraryStore,
    private val server: () -> MusicServer?,
    private val mode: () -> String,
    private val musicRoot: () -> File,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryMs: Long = 30_000L,
    autoStart: Boolean = true,
) {
    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    private val coverFolders = HashSet<String>()
    private var failedAt = 0L

    init {
        if (autoStart) scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { syncNow() }
                delay(TICK_MS)
            }
        }
    }

    /** Where [track] goes on the server (the same place it has under the music folder). */
    fun relativePath(track: LibraryTrack) = PathNames.localPath(track.username, track.remotePath)

    /** Sends what is waiting; returns when there is nothing left (or the server fails). */
    @Synchronized
    fun syncNow() {
        val current = mode()
        val target = server()
        val tracks = library.tracks.value
        _status.value = _status.value.copy(saved = tracks.count { it.serverPath.isNotEmpty() })
        if (current == "off" || target == null) {
            _status.value = _status.value.copy(waiting = 0, sending = null)
            return
        }
        if (failedAt != 0L && now() - failedAt < retryMs) return
        while (true) {
            val todo = library.tracks.value
                .filter { it.serverPath.isEmpty() && it.isLocal && ready(it, current) }
                .sortedByDescending { it.addedAt }
            _status.value = _status.value.copy(waiting = todo.size, sending = null, saved = library.tracks.value.count { it.serverPath.isNotEmpty() })
            val track = todo.firstOrNull() ?: return
            _status.value = _status.value.copy(sending = track.title)
            try {
                val relative = relativePath(track)
                val local = File(track.path)
                target.upload(local, relative)
                sendCovers(target, local, relative)
                library.update(track.id) { it.copy(serverPath = relative) }
                if (current == "only") local.delete()
                failedAt = 0L
                _status.value = _status.value.copy(error = null)
            } catch (e: Exception) {
                failedAt = now()
                _status.value = _status.value.copy(sending = null, error = e.message ?: e.javaClass.simpleName)
                return
            }
        }
    }

    /** In "only" mode, wait for the tags and cover to be read from the file before it goes. */
    private fun ready(track: LibraryTrack, current: String) = current != "only" || track.tagged || now() - track.addedAt > TAG_WAIT_MS

    private fun sendCovers(target: MusicServer, song: File, relative: String) {
        val folder = song.parentFile ?: return
        if (!coverFolders.add(folder.path)) return
        val images = folder.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGES }.orEmpty()
        val remoteFolder = relative.substringBeforeLast('/', "")
        try {
            images.forEach { target.upload(it, if (remoteFolder.isEmpty()) it.name else "$remoteFolder/${it.name}") }
        } catch (e: Exception) {
            coverFolders.remove(folder.path)
        }
    }

    /** Removes [track]'s copy from the server (its files were deleted here). */
    fun deleteFromServer(track: LibraryTrack) {
        if (track.serverPath.isEmpty()) return
        val target = server() ?: return
        runCatching { target.delete(track.serverPath) }
    }

    private companion object {
        const val TICK_MS = 10_000L
        const val TAG_WAIT_MS = 30_000L
        val IMAGES = setOf("jpg", "jpeg", "png")
    }
}
