package io.github.sreepv43.streamhub.download

import java.text.Normalizer

/** A movie or one episode, as a title page or poster knows it. */
data class TitleRef(
    val metaId: String,
    /** The movie's id, or the episode's ("tt0903747:1:2"). */
    val videoId: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** A file that may be a copy of a title: a download (it knows its ids) or a file found by name. */
data class CopyFile(
    val metaId: String? = null,
    val videoId: String? = null,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Decides whether a downloaded or found file is a copy of a title. Files that know their catalogue
 * ids match by id only. Files known just by name need the same title and, to be sure of it, the same
 * year for movies (a "Dune" file without a year is not taken for Dune (2021)) or the same season and
 * episode for series.
 */
object CopyMatch {
    fun matches(ref: TitleRef, file: CopyFile): Boolean {
        if (file.metaId != null) {
            if (file.metaId != ref.metaId) return false
            return file.videoId == null || file.videoId == ref.videoId || ref.videoId == ref.metaId && file.videoId == file.metaId
        }
        val title = file.parsedTitle ?: return false
        if (normalize(title) != normalize(ref.title)) return false
        return if (ref.season != null) {
            file.season == ref.season && file.episode == ref.episode
        } else {
            file.season == null && file.year != null && file.year == ref.year
        }
    }

    /** Whether [file] belongs to the title [metaId] (any episode of a series), for poster badges. */
    fun belongsTo(metaId: String, title: String, year: Int?, file: CopyFile): Boolean {
        if (file.metaId != null) return file.metaId == metaId
        val name = file.parsedTitle ?: return false
        if (normalize(name) != normalize(title)) return false
        return file.season != null || file.year != null && file.year == year
    }

    /** "The Lord of the Rings: The Return of the King" → "lord of the rings the return of the king". */
    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removePrefix("the ")

    /** The first year in a catalogue's "2021" or "2008–2013". */
    fun yearOf(releaseInfo: String?): Int? =
        releaseInfo?.let { Regex("(?<!\\d)(19|20)\\d\\d(?!\\d)").find(it)?.value?.toIntOrNull() }

    /** Season and episode from a Stremio episode id ("tt0903747:1:2" → 1 to 2). */
    fun episodeOf(videoId: String): Pair<Int, Int>? {
        val parts = videoId.split(':')
        if (parts.size < 3) return null
        val season = parts[parts.size - 2].toIntOrNull() ?: return null
        val episode = parts.last().toIntOrNull() ?: return null
        return season to episode
    }
}
