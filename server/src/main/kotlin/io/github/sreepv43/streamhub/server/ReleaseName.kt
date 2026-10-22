package io.github.sreepv43.streamhub.server

/** Title, year and episode read from a video's file name, e.g. "Dune.Part.Two.2024.2160p.WEB-DL.mkv". */
data class ParsedName(val title: String, val year: Int?, val season: Int?, val episode: Int?)

object ReleaseName {
    fun parse(fileName: String): ParsedName {
        var name = fileName.substringBeforeLast('.').takeIf { fileName.contains('.') && fileName.substringAfterLast('.').length <= 4 } ?: fileName
        // Site prefixes such as "www.Example.org - Movie (2023)".
        name = SITE.replace(name, "")
        name = name.replace('_', ' ').replace('.', ' ').replace(Regex("""\s+"""), " ").trim()

        val episodeMatch = EPISODE.find(name) ?: CROSS_EPISODE.find(name)
        val season = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = episodeMatch?.groupValues?.get(2)?.toIntOrNull()
        // The year is the last plausible one before the release details (a title can contain a year).
        val years = YEAR.findAll(name).filter { it.range.first > 0 }.toList()
        val yearMatch = years.lastOrNull { m -> episodeMatch == null || m.range.first < episodeMatch.range.first } ?: years.firstOrNull()
        val details = DETAILS.find(name)
        val end = listOfNotNull(episodeMatch?.range?.first, yearMatch?.range?.first, details?.range?.first).minOrNull() ?: name.length
        val title = name.substring(0, end)
            .replace(Regex("""[\[\](){}]"""), " ")
            .replace(Regex("""\s*[-–:]\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return ParsedName(title.ifEmpty { name }, yearMatch?.groupValues?.get(1)?.toIntOrNull(), season, episode)
    }

    /** Lower-case letters and digits only ("&" as "and"), for comparing titles. */
    fun normalize(title: String): String =
        title.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    private val SITE = Regex("""^\s*(www\.)?[\w-]+\.(com|net|org|to|in|xyz|pw|cc|me|ws|lol|se|tv|li|io|co|fi|nz|ac|st|mx|bz|ms|ma|ag|win|bid|sx|uk)\s*[-–]\s*""", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s?E(\d{1,3})\b""")
    private val CROSS_EPISODE = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
    private val YEAR = Regex("""[(\[\s]((?:19|20)\d{2})[)\]\s]?(?=\s|$|[)\]])""")
    private val DETAILS = Regex(
        """(?i)\b(2160p|1080p|720p|576p|480p|4k|uhd|web-?dl|web-?rip|webrip|blu-?ray|bdrip|brrip|hdrip|hdtv|dvdrip|remux|x264|x265|h ?264|h ?265|hevc|10bit|hdr|proper|repack|extended|unrated|imax|multi|dual audio)\b""",
    )
}
