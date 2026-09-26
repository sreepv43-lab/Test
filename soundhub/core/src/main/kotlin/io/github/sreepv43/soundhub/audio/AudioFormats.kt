package io.github.sreepv43.soundhub.audio

import kotlinx.serialization.Serializable

enum class Codec(val label: String, val lossless: Boolean) {
    MP3("MP3", false),
    AAC("AAC", false),
    VORBIS("Ogg Vorbis", false),
    OPUS("Opus", false),
    WMA("WMA", false),
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    APE("APE", true),
    WAVPACK("WavPack", true),
    DSD("DSD", true),
    AC3("Dolby Digital", false),
    EAC3("Dolby Digital Plus", false),
    TRUEHD("Dolby TrueHD", true),
    DTS("DTS", false),
    UNKNOWN("Audio", false),
}

enum class Atmos {
    /** No sign of Dolby Atmos. */
    NONE,

    /** The name says Atmos and the format can carry it; not checked yet. */
    LIKELY,

    /** The file's header says it carries Atmos (E-AC-3 JOC or a TrueHD 16-channel presentation). */
    VERIFIED,
}

/** What is known about a file's audio, from its name and search attributes, refined by its header. */
@Serializable
data class AudioInfo(
    val codec: Codec,
    val extension: String,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val durationSec: Int? = null,
    val atmos: Atmos = Atmos.NONE,
    val surround: Boolean = false,
    val hiRes: Boolean = false,
    /** Hi-res was guessed from the bitrate or the name rather than read from the file. */
    val hiResGuessed: Boolean = false,
    val vbr: Boolean = false,
    /** A PCM (FLAC, WAV, ...) mix of an Atmos release: surround, but no Atmos objects. */
    val atmosAsPcm: Boolean = false,
) {
    /** Short badge text: "FLAC 24/96", "MP3 320", "DD+ Atmos", "DSD128". */
    val label: String
        get() = when {
            atmos != Atmos.NONE -> when (codec) {
                Codec.EAC3 -> "DD+ Atmos"
                Codec.TRUEHD -> "TrueHD Atmos"
                else -> "Atmos · ${extension.uppercase()}"
            }
            codec == Codec.DSD -> sampleRate?.let { "DSD${it / 44_100}" } ?: "DSD"
            codec.lossless -> buildString {
                append(if (codec == Codec.TRUEHD) "TrueHD" else codec.label)
                val depth = bitDepth
                val rate = sampleRate
                when {
                    depth != null && rate != null -> append(" $depth/${khz(rate)}")
                    rate != null -> append(" ${khz(rate)} kHz")
                    hiRes -> append(" Hi-Res")
                }
                channelSuffix()?.let { append(" $it") }
            }
            codec == Codec.EAC3 -> "DD+" + (channelSuffix()?.let { " $it" } ?: "")
            codec == Codec.AC3 -> "DD" + (channelSuffix()?.let { " $it" } ?: "")
            else -> buildString {
                append(if (codec == Codec.UNKNOWN) extension.uppercase().ifEmpty { "Audio" } else codec.label)
                bitrateKbps?.let { append(if (vbr) " V$it" else " $it") }
            }
        }

    /**
     * ExoPlayer can play it (decoded, or passed through to a receiver for Dolby/DTS). Raw TrueHD and
     * DTS files have no extractor; the same audio inside MKA/MKV/MP4 plays.
     */
    val playable: Boolean
        get() = codec !in setOf(Codec.DSD, Codec.APE, Codec.WAVPACK, Codec.WMA, Codec.AIFF) &&
            extension !in setOf("thd", "mlp", "truehd", "dts", "dtshd")

    private fun channelSuffix(): String? = when (channels) {
        null, 1, 2 -> null
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

    private fun khz(rate: Int): String {
        val value = rate / 1000.0
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)
    }

    /** Folds what the file's header says into what the name suggested. */
    fun withProbe(probe: ProbeResult): AudioInfo {
        val codec = if (probe.codec != Codec.UNKNOWN) probe.codec else codec
        val sampleRate = probe.sampleRate ?: sampleRate
        val bitDepth = probe.bitDepth ?: bitDepth
        val channels = probe.channels ?: channels
        val atmos = when {
            probe.atmos == true -> Atmos.VERIFIED
            probe.atmos == false -> Atmos.NONE
            codec !in AudioFormats.ATMOS_CAPABLE -> Atmos.NONE
            else -> atmos
        }
        val measuredHiRes = codec.lossless && codec != Codec.DSD && codec != Codec.TRUEHD &&
            ((bitDepth ?: 0) >= 24 || (sampleRate ?: 0) > 48_000)
        val knownPcm = bitDepth != null && sampleRate != null
        return copy(
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            durationSec = probe.durationSec ?: durationSec,
            atmos = atmos,
            surround = surround || (channels ?: 0) > 2 || atmos != Atmos.NONE,
            hiRes = if (knownPcm) measuredHiRes else hiRes || measuredHiRes,
            hiResGuessed = if (knownPcm) false else hiResGuessed,
        )
    }
}

