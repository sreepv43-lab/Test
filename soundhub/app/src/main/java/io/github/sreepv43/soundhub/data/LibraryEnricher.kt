package io.github.sreepv43.soundhub.data

import android.media.MediaMetadataRetriever
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.LibraryViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Album covers found in album folders, looked up once per folder. */
class CoverCache {
    private val covers = ConcurrentHashMap<String, String>()

    fun cover(albumDir: String?): File? {
        if (albumDir.isNullOrEmpty()) return null
        val path = covers.getOrPut(albumDir) { LibraryViews.coverIn(File(albumDir))?.path ?: NONE }
        return if (path == NONE) null else File(path)
    }

    fun invalidate(albumDir: String) {
        covers.remove(albumDir)
    }

    private companion object {
        const val NONE = ""
    }
}

/**
 * Reads each new song's own tags (title, artist, album, track number, length) and saves its
 * embedded cover as the album's cover.jpg, so the library shows real names and artwork instead of
 * guesses from folder names.
 */
class LibraryEnricher(private val library: LibraryStore, private val covers: CoverCache, scope: CoroutineScope) {
    init {
        scope.launch {
            library.tracks.collect { tracks ->
                tracks.filter { !it.tagged && File(it.path).isFile }.forEach(::enrich)
            }
        }
    }

    private fun enrich(track: LibraryTrack) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(track.path)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val number = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()
            val seconds = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)?.toInt()
            val dir = File(track.path).parentFile
            if (dir != null && LibraryViews.coverIn(dir) == null) {
                retriever.embeddedPicture?.let { picture ->
                    runCatching { File(dir, "cover.jpg").writeBytes(picture) }
                    covers.invalidate(dir.path)
                }
            }
            library.update(track.id) {
                it.copy(
                    title = title ?: it.title,
                    artist = artist ?: it.artist,
                    album = album ?: it.album,
                    trackNumber = number ?: it.trackNumber,
                    info = if (seconds != null && seconds > 0) it.info.copy(durationSec = seconds) else it.info,
                    tagged = true,
                )
            }
        } catch (e: Exception) {
            // Unreadable tags: keep the names from the path, and don't try again.
            library.update(track.id) { it.copy(tagged = true) }
        } finally {
            runCatching { retriever.release() }
        }
    }
}
