package io.github.sreepv43.streamhub.sync

/** An IMDb-identified movie or episode, as Trakt addresses them. */
data class TraktRef(val imdb: String, val season: Int? = null, val episode: Int? = null)

object SyncIds {
    /**
     * Stremio ids are "tt1234567" for a movie or show and "tt1234567:1:2" for an episode (season 1,
     * episode 2). Other catalogues (e.g. "kitsu:…") aren't known to Trakt.
     */
    fun parse(videoId: String): TraktRef? {
        val parts = videoId.split(':')
        val imdb = parts[0].takeIf { IMDB.matches(it) } ?: return null
        if (parts.size == 1) return TraktRef(imdb)
        val season = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: return null
        return TraktRef(imdb, season, episode)
    }

    /** Stremio calls shows "series"; Trakt calls them "show". */
    fun traktType(stremioType: String): String? = when (stremioType) {
        "movie" -> "movie"
        "series" -> "show"
        else -> null
    }

    private val IMDB = Regex("""tt\d+""")
}