/** Classifies files by name, size and the attributes Soulseek search results carry. */
object AudioFormats {
    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "mp4", "m4b", "aac", "ogg", "oga", "opus", "wma", "flac", "alac", "wav", "wave", "aif", "aiff",
        "aifc", "ape", "wv", "dsf", "dff", "ac3", "ec3", "eac3", "thd", "mlp", "truehd", "dts", "dtshd", "mka", "mkv",
        "m2ts", "mts",
    )

    internal val ATMOS_CAPABLE = setOf(Codec.EAC3, Codec.TRUEHD, Codec.UNKNOWN)

    private val ATMOS_WORDS = Regex("""\batmos\b|\bjoc\b|\b(5\.1\.[24]|7\.1\.[24]|9\.1\.6)\b|spatial audio""")
    private val DDP_WORDS = Regex("""\be-?ac-?3\b|\bec-?3\b|\bdd\+|\bddp\b|dolby digital plus""")
    private val TRUEHD_WORDS = Regex("""true-?hd""")
    private val DTS_WORDS = Regex("""\bdts\b""")
    private val FLAC_WORDS = Regex("""\bflac\b""")
    private val HIRES_WORDS = Regex(
        """24[ -]?bit|\b24[-/ ]?(44|48|88|96|176|192)\b|\b(88\.2|96|176\.4|192|352\.8|384) ?khz|\bhi-?res\b|\bhd ?audio\b""",
    )
    private val SURROUND_WORDS = Regex("""\b[57]\.1\b|multi-?channel|surround|\bquad\b""")

    fun isAudio(filename: String): Boolean = extensionOf(filename) in AUDIO_EXTENSIONS

    fun extensionOf(filename: String): String =
        filename.substringAfterLast('\\').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun classify(
        path: String,
        size: Long,
        bitrateKbps: Int? = null,
        durationSec: Int? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
        vbr: Boolean = false,
    ): AudioInfo {
        val extension = extensionOf(path)
        val name = path.lowercase()
        val estimatedKbps = if (durationSec != null && durationSec > 0 && size > 0) (size * 8 / 1000 / durationSec).toInt() else null
        val kbps = bitrateKbps?.takeIf { it > 0 } ?: estimatedKbps
        val atmosWords = ATMOS_WORDS.containsMatchIn(name)
        val ddpWords = DDP_WORDS.containsMatchIn(name)

        val codec = when (extension) {
            "mp3" -> Codec.MP3
            "aac" -> Codec.AAC
            "m4a", "mp4", "m4b" -> when {
                atmosWords || ddpWords -> Codec.EAC3
                // AAC tops out around 320 kbps; lossless ALAC in the same container is far above it.
                (kbps ?: 0) >= 450 -> Codec.ALAC
                else -> Codec.AAC
            }
            "ogg", "oga" -> Codec.VORBIS
            "opus" -> Codec.OPUS
            "wma" -> Codec.WMA
            "flac" -> Codec.FLAC
            "alac" -> Codec.ALAC
            "wav", "wave" -> Codec.WAV
            "aif", "aiff", "aifc" -> Codec.AIFF
            "ape" -> Codec.APE
            "wv" -> Codec.WAVPACK
            "dsf", "dff" -> Codec.DSD
            "ac3" -> Codec.AC3
            "ec3", "eac3" -> Codec.EAC3
            "thd", "mlp", "truehd" -> Codec.TRUEHD
            "dts", "dtshd" -> Codec.DTS
            else -> when {
                TRUEHD_WORDS.containsMatchIn(name) -> Codec.TRUEHD
                ddpWords -> Codec.EAC3
                DTS_WORDS.containsMatchIn(name) -> Codec.DTS
                FLAC_WORDS.containsMatchIn(name) && !atmosWords -> Codec.FLAC
                else -> Codec.UNKNOWN
            }
        }

        val atmos = if (atmosWords && codec in ATMOS_CAPABLE) Atmos.LIKELY else Atmos.NONE
        val atmosAsPcm = atmosWords && codec.lossless && codec != Codec.TRUEHD
        val surround = atmos != Atmos.NONE || atmosAsPcm || codec in setOf(Codec.AC3, Codec.EAC3, Codec.TRUEHD, Codec.DTS) ||
            SURROUND_WORDS.containsMatchIn(name)

        val pcmLossless = codec.lossless && codec != Codec.DSD && codec != Codec.TRUEHD
        val measured = sampleRate != null || bitDepth != null
        val hiResMeasured = pcmLossless && ((bitDepth ?: 0) >= 24 || (sampleRate ?: 0) > 48_000)
        // Stereo 16/44.1 FLAC averages 700-1100 kbps and 24/96 about 2500-3500; WAV 16/44.1 is 1411.
        val hiResByRate = pcmLossless && !surround && kbps != null && kbps > HIRES_KBPS
        val hiResByName = pcmLossless && HIRES_WORDS.containsMatchIn(name)
        val hiRes = hiResMeasured || (!measured && (hiResByRate || hiResByName))

        return AudioInfo(
            codec = codec,
            extension = extension,
            bitrateKbps = kbps,
            sampleRate = sampleRate?.takeIf { it > 0 },
            bitDepth = bitDepth?.takeIf { it > 0 },
            durationSec = durationSec?.takeIf { it > 0 },
            atmos = atmos,
            surround = surround,
            hiRes = hiRes,
            hiResGuessed = hiRes && !hiResMeasured,
            vbr = vbr,
            atmosAsPcm = atmosAsPcm,
        )
    }

    private const val HIRES_KBPS = 1_700
}

