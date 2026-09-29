package io.github.sreepv43.soundhub.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Playlist(val id: String, val name: String, val trackIds: List<String> = emptyList(), val createdAt: Long = 0)

/** One song that started playing. [albumKey] is the library album (its folder) when known. */
@Serializable
data class PlayRecord(val trackId: String, val albumKey: String? = null, val at: Long)

/**
 * What was playing when the app last stopped, so Home can offer to resume it. Only songs in the
 * library are kept (downloads in progress don't survive a restart).
 */
@Serializable
data class SavedSession(
    val trackIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val savedAt: Long = 0,
)

/**
 * The listener's own data, kept apart from the library index so rescans and removed files never
 * touch it: favourites, playlists, listening history and the last playback session. Tracks are
 * referred to by their durable library id (see [LibraryStore.id]); ids of songs that are no longer
 * in the library are kept, so they come back if the song does.
 */
@Serializable
data class CollectionData(
    val version: Int = 1,
    val favouriteTracks: Set<String> = emptySet(),
    val favouriteAlbums: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<PlayRecord> = emptyList(),
    val session: SavedSession? = null,
)

class CollectionStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<CollectionData> = _data.asStateFlow()

    fun isFavouriteTrack(id: String) = id in _data.value.favouriteTracks

    fun isFavouriteAlbum(key: String) = key in _data.value.favouriteAlbums

    @Synchronized
    fun toggleFavouriteTrack(id: String) = change {
        it.copy(favouriteTracks = if (id in it.favouriteTracks) it.favouriteTracks - id else it.favouriteTracks + id)
    }

    @Synchronized
    fun toggleFavouriteAlbum(key: String) = change {
        it.copy(favouriteAlbums = if (key in it.favouriteAlbums) it.favouriteAlbums - key else it.favouriteAlbums + key)
    }

    @Synchronized
    fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, trackIds.distinct(), now())
        change { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    @Synchronized
    fun deletePlaylist(id: String) = change { data -> data.copy(playlists = data.playlists.filterNot { it.id == id }) }

    /** Adds songs at the end, skipping ones the playlist already has. Returns how many were added. */
    @Synchronized
    fun addToPlaylist(id: String, trackIds: List<String>): Int {
        val playlist = _data.value.playlists.firstOrNull { it.id == id } ?: return 0
        val fresh = trackIds.distinct().filter { it !in playlist.trackIds }
        if (fresh.isNotEmpty()) editPlaylist(id) { it.copy(trackIds = it.trackIds + fresh) }
        return fresh.size
    }

    @Synchronized
    fun removeFromPlaylist(id: String, index: Int) = editPlaylist(id) { playlist ->
        if (index !in playlist.trackIds.indices) playlist
        else playlist.copy(trackIds = playlist.trackIds.filterIndexed { i, _ -> i != index })
    }

    /** Moves the song at [from] one place up ([delta] = -1) or down (+1). */
    @Synchronized
    fun movePlaylistItem(id: String, from: Int, delta: Int) = editPlaylist(id) { playlist ->
        val to = from + delta
        if (from !in playlist.trackIds.indices || to !in playlist.trackIds.indices) playlist
        else playlist.copy(trackIds = playlist.trackIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Records that a song started; repeated starts of the same song in a row count once. */
    @Synchronized
    fun recordPlay(trackId: String, albumKey: String?) = change { data ->
        if (data.history.firstOrNull()?.trackId == trackId) data
        else data.copy(history = (listOf(PlayRecord(trackId, albumKey, now())) + data.history).take(MAX_HISTORY))
    }

    @Synchronized
    fun saveSession(session: SavedSession?) = change { it.copy(session = session?.copy(savedAt = now())) }

    private fun editPlaylist(id: String, edit: (Playlist) -> Playlist) = change { data ->
        data.copy(playlists = data.playlists.map { if (it.id == id) edit(it) else it })
    }

    private fun change(transform: (CollectionData) -> CollectionData) {
        val updated = transform(_data.value)
        if (updated == _data.value) return
        _data.value = updated
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(CollectionData.serializer(), updated))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun load(): CollectionData = try {
        if (file.isFile) json.decodeFromString(CollectionData.serializer(), file.readText()) else CollectionData()
    } catch (e: Exception) {
        // Keep the unreadable file for recovery instead of overwriting it on the next change.
        file.renameTo(File(file.path + ".unreadable"))
        CollectionData()
    }

    private companion object {
        const val MAX_HISTORY = 300
    }
}
