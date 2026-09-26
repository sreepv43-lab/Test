package io.github.sreepv43.soundhub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatsTest {
    @Test
    fun mp3WithBitrate() {
        val info = AudioFormats.classify("@@u\\Music\\A\\01 Song.mp3", 9_000_000, bitrateKbps = 320)
        assertEquals(Codec.MP3, info.codec)
        assertEquals("MP3 320", info.label)
        assertTrue(FormatFilter.MP3.matches(info))
        assertFalse(FormatFilter.HI_RES.matches(info))
    }

    @Test
    fun flacWithReportedHiResAttributes() {
        val info = AudioFormats.classify("A\\01.flac", 100_000_000, durationSec = 300, sampleRate = 96_000, bitDepth = 24)
        assertEquals("FLAC 24/96", info.label)
        assertTrue(info.hiRes)
        assertFalse(info.hiResGuessed)
        assertTrue(FormatFilter.FLAC.matches(info) && FormatFilter.HI_RES.matches(info))
    }

    @Test
    fun cdQualityFlacIsNotHiRes() {
        val info = AudioFormats.classify("A\\01.flac", 30_000_000, durationSec = 240, sampleRate = 44_100, bitDepth = 16)
        assertEquals("FLAC 16/44.1", info.label)
        assertFalse(info.hiRes)
    }

    @Test
    fun hiResGuessedFromBitrateWhenAttributesAreMissing() {
        // ~2900 kbps over 5 minutes.
        val info = AudioFormats.classify("A\\01.flac", 110_000_000, durationSec = 300)
        assertTrue(info.hiRes)
        assertTrue(info.hiResGuessed)
        assertEquals("FLAC Hi-Res", info.label)
    }

    @Test
    fun hiResGuessedFromFolderName() {
        assertTrue(AudioFormats.classify("Artist - Album (2020) [24-96]\\01.flac", 50_000_000).hiRes)
        assertTrue(AudioFormats.classify("Album [FLAC 24bit]\\01.flac", 50_000_000).hiRes)
        assertFalse(AudioFormats.classify("Album [FLAC 24bit]\\01.mp3", 5_000_000).hiRes)
    }

    @Test
    fun m4aIsAacAlacOrAtmosDependingOnBitrateAndName() {
        assertEquals(Codec.AAC, AudioFormats.classify("A\\01.m4a", 8_000_000, bitrateKbps = 256).codec)
        assertEquals(Codec.ALAC, AudioFormats.classify("A\\01.m4a", 30_000_000, durationSec = 240).codec)
        val atmos = AudioFormats.classify("Artist - Album (Dolby Atmos)\\01.m4a", 25_000_000, durationSec = 240)
        assertEquals(Codec.EAC3, atmos.codec)
        assertEquals(Atmos.LIKELY, atmos.atmos)
        assertEquals("DD+ Atmos", atmos.label)
        assertTrue(FormatFilter.ATMOS.matches(atmos))
        assertTrue(FormatFilter.AAC.matches(AudioFormats.classify("A\\01.mp4", 8_000_000, bitrateKbps = 256)))
    }

    @Test
    fun trueHdAtmosFromBluRayRips() {
        val info = AudioFormats.classify("Album [Blu-ray] [TrueHD Atmos 7.1.4]\\01.mka", 300_000_000)
        assertEquals(Codec.TRUEHD, info.codec)
        assertEquals(Atmos.LIKELY, info.atmos)
        assertEquals("TrueHD Atmos", info.label)
        assertEquals(Codec.TRUEHD, AudioFormats.classify("A\\01.thd", 1).codec)
        assertFalse(AudioFormats.classify("A\\01.thd", 1).playable)
        assertTrue(info.playable)
    }

    @Test
    fun atmosMixesAsFlacAreSurroundNotAtmos() {
        val info = AudioFormats.classify("Album (Dolby Atmos 5.1 FLAC)\\01.flac", 100_000_000, durationSec = 300)
        assertEquals(Codec.FLAC, info.codec)
        assertEquals(Atmos.NONE, info.atmos)
        assertTrue(info.atmosAsPcm)
        assertTrue(FormatFilter.SURROUND.matches(info))
        assertFalse(FormatFilter.ATMOS.matches(info))
        // A big multichannel file isn't taken for stereo hi-res.
        assertFalse(info.hiRes)
    }

    @Test
    fun plainDolbyDigitalPlusIsSurround() {
        val info = AudioFormats.classify("Album [DD+ 5.1]\\01.ec3", 20_000_000)
        assertEquals(Codec.EAC3, info.codec)
        assertEquals(Atmos.NONE, info.atmos)
        assertTrue(FormatFilter.SURROUND.matches(info))
    }

    @Test
    fun probeVerifiesOrRulesOutAtmos() {
        val likely = AudioFormats.classify("Album (Atmos)\\01.m4a", 25_000_000)
        assertEquals(Atmos.VERIFIED, likely.withProbe(ProbeResult(Codec.EAC3, 48_000, channels = 6, atmos = true)).atmos)
        assertEquals(Atmos.NONE, likely.withProbe(ProbeResult(Codec.EAC3, 48_000, channels = 6, atmos = false)).atmos)
        // It was really ALAC.
        val alac = likely.withProbe(ProbeResult(Codec.ALAC, 48_000, 24, 2))
        assertEquals(Codec.ALAC, alac.codec)
        assertEquals(Atmos.NONE, alac.atmos)
        assertTrue(alac.hiRes)
    }

    @Test
    fun probeCorrectsGuessedHiRes() {
        val guessed = AudioFormats.classify("Album [24-96]\\01.flac", 50_000_000)
        assertTrue(guessed.hiRes)
        val real = guessed.withProbe(ProbeResult(Codec.FLAC, 44_100, 16, 2))
        assertFalse(real.hiRes)
        assertEquals("FLAC 16/44.1", real.label)
    }

    @Test
    fun dsdAndOtherFormats() {
        assertTrue(FormatFilter.DSD.matches(AudioFormats.classify("A\\01.dsf", 1)))
        assertFalse(AudioFormats.classify("A\\01.dsf", 1).playable)
        assertTrue(FormatFilter.WAV_AIFF.matches(AudioFormats.classify("A\\01.aiff", 1)))
        assertTrue(FormatFilter.OTHER.matches(AudioFormats.classify("A\\01.ape", 1)))
        assertTrue(FormatFilter.OTHER.matches(AudioFormats.classify("A\\01.opus", 1)))
    }

    @Test
    fun onlyAudioFilesCount() {
        assertTrue(AudioFormats.isAudio("A\\01.FLAC"))
        assertFalse(AudioFormats.isAudio("A\\cover.jpg"))
        assertFalse(AudioFormats.isAudio("A\\album.cue"))
    }
}
