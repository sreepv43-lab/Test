package io.github.sreepv43.streamhub.addon

/** Episode order for "next episode" and the label shown for an episode. */
object Episodes {
    /** Watching order: seasons ascending with specials (season 0) last, then episode number. */
    fun ordered(videos: List<Video>): List<Video> = videos.sortedWith(
        compareBy<Video>({ (it.season ?: 0).let { s -> if (s == 0) Int.MAX_VALUE else s } }, { it.episodeNumber ?: 0 }),
    )

    /** The episode after [currentId], or null at the end (regular episodes never roll into specials). */
    fun after(videos: List<Video>, currentId: String): Video? {
        val list = ordered(videos)
        val index = list.indexOfFirst { it.id == currentId }
        if (index < 0 || index == list.lastIndex) return null
        val next = list[index + 1]
        if ((list[index].season ?: 0) != 0 && (next.season ?: 0) == 0) return null
        return next
    }

    /** "S1E3 · Title" (same format as the streams page). */
    fun label(video: Video): String = listOfNotNull(
        video.season?.let { s -> video.episodeNumber?.let { e -> "S${s}E$e" } },
        video.displayTitle,
    ).joinToString(" · ")
}