/** The format chips on the search and library pages. */
enum class FormatFilter(val label: String) {
    ALL("All"),
    MP3("MP3"),
    AAC("AAC / MP4"),
    FLAC("FLAC"),
    ALAC("ALAC"),
    WAV_AIFF("WAV / AIFF"),
    HI_RES("Hi-Res"),
    DSD("DSD"),
    SURROUND("Surround"),
    ATMOS("Atmos"),
    OTHER("Other"),
    ;

    fun matches(info: AudioInfo): Boolean = when (this) {
        ALL -> true
        MP3 -> info.codec == Codec.MP3
        AAC -> info.codec == Codec.AAC
        FLAC -> info.codec == Codec.FLAC
        ALAC -> info.codec == Codec.ALAC
        WAV_AIFF -> info.codec == Codec.WAV || info.codec == Codec.AIFF
        HI_RES -> info.hiRes
        DSD -> info.codec == Codec.DSD
        SURROUND -> info.surround && info.atmos == Atmos.NONE
        ATMOS -> info.atmos != Atmos.NONE
        OTHER -> info.atmos == Atmos.NONE &&
            info.codec in setOf(Codec.VORBIS, Codec.OPUS, Codec.WMA, Codec.APE, Codec.WAVPACK, Codec.UNKNOWN)
    }
}
