package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFactsTest {
    @Test
    fun torrentioStyleStreamGivesTheCardLines() {
        val stream = Stream(
            infoHash = "abc",
            name = "Torrentio\n4k DV | HDR",
            title = "The.Last.Horizon.2026.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-GRP\n👤 18 💾 68.4 GB ⚙️ ThePirateBay\n🇬🇧 / 🇮🇹",
        )
        val facts = StreamFacts.of(stream)
        assertEquals("4K · Blu-ray REMUX · Dolby Vision", facts.headline)
        assertEquals("HEVC", facts.codec)
        assertEquals("TrueHD 7.1 Atmos", facts.audio)
        assertEquals(listOf("English", "Italian"), facts.languages)
        assertEquals(18, facts.seeders)
        assertEquals((68.4 * (1L shl 30)).toLong(), facts.sizeBytes)
    }

    @Test
    fun indianReleaseNamesListTheirLanguages() {
        val stream = Stream(url = "https://a.example/x.mkv", name = "Movie 1080p WEB-DL [Tam + Tel + Hin + Mal] x264 AAC 5.1 4.2GB 🇮🇳")
        val facts = StreamFacts.of(stream)
        assertEquals("1080p · WEB-DL", facts.headline)
        assertEquals("H.264", facts.codec)
        assertEquals("AAC 5.1", facts.audio)
        // India's flag says nothing more once the languages are named.
        assertEquals(listOf("Malayalam", "Tamil", "Hindi", "Telugu"), facts.languages)
    }

    @Test
    fun nothingIsGuessed() {
        val facts = StreamFacts.of(Stream(url = "https://a.example/movie.mp4", name = "Direct", title = "Telesync is not here; HDRip neither"))
        assertNull(facts.quality)
        assertEquals("HDRip", facts.source)
        assertNull("HDRip is not HDR", facts.hdr)
        assertEquals(emptyList<String>(), facts.languages)
        assertEquals("SD", StreamFacts.qualityOf(480))
        assertEquals(listOf("Multi audio"), StreamFacts.languages("Movie.MULTi.1080p"))
    }
}
