package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAudioTest {
    private fun label(text: String) = StreamAudio.parse(text)?.label

    @Test
    fun readsCommonReleaseNames() {
        assertEquals("DD+ 5.1 Atmos", label("Movie.2023.2160p.WEB-DL.DDP5.1.Atmos.H.265"))
        assertEquals("DD+ 5.1", label("Movie 1080p WEB DD+ 5.1 x264"))
        assertEquals("DD+ 5.1", label("Movie.1080p.EAC3.5.1"))
        assertEquals("Dolby Digital 5.1", label("Movie.2008.1080p.BluRay.DD5.1.x264"))
        assertEquals("Dolby Digital 5.1", label("Movie 720p AC3 5.1"))
        assertEquals("DTS-HD 7.1", label("Movie.1080p.BluRay.DTS-HD.MA.7.1"))
        assertEquals("DTS:X 7.1", label("Movie.2160p.REMUX.DTS-X.7.1"))
        assertEquals("DTS 5.1", label("Movie.1080p.DTS.5.1"))
        assertEquals("TrueHD 7.1 Atmos", label("Movie.2160p.BluRay.REMUX.TrueHD.7.1.Atmos"))
        assertEquals("AAC 5.1", label("Movie.1080p.WEBRip.AAC5.1.x265"))
        assertEquals("AAC 2.0", label("Show.S01E01.720p.AAC2.0"))
        assertNull(StreamAudio.parse("Movie.2020.1080p.WEB.x264"))
    }

    @Test
    fun wordsThatOnlyContainTheLettersDontCount() {
        assertNull(StreamAudio.parse("The Odd Couple ADDAMS 1080p"))
    }

    @Test
    fun passthroughOverArcAndEarc() {
        val ddp = StreamAudio.parse("DDP5.1")!!
        val truehd = StreamAudio.parse("TrueHD 7.1")!!
        val aac = StreamAudio.parse("AAC5.1")!!
        assertTrue(ddp.passesThrough("arc"))
        assertFalse(ddp.passesThrough("auto"))
        assertFalse(truehd.passesThrough("arc"))
        assertTrue(truehd.passesThrough("earc"))
        assertFalse(aac.passesThrough("earc"))
    }

    @Test
    fun withPassthroughDolbyWinsAtTheSameQuality() {
        fun torrent(title: String) = Stream(infoHash = "b".repeat(40), name = "Torrent", title = title)
        val aac = torrent("Movie.1080p.WEB.AAC5.1 👤 500 💾 2 GB")
        val ddp = torrent("Movie.1080p.WEB.DDP5.1 👤 100 💾 3 GB")
        val sharper = torrent("Movie.720p.DDP5.1 👤 900 💾 1 GB")
        assertEquals(listOf(aac, ddp, sharper), StreamRanking.rank(listOf(sharper, ddp, aac), passthrough = "auto"))
        assertEquals(listOf(ddp, aac, sharper), StreamRanking.rank(listOf(sharper, aac, ddp), passthrough = "arc"))
    }
}
