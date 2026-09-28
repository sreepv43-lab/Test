package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.MusicFilter
import java.io.File

enum class LibrarySort(val label: String) { RECENT("Recently added"), TITLE("Title"), ARTIST("Artist") }

data class Artist(val name: String, val albums: List<Album>) {
    val trackCount: Int get() = albums.sumOf { it.tracks.size }
}

/** The library seen as albums, songs and artists, searched, filtered and sorted. */
object LibraryViews {
    const val UNKNOWN_ARTIST = "Unknown artist"

    /** Songs whose title, artist or album contain every word of [query]. */
    fun search(tracks: List<LibraryTrack>, query: String): List<LibraryTrack> {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return tracks
        return tracks.filter { track ->
            val text = "${track.title} ${track.artist.orEmpty()} ${track.album}".lowercase()
            words.all { it in text }
        }
    }

    fun filter(tracks: List<LibraryTrack>, filter: MusicFilter): List<LibraryTrack> =
        if (filter.isDefault) tracks else tracks.filter { filter.matches(it.info) }

    fun albums(tracks: List<LibraryTrack>, sort: LibrarySort): List<Album> {
        val albums = LibraryStore.albums(tracks)
        return when (sort) {
            LibrarySort.RECENT -> albums
            LibrarySort.TITLE -> albums.sortedBy { it.title.lowercase() }
            LibrarySort.ARTIST -> albums.sortedWith(compareBy({ (it.artist ?: UNKNOWN_ARTIST).lowercase() }, { it.title.lowercase() }))
        }
    }

    fun songs(tracks: List<LibraryTrack>, sort: LibrarySort): List<LibraryTrack> = when (sort) {
        LibrarySort.RECENT -> tracks.sortedByDescending { it.addedAt }
        LibrarySort.TITLE -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.ARTIST -> tracks.sortedWith(
            compareBy<LibraryTrack>({ (it.artist ?: UNKNOWN_ARTIST).lowercase() }, { it.album.lowercase() }, { it.trackNumber ?: Int.MAX_VALUE }),
        )
    }

    fun artists(tracks: List<LibraryTrack>): List<Artist> =
        LibraryStore.albums(tracks)
            .groupBy { it.artist?.takeIf(String::isNotBlank) ?: UNKNOWN_ARTIST }
            .map { (name, albums) -> Artist(name, albums.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    /** Albums in the order they were last played, most recent first. */
    fun recentlyPlayed(history: List<PlayRecord>, albums: List<Album>, limit: Int = 12): List<Album> {
        val byKey = albums.associateBy { it.key }
        return history.mapNotNull { it.albumKey }.distinct().mapNotNull(byKey::get).take(limit)
    }

    /** A song's album key: the folder it is in. */
    fun albumKeyOf(path: String): String = File(path).parent.orEmpty()

    private val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** The album's cover image in [dir]: cover/folder/front.jpg first, else any image there. */
    fun coverIn(dir: File): File? {
        val images = dir.listFiles { file -> file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS }?.toList().orEmpty()
        if (images.isEmpty()) return null
        return COVER_NAMES.firstNotNullOfOrNull { name -> images.firstOrNull { it.nameWithoutExtension.lowercase().startsWith(name) } }
            ?: images.maxByOrNull { it.length() }
    }
}
