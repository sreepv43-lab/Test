package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.AudioProbe
import io.github.sreepv43.soundhub.slsk.SoulseekClient
import io.github.sreepv43.soundhub.slsk.Transfer
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads songs into the music folder, checks their real format once the first bytes arrive,
 * and adds them to the library when they are complete.
 */
class MusicDownloads(
    private val client: SoulseekClient,
    private val library: LibraryStore,
    private val musicRoot: () -> File,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val track: SearchTrack, var info: AudioInfo, var headProbed: Boolean = false)

    private val pending = HashMap<Long, Pending>()
    private val _infos = MutableStateFlow<Map<Long, AudioInfo>>(emptyMap())

    /** What is known about each download's audio (from its name, then from its header). */
    val infos: StateFlow<Map<Long, AudioInfo>> = _infos.asStateFlow()

    init {
        scope.launch { client.transfers.collect { onUpdate(it) } }
    }

    /** Starts (or joins) the download of [track]; play it while it arrives through the transfer's data. */
    fun fetch(track: SearchTrack): Transfer {
        val target = File(musicRoot(), PathNames.localPath(track.username, track.file.filename))
        val transfer = client.download(track.username, track.file.filename, track.file.size, target)
        synchronized(this) {
            if (transfer.id !in pending && library.get(LibraryStore.id(track.username, track.file.filename)) == null) {
                pending[transfer.id] = Pending(track, track.info)
                _infos.update { it + (transfer.id to track.info) }
            }
        }
        onUpdate(listOf(transfer.info))
        return transfer
    }

    /** The player found Atmos (E-AC-3 JOC) in a song: remember it. */
    fun markAtmos(username: String, remotePath: String) {
        val id = LibraryStore.id(username, remotePath)
        library.get(id)?.takeIf { it.info.atmos != Atmos.VERIFIED }?.let {
            library.update(id) { track -> track.copy(info = track.info.copy(atmos = Atmos.VERIFIED, surround = true)) }
        }
        synchronized(this) {
            pending.entries.firstOrNull { it.value.track.username == username && it.value.track.file.filename == remotePath }?.let { (id, entry) ->
                entry.info = entry.info.copy(atmos = Atmos.VERIFIED, surround = true)
                _infos.update { it + (id to entry.info) }
            }
        }
    }

    @Synchronized
    private fun onUpdate(transfers: List<TransferInfo>) {
        for (info in transfers) {
            val entry = pending[info.id] ?: continue
            val transfer = client.transfer(info.id) ?: continue
            val file = transfer.data.file
            val headReady = info.size > 0 && info.bytes >= minOf(info.size, AudioProbe.HEAD_BYTES.toLong())
            if (!entry.headProbed && (headReady || info.status == TransferStatus.COMPLETED)) {
                entry.headProbed = true
                refine(info.id, entry, file)
            }
            if (info.status == TransferStatus.COMPLETED) {
                pending.remove(info.id)
                // A trailing MP4 index can only be read now.
                if (entry.info == entry.track.info) refine(info.id, entry, file)
                val name = entry.track.name
                library.add(
                    LibraryTrack(
                        id = LibraryStore.id(entry.track.username, entry.track.file.filename),
                        username = entry.track.username,
                        remotePath = entry.track.file.filename,
                        path = file.absolutePath,
                        size = file.length(),
                        title = name.title,
                        trackNumber = name.trackNumber,
                        album = name.album,
                        artist = name.artist,
                        info = entry.info,
                        addedAt = now(),
                    ),
                )
            }
        }
    }

    private fun refine(id: Long, entry: Pending, file: File) {
        val probe = runCatching { AudioProbe.probeFile(file) }.getOrNull() ?: return
        entry.info = entry.info.withProbe(probe)
        _infos.update { it + (id to entry.info) }
    }
}
