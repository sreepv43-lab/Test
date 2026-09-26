package io.github.sreepv43.streamhub.addon

/** What a stream's name and description say about it (addons put quality, size and peers there). */
data class StreamInfo(
    val resolution: Int?,
    val sizeBytes: Long?,
    val seeders: Int?,
    val lowQualitySource: Boolean,
)

/** Orders streams so the one that will play best on this TV comes first ("Play best"). */
object StreamRanking {
    fun info(stream: Stream): StreamInfo {
        val text = listOfNotNull(stream.name, stream.details, stream.behaviorHints.filename).joinToString("\n")
        return StreamInfo(
            resolution = resolution(text),
            sizeBytes = stream.behaviorHints.videoSize ?: size(text),
            seeders = seeders(text),
            lowQualitySource = LOW_QUALITY.containsMatchIn(text),
        )
    }

    /**
     * Best first: streams the app can play itself, then by picture quality up to [maxResolution]
     * (sharper than the TV can show only costs bandwidth), direct links before torrents, more
     * seeders, then smaller files of the same quality (they start and seek faster).
     */
    fun rank(streams: List<Stream>, maxResolution: Int = 1080): List<Stream> {
        val scored = streams.map { stream ->
            val target = StreamResolver.resolve(stream)
            val info = info(stream)
            Scored(
                stream = stream,
                playable = target != null && target !is PlaybackTarget.External,
                direct = target is PlaybackTarget.Direct,
                quality = qualityScore(info, maxResolution),
                seeders = info.seeders ?: if (target is PlaybackTarget.Torrent) 0 else Int.MAX_VALUE,
                size = info.sizeBytes ?: Long.MAX_VALUE,
            )
        }
        return scored.sortedWith(
            compareByDescending<Scored> { it.playable }
                .thenByDescending { it.quality }
                .thenByDescending { it.direct }
                .thenByDescending { it.seeders }
                .thenBy { it.size },
        ).map { it.stream }
    }

    /** The stream to start with one click, or null if none can be played in the app. */
    fun best(streams: List<Stream>, maxResolution: Int = 1080): Stream? =
        rank(streams, maxResolution).firstOrNull { StreamResolver.resolve(it).let { t -> t != null && t !is PlaybackTarget.External } }

    /**
     * For the next episode: a stream from the same release group as the one just watched (addons
     * mark these with a "binge group"), else the best one.
     */
    fun next(streams: List<Stream>, bingeGroup: String?, maxResolution: Int = 1080): Stream? =
        bingeGroup?.let { group -> streams.firstOrNull { it.behaviorHints.bingeGroup == group } }
            ?: best(streams, maxResolution)

    private data class Scored(
        val stream: Stream,
        val playable: Boolean,
        val direct: Boolean,
        val quality: Int,
        val seeders: Int,
        val size: Long,
    )

    private fun qualityScore(info: StreamInfo, maxResolution: Int): Int {
        if (info.lowQualitySource) return 1
        val resolution = info.resolution ?: return UNKNOWN_RESOLUTION_SCORE
        // Above what the TV shows: still fine, but ranked like a mid-quality stream.
        return if (resolution <= maxResolution) resolution else maxResolution / 2
    }

    internal fun resolution(text: String): Int? = RESOLUTIONS.firstNotNullOfOrNull { (pattern, value) ->
        value.takeIf { pattern.containsMatchIn(text) }
    }

    internal fun size(text: String): Long? {
        val match = SIZE.find(text) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (match.groupValues[2].uppercase()) {
            "TB" -> 1L shl 40
            "GB" -> 1L shl 30
            "MB" -> 1L shl 20
            else -> return null
        }
        return (number * unit).toLong()
    }

    internal fun seeders(text: String): Int? =
        SEEDERS.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    private val RESOLUTIONS = listOf(
        Regex("""(?i)\b(2160p|4k|uhd)\b""") to 2160,
        Regex("""(?i)\b1440p\b""") to 1440,
        Regex("""(?i)\b(1080p|fhd|full ?hd)\b""") to 1080,
        Regex("""(?i)\b720p\b""") to 720,
        Regex("""(?i)\b(576p|540p|480p|sd)\b""") to 480,
        Regex("""(?i)\b(360p|240p)\b""") to 360,
    )
    private val SIZE = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)\b""")
    private val SEEDERS = Regex("""(?i)(?:👤\s*(\d+))|(?:\bseed(?:er)?s?\s*[:=]?\s*(\d+))""")
    private val LOW_QUALITY = Regex("""(?i)\b(cam|camrip|hdcam|ts|telesync|hdts|tc|telecine|scr|screener)\b""")
    private const val UNKNOWN_RESOLUTION_SCORE = 500
}
