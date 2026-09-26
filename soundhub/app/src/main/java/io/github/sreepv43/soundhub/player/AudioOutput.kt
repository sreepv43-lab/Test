package io.github.sreepv43.soundhub.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** What the player is sending out right now, for the Now Playing page. */
data class OutputState(
    /** "DD+ Atmos 5.1", "FLAC 24-bit 96 kHz". */
    val input: String,
    /** "Bitstream to the receiver (Dolby Atmos)", "Decoded here: stereo PCM". */
    val output: String,
    val passthrough: Boolean,
    /** Set when Atmos (or surround) is lost on the way, with what to do about it. */
    val warning: String? = null,
)

/** What this device says its HDMI output can carry. */
data class OutputReport(
    val connection: String,
    val ddPlusAtmos: Boolean,
    val trueHd: Boolean,
    val dolbyDigital: Boolean,
    val dts: Boolean,
    val maxPcmChannels: Int,
    val forcedMode: String,
)

/**
 * Dolby Atmos reaches the receiver untouched ("passthrough"): the app never decodes it, the
 * receiver does. Android reports what the HDMI sink accepts; when a TV or box under-reports, the
 * output mode setting forces it: "hdmi" (box plugged into the receiver, or eARC: everything incl.
 * TrueHD) or "arc" (plain ARC: Dolby Digital, DD+ incl. Atmos, DTS core).
 */
@OptIn(UnstableApi::class)
object AudioOutput {
    const val MODE_AUTO = "auto"
    const val MODE_HDMI = "hdmi"
    const val MODE_ARC = "arc"

    fun renderersFactory(context: Context, mode: String): DefaultRenderersFactory {
        val forced = forcedEncodings(mode)
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
            // FFmpeg only for what the device can neither decode nor pass through.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    }

    private fun forcedEncodings(mode: String): IntArray? {
        val joc = if (Build.VERSION.SDK_INT >= 28) intArrayOf(C.ENCODING_E_AC3_JOC) else intArrayOf()
        return when (mode) {
            MODE_ARC -> intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS) + joc
            MODE_HDMI -> intArrayOf(
                C.ENCODING_PCM_16BIT,
                C.ENCODING_AC3,
                C.ENCODING_E_AC3,
                C.ENCODING_DTS,
                C.ENCODING_DTS_HD,
                C.ENCODING_DOLBY_TRUEHD,
            ) + joc
            else -> null
        }
    }

    /** What Android says the current HDMI output accepts (Media3's view plus the device list). */
    fun report(context: Context, mode: String): OutputReport {
        @Suppress("DEPRECATION")
        val caps = AudioCapabilities.getCapabilities(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val hdmi = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC)
        }
        val connection = when {
            Build.VERSION.SDK_INT >= 31 && hdmi.any { it.type == AudioDeviceInfo.TYPE_HDMI_EARC } -> "HDMI eARC"
            hdmi.any { it.type == AudioDeviceInfo.TYPE_HDMI_ARC } -> "HDMI ARC"
            hdmi.any { it.type == AudioDeviceInfo.TYPE_HDMI } -> "HDMI"
            else -> "No HDMI audio output (speakers, headphones or Bluetooth)"
        }
        val deviceEncodings = hdmi.flatMap { it.encodings.toList() }.toSet()
        fun supported(media3: Int, platform: Int?) = caps.supportsEncoding(media3) || (platform != null && platform in deviceEncodings)
        return OutputReport(
            connection = connection,
            ddPlusAtmos = supported(C.ENCODING_E_AC3_JOC, if (Build.VERSION.SDK_INT >= 28) AudioFormat.ENCODING_E_AC3_JOC else null) ||
                // Receivers get JOC inside a DD+ bitstream even when Android only lists DD+.
                supported(C.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3),
            trueHd = supported(C.ENCODING_DOLBY_TRUEHD, if (Build.VERSION.SDK_INT >= 25) AudioFormat.ENCODING_DOLBY_TRUEHD else null),
            dolbyDigital = supported(C.ENCODING_AC3, AudioFormat.ENCODING_AC3),
            dts = supported(C.ENCODING_DTS, AudioFormat.ENCODING_DTS),
            maxPcmChannels = maxOf(caps.maxChannelCount, hdmi.flatMap { it.channelCounts.toList() }.maxOrNull() ?: 0),
            forcedMode = mode,
        )
    }

    fun describe(input: Format?, track: AudioSink.AudioTrackConfig?, atmosKnown: Boolean): OutputState? {
        input ?: return null
        val source = listOfNotNull(codecName(input, atmosKnown), channels(input.channelCount)).joinToString(" ")
        track ?: return OutputState(source, "Starting…", passthrough = false)
        if (!isPcm(track.encoding)) {
            val what = when (track.encoding) {
                C.ENCODING_E_AC3_JOC -> "Dolby Atmos (DD+)"
                C.ENCODING_E_AC3 -> if (input.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC || atmosKnown) "Dolby Atmos (DD+)" else "Dolby Digital Plus"
                C.ENCODING_DOLBY_TRUEHD -> if (atmosKnown) "Dolby TrueHD Atmos" else "Dolby TrueHD"
                C.ENCODING_AC3 -> "Dolby Digital"
                C.ENCODING_DTS, C.ENCODING_DTS_HD -> "DTS"
                else -> "encoded audio"
            }
            return OutputState(source, "Bitstream to the receiver: $what", passthrough = true)
        }
        val count = Integer.bitCount(track.channelConfig)
        val output = "Decoded here: ${channels(count) ?: "PCM"} PCM, ${track.sampleRate / 1000.0} kHz".replace(".0 kHz", " kHz")
        val warning = when {
            dolby(input) && (input.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC || atmosKnown) ->
                "Atmos is lost: this output didn't accept the Dolby bitstream. Check Settings → Audio output, " +
                    "and the box's Settings → Display & Sound → Advanced sound (Surround: Auto)."
            dolby(input) -> "Surround is decoded here instead of by the receiver. Check Settings → Audio output."
            count > 2 -> "Over plain ARC the TV turns multichannel PCM into stereo."
            else -> null
        }
        return OutputState(source, output, passthrough = false, warning = warning)
    }

    private fun dolby(format: Format) = format.sampleMimeType in setOf(
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
    )

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

    private fun codecName(format: Format, atmosKnown: Boolean): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> if (atmosKnown) "DD+ Atmos" else "DD+"
        MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
        MimeTypes.AUDIO_TRUEHD -> if (atmosKnown) "TrueHD Atmos" else "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        "audio/alac" -> "ALAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase()
    }?.let { name ->
        val rate = format.sampleRate.takeIf { it > 0 }?.let { " ${it / 1000.0} kHz".replace(".0 kHz", " kHz") }.orEmpty()
        name + rate
    }

    private const val MAX_CHANNELS = 8
}
