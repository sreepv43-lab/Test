package io.github.sreepv43.streamhub.torrent

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.concurrent.thread
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
        seederSettings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
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
                // The seeder starts listening asynchronously; retry it quickly if the first dial is early.
                it.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
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

        // Unused torrents are let go of; played ones keep their data for next time, until cleanup.
        val until = System.currentTimeMillis() + 20_000
        while (engine.stats(magnet) != null && System.currentTimeMillis() < until) Thread.sleep(200)
        assertNull(engine.stats(magnet))
        Thread.sleep(4_000)
        assertTrue(engine.cacheSizeBytes() >= movie.size)
        engine.clearCache()
        assertEquals(0L, engine.cacheSizeBytes())
    }

    @Test
    fun prefetchGetsTheDetailsAndOnlyTheStartOfTheVideo() {
        val pack = File(root, "seed/pack").apply { mkdirs() }
        val movie = Random(2).nextBytes(48 * 1024 * 1024 + 777)
        File(pack, "movie.mkv").writeBytes(movie)
        val torrentBytes = TorrentBuilder().path(pack).pieceSize(256 * 1024).generate().entry().bencode()
        val info = TorrentInfo(torrentBytes)

        seeder = SessionManager(false)
        val seederSettings = SettingsPack().listenInterfaces("127.0.0.1:47303")
        seederSettings.setEnableDht(false)
        seederSettings.setEnableLsd(false)
        // After the prefetch the two sides have nothing to exchange and disconnect; libtorrent would
        // then wait a minute before dialling the same peer again (a real swarm has other peers).
        seederSettings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
        seeder.start(SessionParams(seederSettings))
        seeder.download(info, File(root, "seed"))

        engine = TorrentEngine(
            cacheDirs = { listOf(File(root, "cache")) },
            fetchTorrentFile = { File(it.removePrefix("file://")).readBytes() },
            idleTimeoutMs = 60_000,
            configure = {
                it.listenInterfaces("127.0.0.1:47304")
                it.setEnableDht(false)
                it.setEnableLsd(false)
                // The seeder starts listening asynchronously; retry it quickly if the first dial is early.
                it.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
            },
        )
        // A .torrent file (a magnet link works the same, but on localhost the whole file arrives
        // in the moment between getting the details and choosing what to download).
        val source = "file://" + File(root, "x.torrent").apply { writeBytes(torrentBytes) }.path
        engine.prefetch(source, -1, warmStart = true)

        val eightMb = 8L * 1024 * 1024
        val until = System.currentTimeMillis() + 30_000
        fun downloaded() = engine.stats(source, 0)?.fileDownloadedBytes ?: 0L
        // The .torrent file lists no trackers here, so the seeder dials the engine itself.
        val seed = seeder.find(info.infoHash())
        while (downloaded() < eightMb && System.currentTimeMillis() < until) {
            seed.swig().connect_peer(TcpEndpoint("127.0.0.1", 47304).swig())
            Thread.sleep(500)
        }
        Thread.sleep(2_000)
        val stats = engine.stats(source, 0)!!
        assertTrue(stats.hasMetadata)
        assertTrue("the first 8 MB are ready (${downloaded()})", downloaded() >= eightMb)
        // (Requests already on their way when the limit is set can bring a few MB more.)
        assertTrue("not the whole file (${downloaded()} of ${movie.size})", downloaded() < movie.size / 2)

        // Playing it afterwards reads the whole file as usual (the seeder keeps dialling in, as
        // trackers would bring peers back).
        var read: ByteArray? = null
        val reader = thread { read = open(TorrentHttpServer(engine).urlFor(source, -1), null).inputStream.readBytes() }
        while (reader.isAlive) {
            seed.swig().connect_peer(TcpEndpoint("127.0.0.1", 47304).swig())
            reader.join(500)
        }
        assertArrayEquals(movie, read)
    }

    /** Seeds [movie] (as pack/movie.mkv) from 127.0.0.1:[port]; returns the torrent and a magnet link to it. */
    private fun seed(movie: ByteArray, port: Int): Pair<TorrentInfo, String> {
        val pack = File(root, "seed/pack").apply { mkdirs() }
        File(pack, "movie.mkv").writeBytes(movie)
        val info = TorrentInfo(TorrentBuilder().path(pack).pieceSize(256 * 1024).generate().entry().bencode())
        seeder = SessionManager(false)
        val settings = SettingsPack().listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        settings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
        seeder.start(SessionParams(settings))
        seeder.download(info, File(root, "seed"))
        return info to TorrentLinks.magnet(info.infoHash().toHex(), "pack") + "&x.pe=127.0.0.1:$port"
    }

    private fun localEngine(
        port: Int,
        chooseCache: () -> CacheChoice,
        rules: (File) -> CacheRules = { CacheRules() },
        cacheDirs: List<File>,
        idleTimeoutMs: Long = 60_000,
    ) =
        TorrentEngine(
            cacheDirs = { cacheDirs.filter { it.exists() } },
            fetchTorrentFile = { File(it.removePrefix("file://")).readBytes() },
            idleTimeoutMs = idleTimeoutMs,
            chooseCache = chooseCache,
            cacheRules = rules,
            configure = {
                it.listenInterfaces("127.0.0.1:$port")
                it.setEnableDht(false)
                it.setEnableLsd(false)
                // The seeder starts listening asynchronously; retry it quickly if the first dial is early.
                it.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
            },
            storageCheckSeconds = 1,
        )

    @Test
    fun aMissingChosenDriveStoresNothingAnywhereElse() {
        val (_, magnet) = seed(Random(3).nextBytes(1024 * 1024), 47311)
        val internal = File(root, "internal-cache").apply { mkdirs() }
        val message = "Seagate 2TB (torrent cache) isn't connected."
        engine = localEngine(47312, { CacheChoice.Unavailable(message) }, cacheDirs = listOf(internal))
        val response = open(TorrentHttpServer(engine).urlFor(magnet, -1), null)
        assertEquals(504, response.responseCode)
        assertEquals(message, engine.problemFor(magnet))
        assertTrue("nothing was written to internal storage", internal.listFiles().isNullOrEmpty())
    }

    @Test
    fun cleanupKeepsWhatIsInUseAndNeverTouchesDownloads() {
        val movie = Random(4).nextBytes(2 * 1024 * 1024 + 99)
        val (_, magnet) = seed(movie, 47313)
        val drive = File(root, "usb").apply { mkdirs() }
        val cache = File(drive, "cache").apply { mkdirs() }
        val download = File(drive, "StreamHub/movie.mkv").apply { parentFile.mkdirs(); writeBytes(movie) }
        val volume = CacheVolume("1A2B-3C4D", "Seagate", cache, true, drive.totalSpace, drive.usableSpace)
        engine = localEngine(
            47314,
            { CacheChoice.Ready(volume, cache) },
            // A tiny target, so every torrent nobody uses must go.
            rules = { CacheRules(targetBytes = 1) },
            cacheDirs = listOf(cache),
        )
        val file = engine.open(magnet, -1)
        val bytes = ByteArray(movie.size)
        var position = 0
        while (position < movie.size) {
            val read = file.read(position.toLong(), bytes, position, movie.size - position, 30_000)
            assertTrue(read > 0)
            position += read
        }
        assertArrayEquals(movie, bytes)
        assertTrue("written straight to the chosen drive", engine.sizeOf(cache) >= movie.size)

        engine.trimCache()
        assertTrue("the torrent being played stays", engine.sizeOf(cache) >= movie.size)
        file.close()
        engine.trimCache()
        Thread.sleep(6_000)
        assertEquals("unused cached torrents are removed", 0L, engine.sizeOf(cache))
        assertArrayEquals("downloads are never touched", movie, download.readBytes())
    }

    @Test
    fun unpluggingTheDriveStopsTheTorrentWithAClearMessage() {
        val movie = Random(5).nextBytes(1024 * 1024 + 7)
        val (_, magnet) = seed(movie, 47315)
        val drive = File(root, "usb").apply { mkdirs() }
        val cache = File(drive, "cache").apply { mkdirs() }
        val volume = CacheVolume("1A2B-3C4D", "Seagate", cache, true, drive.totalSpace, drive.usableSpace)
        engine = localEngine(47316, { CacheChoice.Ready(volume, cache) }, cacheDirs = listOf(cache))
        val file = engine.open(magnet, -1)
        assertTrue(file.read(0, ByteArray(1024), 0, 1024, 30_000) > 0)

        // The drive disappears (as /storage/<uuid> does when it is unplugged).
        val unplugged = File(root, "unplugged")
        assertTrue(drive.renameTo(unplugged))
        val until = System.currentTimeMillis() + 10_000
        while (engine.problemFor(magnet) == null && System.currentTimeMillis() < until) Thread.sleep(200)
        val problem = engine.problemFor(magnet)
        assertTrue("$problem", problem!!.contains("disconnected"))
        val error = runCatching { file.read(0, ByteArray(1024), 0, 1024, 1_000) }.exceptionOrNull()
        assertTrue("reading stops with the message: $error", error is TorrentException && error.message == problem)
        file.close()

        // Plugged back in: the title plays again.
        assertTrue(unplugged.renameTo(drive))
        val again = engine.open(magnet, -1)
        assertTrue(again.read(0, ByteArray(1024), 0, 1024, 30_000) > 0)
        again.close()
    }

    private fun readAll(file: TorrentEngine.TorrentFile, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var position = 0
        while (position < size) {
            val read = file.read(position.toLong(), bytes, position, size - position, 30_000)
            assertTrue("data arrives", read > 0)
            position += read
        }
        return bytes
    }

    @Test
    fun aTitlePlayedBeforeIsReadBackFromTheDriveWithoutDownloadingItAgain() {
        val movie = Random(6).nextBytes(3 * 1024 * 1024 + 5)
        val (info, magnet) = seed(movie, 47317)
        val hash = info.infoHash().toHex().lowercase()
        val drive = File(root, "usb").apply { mkdirs() }
        val cache = File(drive, "cache").apply { mkdirs() }
        val volume = CacheVolume("1A2B-3C4D", "Seagate", cache, true, drive.totalSpace, drive.usableSpace)
        engine = localEngine(47318, { CacheChoice.Ready(volume, cache) }, cacheDirs = listOf(cache), idleTimeoutMs = 500)
        engine.open(magnet, -1).use { assertArrayEquals(movie, readAll(it, movie.size)) }

        // Unused for a while: let go of, but its data and resume data stay on the drive.
        val until = System.currentTimeMillis() + 20_000
        while ((engine.stats(magnet) != null || !File(cache, "$hash.resume").isFile) && System.currentTimeMillis() < until) {
            Thread.sleep(200)
        }
        assertTrue("resume data saved", File(cache, "$hash.resume").isFile)
        assertTrue("data kept", engine.sizeOf(File(cache, hash)) >= movie.size)

        // The app restarts and nobody seeds any more: the title still plays, from the drive.
        engine.shutdown()
        seeder.stop()
        engine = localEngine(47319, { CacheChoice.Ready(volume, cache) }, cacheDirs = listOf(cache))
        engine.open(magnet, -1).use { assertArrayEquals(movie, readAll(it, movie.size)) }
    }

    @Test
    fun theIndexAtTheEndAndTheMainStreamReadAtTheSameTime() {
        val movie = Random(7).nextBytes(6 * 1024 * 1024 + 321)
        val (_, magnet) = seed(movie, 47320)
        val cache = File(root, "cache").apply { mkdirs() }
        engine = localEngine(47321, { CacheChoice.Ready(CacheVolume("x", "x", cache, false, 0, Long.MAX_VALUE), cache) }, cacheDirs = listOf(cache))
        val server = TorrentHttpServer(engine)
        val url = server.urlFor(magnet, -1)
        var tail: ByteArray? = null
        val index = thread { tail = open(url, "bytes=${movie.size - 200_000}-").inputStream.readBytes() }
        val main = open(url, null).inputStream.readBytes()
        index.join(60_000)
        assertArrayEquals(movie, main)
        assertArrayEquals(movie.copyOfRange(movie.size - 200_000, movie.size), tail)
    }

    private fun open(url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            readTimeout = 60_000
            if (range != null) setRequestProperty("Range", range)
        }
}
