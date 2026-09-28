package io.github.sreepv43.soundhub.audio

enum class Quality(val label: String) { ANY("Any quality"), LOSSLESS("Lossless"), HI_RES("Hi-Res"), LOSSY("Lossy") }

enum class Channels(val label: String) { ANY("Any"), STEREO("Stereo"), SURROUND("Surround"), ATMOS("Dolby Atmos") }

enum class CodecChoice(val label: String) {
    ANY("Any format"),
    MP3("MP3"),
    AAC("AAC / MP4"),
    FLAC("FLAC"),
    ALAC("ALAC"),
    WAV_AIFF("WAV / AIFF"),
    DSD("DSD"),
    DOLBY_DTS("Dolby / DTS"),
    OTHER("Other"),
}

/**
 * What to show, as independent choices (quality, channels, format, availability) instead of one row
 * of overlapping chips. A song matches when it matches every choice; an album when any song does.
 */
data class MusicFilter(
    val quality: Quality = Quality.ANY,
    val channels: Channels = Channels.ANY,
    val codec: CodecChoice = CodecChoice.ANY,
    /** Only sources whose owner reports a free upload slot (search results only). */
    val freeSlotOnly: Boolean = false,
) {
    val isDefault: Boolean get() = this == MusicFilter()

    /** "Lossless · Dolby Atmos", or "All music". */
    val label: String
        get() = listOfNotNull(
            quality.takeIf { it != Quality.ANY }?.label,
            channels.takeIf { it != Channels.ANY }?.label,
            codec.takeIf { it != CodecChoice.ANY }?.label,
            "free slot".takeIf { freeSlotOnly },
        ).joinToString(" · ").ifEmpty { "All music" }

    fun matches(info: AudioInfo): Boolean {
        val quality = when (quality) {
            Quality.ANY -> true
            Quality.LOSSLESS -> info.codec.lossless
            Quality.HI_RES -> info.hiRes || info.codec == Codec.DSD
            Quality.LOSSY -> !info.codec.lossless
        }
        val channels = when (channels) {
            Channels.ANY -> true
            Channels.STEREO -> !info.surround
            Channels.SURROUND -> info.surround && info.atmos == Atmos.NONE
            Channels.ATMOS -> info.atmos != Atmos.NONE
        }
        val codec = when (codec) {
            CodecChoice.ANY -> true
            CodecChoice.MP3 -> info.codec == Codec.MP3
            CodecChoice.AAC -> info.codec == Codec.AAC
            CodecChoice.FLAC -> info.codec == Codec.FLAC
            CodecChoice.ALAC -> info.codec == Codec.ALAC
            CodecChoice.WAV_AIFF -> info.codec == Codec.WAV || info.codec == Codec.AIFF
            CodecChoice.DSD -> info.codec == Codec.DSD
            CodecChoice.DOLBY_DTS -> info.codec in setOf(Codec.AC3, Codec.EAC3, Codec.TRUEHD, Codec.DTS)
            CodecChoice.OTHER -> info.codec in setOf(Codec.VORBIS, Codec.OPUS, Codec.WMA, Codec.APE, Codec.WAVPACK, Codec.UNKNOWN)
        }
        return quality && channels && codec
    }

    companion object {
        /** A few one-press shortcuts; everything else is in the filter panel. */
        val SHORTCUTS: List<Pair<String, MusicFilter>> = listOf(
            "All" to MusicFilter(),
            "Lossless" to MusicFilter(quality = Quality.LOSSLESS),
            "Hi-Res" to MusicFilter(quality = Quality.HI_RES),
            "Dolby Atmos" to MusicFilter(channels = Channels.ATMOS),
            "MP3" to MusicFilter(codec = CodecChoice.MP3),
        )
    }
}
