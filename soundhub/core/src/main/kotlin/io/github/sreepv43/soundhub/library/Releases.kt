package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.audio.MusicFilter

/**
 * One album as the listener sees it, with every user's folder that has it as a source. Folders are
 * grouped only when artist and album match after format tags ("[FLAC 24-96]", "(2020)", "320")
 * are ignored; editions ("Deluxe", "Live", "CD2") stay apart, as do Atmos releases and anything
 * without a recognisable artist.
 */
data class Release(val key: String, val album: String, val artist: String?, val sources: List<SearchFolder>) {
    val trackCount: Int get() = sources.maxOf { it.tracks.size }

    fun matches(filter: MusicFilter): Boolean = sources.any { it.matches(filter) }

    /**
     * The source playing starts from: one with songs this device can play, matching the filter,
     * with a free slot reported, the most songs, the best quality, then the shortest queue and
     * highest reported speed. The choice is shown, and can be changed, on the album page.
     */
    fun bestSource(filter: MusicFilter): SearchFolder {
        val candidates = sources.filter { it.matches(filter) }.ifEmpty { sources }
        val most = candidates.maxOf { it.tracks.size }
        return candidates.sortedWith(
            compareByDescending<SearchFolder> { source -> source.tracks.any { it.info.playable } }
                .thenByDescending { it.slotFree }
                .thenByDescending { it.tracks.size == most }
                .thenByDescending { source -> source.summary(filter)?.let(Releases::qualityRank) ?: 0 }
                .thenBy { it.queueLength }
                .thenByDescending { it.avgSpeed },
        ).first()
    }
}

object Releases {
    private val BRACKETS = Regex("""[\[({]([^\])}]*)[\])}]""")
    private val FORMAT_WORDS = Regex(
        """\b(flac|mp3|aac|alac|m4a|ogg|opus|wav|aiff?|ape|wv|dsd\d*|dsf|dff|\d{2,4}\s?kbps|\d{3}k|320|256|192|128|v0|v2|vbr|cbr|""" +
            """\d{2}\s?bit|\d{2}[-/ ]\d{2,3}(\.\d)?|\d{2,3}(\.\d)?\s?khz|hi-?res|hd|lossless|lossy|web|web-?dl|cd|cdrip|""" +
            """vinyl|lp|atmos|dolby|ddp?\+?|e-?ac-?3|truehd|[57]\.1(\.[24])?|\d{4}|remaster(ed)?\s\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATORS = Regex("""[\s,._/+&-]+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val GENERIC = setOf("", "music", "album", "albums", "various", "various artists", "unknown", "untitled", "new folder")

    /** "Album (2020) [FLAC 24-96]" → "Album"; "Album (Deluxe Edition) [MP3]" → "Album (Deluxe Edition)". */
    fun cleanTitle(title: String): String {
        var result = BRACKETS.replace(title) { match -> if (onlyFormatWords(match.groupValues[1])) " " else match.value }
        // Trailing " - 2020 - FLAC" style parts.
        val parts = result.split(" - ").toMutableList()
        while (parts.size > 1 && onlyFormatWords(parts.last())) parts.removeAt(parts.lastIndex)
        result = parts.joinToString(" - ")
        return result.replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { title.trim() }
    }

    fun normalize(text: String): String = NON_WORD.replace(cleanTitle(text).lowercase(), " ").trim()

    fun keyOf(folder: SearchFolder): String {
        val artist = folder.artist?.let(::normalize)
        val album = normalize(folder.album)
        if (artist.isNullOrEmpty() || album in GENERIC || album.length < 2) return "folder:" + folder.key
        val atmos = if (folder.matches(FormatFilter.ATMOS)) "|atmos" else ""
        return "$artist|$album$atmos"
    }

    /** Groups [folders] into releases, keeping their order (a release sits where its first source was). */
    fun group(folders: List<SearchFolder>): List<Release> {
        val byKey = LinkedHashMap<String, MutableList<SearchFolder>>()
        folders.forEach { byKey.getOrPut(keyOf(it)) { mutableListOf() } += it }
        return byKey.map { (key, sources) ->
            val first = sources.first()
            Release(key, cleanTitle(first.album), first.artist, sources)
        }
    }

    /** Higher is better: hi-res lossless, lossless, then lossy by bitrate. */
    fun qualityRank(info: AudioInfo): Int = when {
        info.codec.lossless && info.hiRes -> 3_000
        info.codec.lossless -> 2_000
        else -> (info.bitrateKbps ?: 0).coerceAtMost(999) + 1_000
    }

    private fun onlyFormatWords(text: String): Boolean =
        text.isNotBlank() && SEPARATORS.replace(FORMAT_WORDS.replace(text, ""), "").isEmpty()
}
