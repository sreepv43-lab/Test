package io.github.sreepv43.streamhub.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Audio output: surround passthrough (as Android reports it, or forced for receivers the TV
 * doesn't report), software decoding with FFmpeg for formats the device can't decode (DTS, TrueHD),
 * and a one-line description of what is happening for the player's info label.
 */
@OptIn(UnstableApi::class)
object AudioOutput {
    /** [passthrough]: "auto", "arc" (Dolby Digital, DD+, DTS) or "earc" (also TrueHD, DTS-HD, Atmos). */
    fun renderersFactory(context: Context, passthrough: String): DefaultRenderersFactory {
        val forced = forcedEncodings(passthrough)
        return object : DefaultRenderersFactory(context) {
            @Suppress("DEPRECATION")
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                if (forced == null) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                // Without a context the sink uses these fixed capabilities instead of asking Android.
                return DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities(forced, MAX_CHANNELS))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
            // Extension renderers (FFmpeg) come after the device's own decoders and passthrough.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    }

    private fun forcedEncodings(passthrough: String): IntArray? = when (passthrough) {
        "arc" -> intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS)
        "earc" -> intArrayOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_AC3,
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS,
            C.ENCODING_DTS_HD,
            C.ENCODING_DOLBY_TRUEHD,
        )
        else -> null
    }

    /** "E-AC3 5.1 → passthrough to the receiver", "DTS 5.1 → decoded by FFmpeg, 5.1 out", … */
    fun describe(input: Format?, decoder: String?, track: AudioSink.AudioTrackConfig?, forced: Boolean): String? {
        input ?: return null
        val source = listOfNotNull(codecName(input), channels(input.channelCount)).joinToString(" ")
        track ?: return "Audio  $source"
        val output = when {
            !isPcm(track.encoding) ->
                "passthrough (${encodingName(track.encoding)}) to the receiver" + if (forced) ", forced" else ""
            else -> {
                val by = when {
                    decoder == null -> "decoded"
                    decoder.startsWith("ffmpeg", ignoreCase = true) -> "decoded by FFmpeg"
                    else -> "decoded by the TV"
                }
                "$by, ${channels(Integer.bitCount(track.channelConfig)) ?: "PCM"} out"
            }
        }
        return "Audio  $source → $output"
    }

    private fun isPcm(encoding: Int) = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT ||
        encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_8BIT

    private fun channels(count: Int): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count ch"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "DD+"
        C.ENCODING_E_AC3_JOC -> "DD+ Atmos"
        C.ENCODING_AC4 -> "AC-4"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        else -> "encoded"
    }

    private fun codecName(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 Atmos"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    private const val MAX_CHANNELS = 8
}
