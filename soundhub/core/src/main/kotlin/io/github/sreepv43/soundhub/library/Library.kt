package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.FormatFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A downloaded song. [path] is the local file; [username]/[remotePath] where it came from. */
@Serializable
data class LibraryTrack(
    val id: String,
    val username: String,
    val remotePath: String,
    val path: String,
    val size: Long,
    val title: String,
    val trackNumber: Int? = null,
    val album: String,
    val artist: String? = null,
    val info: AudioInfo,
    val addedAt: Long,
)

data class Album(val key: String, val title: String, val artist: String?, val tracks: List<LibraryTrack>) {
    fun matches(filter: FormatFilter) = tracks.any { filter.matches(it.info) }
}

/** The library index, kept as JSON next to the app's data. */
class LibraryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LibraryTrack.serializer())
    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()

    fun find(username: String, remotePath: String): LibraryTrack? =
        _tracks.value.firstOrNull { it.username == username && it.remotePath == remotePath }

    fun get(id: String): LibraryTrack? = _tracks.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(track: LibraryTrack) {
        save(_tracks.value.filterNot { it.id == track.id || it.path == track.path } + track)
    }

    @Synchronized
    fun update(id: String, transform: (LibraryTrack) -> LibraryTrack) {
        save(_tracks.value.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun remove(ids: Collection<String>, deleteFiles: Boolean) {
        val (gone, kept) = _tracks.value.partition { it.id in ids }
        if (deleteFiles) {
            gone.forEach { track ->
                val local = File(track.path)
                local.delete()
                // Tidy up the album folder once it is empty.
                local.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
            }
        }
        save(kept)
    }

    /** Drops entries whose files were deleted outside the app (or whose drive is gone). */
    @Synchronized
    fun pruneMissing() {
        val present = _tracks.value.filter { File(it.path).isFile }
        if (present.size != _tracks.value.size) save(present)
    }

    private fun load(): List<LibraryTrack> = try {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(tracks: List<LibraryTrack>) {
        _tracks.value = tracks
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(serializer, tracks))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        fun id(username: String, remotePath: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest("$username\u0000$remotePath".toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }

        /** Albums by folder, tracks in order, newest album first. */
        fun albums(tracks: List<LibraryTrack>): List<Album> =
            tracks.groupBy { File(it.path).parent.orEmpty() }
                .map { (folder, list) ->
                    val sorted = list.sortedWith(compareBy<LibraryTrack>({ it.trackNumber ?: Int.MAX_VALUE }, { it.path }))
                    Album(folder, sorted.first().album, sorted.first().artist, sorted)
                }
                .sortedByDescending { album -> album.tracks.maxOf { it.addedAt } }
    }
}
