package io.github.sreepv43.streamhub.addon

/**
 * The audio a stream's name says it has, e.g. "DDP5.1 Atmos" → DD+ 5.1 Atmos. Used to show it in
 * stream lists and, with surround passthrough on, to prefer audio a receiver can take undecoded.
 */
data class StreamAudio(val codec: Codec?, val channels: String?, val atmos: Boolean) {
    enum class Codec(val label: String) {
        TRUEHD("TrueHD"),
        DTS_X("DTS:X"),
        DTS_HD("DTS-HD"),
        DTS("DTS"),
        DDP("DD+"),
        DD("Dolby Digital"),
        AAC("AAC"),
        OPUS("Opus"),
        FLAC("FLAC"),
    }

    /** "DD+ 5.1 Atmos", "AAC 5.1", "7.1" … */
    val label: String get() = listOfNotNull(codec?.label, channels, "Atmos".takeIf { atmos }).joinToString(" ")

    /**
     * Whether the receiver can get this audio undecoded with passthrough [mode] ("arc": Dolby
     * Digital, DD+ incl. Atmos and DTS, where DTS-HD / DTS:X send their DTS core; "earc": also TrueHD).
     */
    fun passesThrough(mode: String): Boolean = when (codec) {
        Codec.DD, Codec.DDP, Codec.DTS, Codec.DTS_HD, Codec.DTS_X -> mode == "arc" || mode == "earc"
        Codec.TRUEHD -> mode == "earc"
        else -> false
    }

    companion object {
        fun parse(text: String): StreamAudio? {
            val codec = CODECS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
            val channels = CHANNELS.find(text)?.value
            val atmos = ATMOS.containsMatchIn(text)
            if (codec == null && channels == null && !atmos) return null
            return StreamAudio(codec, channels, atmos)
        }

        // Most specific first (DTS-HD before DTS, DD+ before DD).
        private val CODECS = listOf(
            Regex("""(?i)(?<![a-z])true-?hd(?![a-z])""") to Codec.TRUEHD,
            Regex("""(?i)(?<![a-z])dts[-:. ]?x(?![a-z])""") to Codec.DTS_X,
            Regex("""(?i)(?<![a-z])dts[-. ]?(hd|ma)(?![a-z])""") to Codec.DTS_HD,
            Regex("""(?i)(?<![a-z])dts(?![a-z])""") to Codec.DTS,
            Regex("""(?i)(?<![a-z])(ddp|dd\+|e-?ac-?3)""") to Codec.DDP,
            Regex("""(?i)(?<![a-z])(dd|ac-?3)(?![a-z])""") to Codec.DD,
            Regex("""(?i)(?<![a-z])aac(?![a-z])""") to Codec.AAC,
            Regex("""(?i)(?<![a-z])opus(?![a-z])""") to Codec.OPUS,
            Regex("""(?i)(?<![a-z])flac(?![a-z])""") to Codec.FLAC,
        )
        private val CHANNELS = Regex("""(?<!\d)(7\.1|6\.1|5\.1|2\.1|2\.0|1\.0)(?![\d])""")
        private val ATMOS = Regex("""(?i)(?<![a-z])atmos(?![a-z])""")
    }
}
