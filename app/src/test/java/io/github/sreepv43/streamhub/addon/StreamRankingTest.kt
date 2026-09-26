package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRankingTest {
    private fun torrent(name: String, title: String, group: String? = null) = Stream(
        infoHash = "a".repeat(40),
        name = name,
        title = title,
        behaviorHints = StreamHints(bingeGroup = group),
    )

    private fun direct(name: String) = Stream(url = "https://cdn.example/video.mp4", name = name)

    @Test
    fun readsQualitySizeAndSeedersFromTheDescription() {
        val info = StreamRanking.info(torrent("Torrentio\n1080p", "Movie.2020.1080p.WEB\n👤 42 💾 2.3 GB ⚙️ Source"))
        assertEquals(1080, info.resolution)
        assertEquals(42, info.seeders)
        assertEquals((2.3 * (1L shl 30)).toLong(), info.sizeBytes)
    }

    @Test
    fun understandsCommonNamings() {
        assertEquals(2160, StreamRanking.resolution("Movie 4K HDR"))
        assertEquals(720, StreamRanking.resolution("Show.S01E02.720p"))
        assertNull(StreamRanking.resolution("Movie"))
        assertEquals(12, StreamRanking.seeders("Seeders: 12"))
        assertEquals(700L shl 20, StreamRanking.size("700 MB"))
    }

    @Test
    fun prefersTheTvsResolutionThenSeeders() {
        val few = torrent("1080p", "👤 3 💾 2 GB")
        val many = torrent("1080p", "👤 300 💾 4 GB")
        val uhd = torrent("4K", "👤 500 💾 20 GB")
        val low = torrent("720p", "👤 900 💾 1 GB")
        val ranked = StreamRanking.rank(listOf(uhd, few, low, many), maxResolution = 1080)
        assertEquals(listOf(many, few, low, uhd), ranked)
    }

    @Test
    fun a4kTvPrefers4k() {
        val hd = torrent("1080p", "👤 300")
        val uhd = torrent("2160p", "👤 50")
        assertEquals(uhd, StreamRanking.best(listOf(hd, uhd), maxResolution = 2160))
    }

    @Test
    fun camRecordingsAndExternalLinksComeLast() {
        val cam = torrent("1080p", "Movie.2024.HDCAM 👤 999")
        val web = Stream(externalUrl = "https://example.com/watch", name = "1080p")
        val ok = torrent("720p", "👤 5")
        assertEquals(listOf(ok, cam, web), StreamRanking.rank(listOf(web, cam, ok)))
        assertEquals(ok, StreamRanking.best(listOf(web, cam, ok)))
    }

    @Test
    fun directLinksWinAtTheSameQuality() {
        val link = direct("1080p")
        val torrent = torrent("1080p", "👤 1000")
        assertEquals(link, StreamRanking.best(listOf(torrent, link)))
    }

    @Test
    fun nextEpisodeKeepsTheSameReleaseGroup() {
        val sameGroup = torrent("720p", "👤 5", group = "torrentio|720p|WEB")
        val better = torrent("1080p", "👤 500", group = "torrentio|1080p|BluRay")
        assertEquals(sameGroup, StreamRanking.next(listOf(better, sameGroup), "torrentio|720p|WEB"))
        assertEquals(better, StreamRanking.next(listOf(better, sameGroup), "other"))
    }
}
