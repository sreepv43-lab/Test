package io.github.sreepv43.streamhub.addon

/**
 * What a source card shows about a stream ("1080p · WEB-DL · HDR10", languages, audio, codec, size,
 * seeders), read from what addons write in its name, description and file name. Anything an addon
 * doesn't mention is null (or empty), never guessed.
 */
data class StreamFacts(
    /** "4K", "1440p", "1080p", "720p" or "SD". */
    val quality: String?,
    /** "WEB-DL", "Blu-ray REMUX", "HDTV"… */
    val source: String?,
    /** "Dolby Vision", "HDR10+", "HDR10" or "HDR". */
    val hdr: String?,
    /** "HEVC", "H.264" or "AV1". */
    val codec: String?,
    /** "DD+ 5.1 Atmos", "AAC 2.0"… */
    val audio: String?,
    val languages: List<String>,
    val sizeBytes: Long?,
    val seeders: Int?,
) {
    /** The card's title line, e.g. "4K · Blu-ray REMUX · Dolby Vision". */
    val headline: String get() = listOfNotNull(quality, source, hdr).joinToString(" · ")

    /** Headline and audio, e.g. "1080p · WEB-DL · DD+ 5.1", or null when the addon says neither. */
    val label: String? get() = listOfNotNull(headline.ifEmpty { null }, audio).joinToString(" · ").ifEmpty { null }

    companion object {
        fun of(stream: Stream): StreamFacts {
            val text = listOfNotNull(stream.name, stream.details, stream.behaviorHints.filename).joinToString("\n")
            val info = StreamRanking.info(stream)
            return StreamFacts(
                quality = qualityOf(info.resolution),
                source = SOURCES.firstOrNull { it.first.containsMatchIn(text) }?.second,
                hdr = HDR.firstOrNull { it.first.containsMatchIn(text) }?.second,
                codec = CODECS.firstOrNull { it.first.containsMatchIn(text) }?.second,
                audio = info.audio?.label?.ifEmpty { null },
                languages = languages(text),
                sizeBytes = info.sizeBytes,
                seeders = info.seeders,
            )
        }

        fun qualityOf(resolution: Int?): String? = when {
            resolution == null -> null
            resolution >= 2160 -> "4K"
            resolution >= 1440 -> "1440p"
            resolution >= 1080 -> "1080p"
            resolution >= 720 -> "720p"
            else -> "SD"
        }

        /** Languages named in [text] (words, common short forms and flags), in a fixed order. */
        fun languages(text: String): List<String> {
            val found = LinkedHashSet<String>()
            for ((pattern, name) in LANGUAGE_WORDS) if (pattern.containsMatchIn(text)) found += name
            for ((flag, name) in FLAGS) if (text.contains(flag)) found += name
            if ("Indian" in found && found.any { it in INDIAN }) found -= "Indian"
            if (MULTI.containsMatchIn(text) && found.size < 2) found += "Multi audio"
            return found.toList()
        }

        private fun word(pattern: String) = Regex("""(?i)(?<![a-z])($pattern)(?![a-z])""")

        // Most specific first.
        private val SOURCES = listOf(
            word("""(blu-?ray|bd|uhd)[ .-]?remux|remux[ .-]?(blu-?ray|bd)""") to "Blu-ray REMUX",
            word("remux") to "REMUX",
            word("""blu-?ray|bdrip|brrip|bdremux|bd25|bd50""") to "Blu-ray",
            word("""web-?dl""") to "WEB-DL",
            word("""web-?rip""") to "WEBRip",
            word("hdtv") to "HDTV",
            word("hdrip") to "HDRip",
            word("""dvd-?rip|dvd""") to "DVD",
            word("""cam|camrip|hdcam|telesync|hdts""") to "CAM",
            word("web") to "WEB",
        )
        private val HDR = listOf(
            word("""dolby[ .-]?vision|dovi|dv""") to "Dolby Vision",
            word("""hdr10\+|hdr10plus""") to "HDR10+",
            word("hdr10") to "HDR10",
            word("hdr") to "HDR",
        )
        private val CODECS = listOf(
            word("""x265|h\.?265|hevc""") to "HEVC",
            word("""x264|h\.?264|avc""") to "H.264",
            word("av1") to "AV1",
        )
        private val LANGUAGE_WORDS = listOf(
            "English" to "english|eng",
            "Malayalam" to "malayalam|mal",
            "Tamil" to "tamil|tam",
            "Hindi" to "hindi|hin",
            "Telugu" to "telugu|tel",
            "Kannada" to "kannada|kan",
            "Bengali" to "bengali",
            "Marathi" to "marathi",
            "Punjabi" to "punjabi",
            "Spanish" to "spanish|español|castellano|latino",
            "French" to "french|français|vff|truefrench",
            "German" to "german|deutsch",
            "Italian" to "italian|italiano",
            "Portuguese" to "portuguese|português",
            "Russian" to "russian",
            "Japanese" to "japanese",
            "Korean" to "korean",
            "Chinese" to "chinese|mandarin|cantonese",
            "Arabic" to "arabic",
            "Turkish" to "turkish",
        ).map { (name, words) ->
            // Short forms ("Mal", "Tam") only as written in release names, capitalised.
            val (long, short) = words.split('|').partition { it.length > 3 || it == "vff" }
            val pattern = buildList {
                if (long.isNotEmpty()) add("(?i:${long.joinToString("|")})")
                if (short.isNotEmpty()) add(short.joinToString("|") { it.replaceFirstChar(Char::uppercaseChar) + "|" + it.uppercase() })
            }.joinToString("|")
            Regex("""(?<![A-Za-z])($pattern)(?![A-Za-z])""") to name
        }
        // Flags addons such as Torrentio add for each audio language (India's flag covers several languages).
        private val FLAGS = listOf(
            "🇬🇧" to "English", "🇺🇸" to "English", "🇮🇳" to "Indian", "🇪🇸" to "Spanish", "🇲🇽" to "Spanish",
            "🇫🇷" to "French", "🇩🇪" to "German", "🇮🇹" to "Italian", "🇵🇹" to "Portuguese", "🇧🇷" to "Portuguese",
            "🇷🇺" to "Russian", "🇯🇵" to "Japanese", "🇰🇷" to "Korean", "🇨🇳" to "Chinese", "🇸🇦" to "Arabic", "🇹🇷" to "Turkish",
        )
        private val INDIAN = setOf("Malayalam", "Tamil", "Hindi", "Telugu", "Kannada", "Bengali", "Marathi", "Punjabi")
        private val MULTI = word("""multi|dual[ .-]?audio|multi[ .-]?audio""")
    }
}
