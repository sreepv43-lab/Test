package io.github.sreepv43.streamhub.torrent

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.random.Random

/**
 * End-to-end test of the torrent engine and its HTTP server against a real libtorrent seeder on
 * localhost. Needs the desktop native library, which the Gradle build provides on Linux x86-64.
 */
class TorrentEngineTest {
    private lateinit var root: File
    private lateinit var seeder: SessionManager
    private lateinit var engine: TorrentEngine

    @Before
    fun setUp() {
        val native = System.getProperty("libtorrent4j.jni.path")
        assumeTrue("libtorrent native library not available", native != null && File(native).exists())
        root = Files.createTempDirectory("torrent-test").toFile()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::seeder.isInitialized) seeder.stop()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun streamsSeeksAndCleansUp() {
        val pack = File(root, "seed/pack").apply { mkdirs() }
        val movie = Random(1).nextBytes(9 * 1024 * 1024 + 12345)
        File(pack, "movie.mkv").writeBytes(movie)
        File(pack, "readme.txt").writeText("hello")
        val torrentBytes = TorrentBuilder().path(pack).pieceSize(256 * 1024).generate().entry().bencode()
        val info = TorrentInfo(torrentBytes)

        seeder = SessionManager(false)
        val seederSettings = SettingsPack().listenInterfaces("127.0.0.1:47301")
        seederSettings.setEnableDht(false)
        seederSettings.setEnableLsd(false)
        seeder.start(SessionParams(seederSettings))
        seeder.download(info, File(root, "seed"))

        engine = TorrentEngine(
            cacheDirs = { listOf(File(root, "cache")) },
            fetchTorrentFile = { File(it.removePrefix("file://")).readBytes() },
            idleTimeoutMs = 1_000,
            configure = {
                it.listenInterfaces("127.0.0.1:47302")
                it.setEnableDht(false)
                it.setEnableLsd(false)
            },
        )
        val server = TorrentHttpServer(engine)
        val magnet = TorrentLinks.magnet(info.infoHash().toHex(), "pack") + "&x.pe=127.0.0.1:47301"
        val url = server.urlFor(magnet, -1)

        // A seek into the middle of the largest video file, before anything is downloaded.
        val ranged = open(url, "bytes=5000000-5100000")
        assertEquals(206, ranged.responseCode)
        assertEquals("bytes 5000000-5100000/${movie.size}", ranged.getHeaderField("Content-Range"))
        assertEquals("video/x-matroska", ranged.contentType)
        assertArrayEquals(movie.copyOfRange(5000000, 5100001), ranged.inputStream.readBytes())

        val full = open(url, null)
        assertEquals(200, full.responseCode)
        assertArrayEquals(movie, full.inputStream.readBytes())
        assertEquals(1f, engine.stats(magnet)!!.fileProgress, 0.001f)

        // A .torrent file as source, selecting a specific file.
        val torrentFile = File(root, "x.torrent").apply { writeBytes(torrentBytes) }
        val readme = (0 until info.numFiles()).first { info.files().fileName(it) == "readme.txt" }
        val small = open(server.urlFor("file://" + torrentFile.path, readme), null)
        assertEquals("hello", small.inputStream.readBytes().decodeToString())

        // Unused torrents are removed and their data deleted.
        val until = System.currentTimeMillis() + 20_000
        while (engine.stats(magnet) != null && System.currentTimeMillis() < until) Thread.sleep(200)
        assertNull(engine.stats(magnet))
        Thread.sleep(6_000)
        assertEquals(0L, engine.cacheSizeBytes())
    }

    private fun open(url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            readTimeout = 60_000
            if (range != null) setRequestProperty("Range", range)
        }
}
