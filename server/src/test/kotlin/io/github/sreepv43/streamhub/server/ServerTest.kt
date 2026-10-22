package io.github.sreepv43.streamhub.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random

/** The server as the app uses it: pairing, the API, a real torrent download and streaming the result. */
class ServerTest {
    private lateinit var root: File
    private lateinit var storage: File
    private var server: StreamHubServer? = null
    private var seeder: SessionManager? = null

    @Before
    fun setUp() {
        root = Files.createTempDirectory("streamhub-server").toFile()
        storage = File(root, "movies").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server?.stop()
        seeder?.stop()
        root.deleteRecursively()
    }

    private fun startServer(torrentPort: Int): StreamHubServer {
        val s = StreamHubServer(
            File(root, "data"),
            portOverride = 0,
            configureTorrents = {
                it.listenInterfaces("127.0.0.1:$torrentPort")
                it.setEnableDht(false)
                it.setEnableLsd(false)
                it.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
            },
            minVideoBytes = 1,
            log = {},
        )
        s.addStorage("Test drive", storage.path)
        server = s.start()
        return s
    }

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        range: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, ByteArray> {
        val connection = URI("http://127.0.0.1:${server!!.port}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        range?.let { connection.setRequestProperty("Range", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val bytes = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    private fun pair(s: StreamHubServer): String {
        val (code, body) = request("POST", "/api/pair", body = """{"code":"${s.pairingCode}","device":"Test TV"}""")
        assertEquals(200, code)
        return ServerJson.decodeFromString(PairResponse.serializer(), body.decodeToString()).token
    }

    @Test
    fun onlyPairedDevicesGetIn() {
        val s = startServer(47411)
        assertEquals(200, request("GET", "/api/hello").first)
        assertEquals(401, request("GET", "/api/status").first)
        assertEquals(403, request("POST", "/api/pair", body = """{"code":"000000","device":"Intruder"}""").first)
        val firstCode = s.pairingCode
        val token = pair(s)
        assertTrue("a code works only once", s.pairingCode != firstCode)
        val (code, body) = request("GET", "/api/status", token)
        assertEquals(200, code)
        val status = ServerJson.decodeFromString(ServerStatus.serializer(), body.decodeToString())
        assertTrue(status.storages.single().available)
        assertEquals(401, request("GET", "/api/status", "not-a-token").first)
    }

    @Test
    fun filesInTheStorageAreFoundAndStreamedWithRanges() {
        val s = startServer(47412)
        val token = pair(s)
        val movie = Random(1).nextBytes(300_000)
        File(storage, "Manjummel.Boys.2024.1080p.WEB-DL.mkv").writeBytes(movie)
        s.library.scan()
        val items = ServerJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LibraryItem.serializer()),
            request("GET", "/api/library", token).second.decodeToString(),
        )
        val item = items.single()
        assertEquals("Manjummel Boys", item.parsedTitle)
        assertEquals(2024, item.year)
        assertTrue(item.available)

        val (code, part) = request("GET", "/media/${item.id}", token, range = "bytes=1000-1999")
        assertEquals(206, code)
        assertArrayEquals(movie.copyOfRange(1000, 2000), part)
        val (whole, all) = request("GET", "/media/${item.id}?token=$token")
        assertEquals(200, whole)
        assertArrayEquals(movie, all)
        assertEquals(401, request("GET", "/media/${item.id}").first)
    }

    @Test
    fun aTorrentDownloadsWithRealNumbersAndShowsUpInTheLibrary() {
        val movie = Random(2).nextBytes(3 * 1024 * 1024)
        val magnet = seed(movie, 47413)
        val s = startServer(47414)
        val token = pair(s)
        val (code, body) = request("POST", "/api/downloads", token, """{"source":"$magnet","fileIdx":0,"title":"Test Movie","metaId":"tt123","type":"movie","videoId":"tt123"}""")
        assertEquals(201, code)
        val id = ServerJson.decodeFromString(Job.serializer(), body.decodeToString()).id

        val until = System.currentTimeMillis() + 90_000
        var job: Job
        val seen = mutableSetOf<String>()
        do {
            Thread.sleep(250)
            job = s.downloads.get(id)!!
            seen += job.state
        } while (job.state != JobState.COMPLETED && job.state != JobState.ERROR && System.currentTimeMillis() < until)
        assertEquals("states seen: $seen, error: ${job.error}", JobState.COMPLETED, job.state)
        assertEquals(movie.size.toLong(), job.totalBytes)
        assertArrayEquals(movie, File(job.filePath!!).readBytes())

        val item = s.library.items().single { it.id == id }
        assertEquals("tt123", item.metaId)
        assertTrue(item.available)
        val (_, streamed) = request("GET", "/media/$id", token, range = "bytes=0-99")
        assertArrayEquals(movie.copyOfRange(0, 100), streamed)

        // Removing with its file deletes the file too.
        assertEquals(200, request("DELETE", "/api/downloads/$id?deleteFile=true", token).first)
        Thread.sleep(1_000)
        assertTrue(!File(job.filePath!!).exists())
    }

    @Test
    fun downloadsContinueAfterTheServerRestarts() {
        val movie = Random(4).nextBytes(4 * 1024 * 1024)
        val magnet = seed(movie, 47416)
        val first = startServer(47417)
        val token = pair(first)
        val (_, body) = request("POST", "/api/downloads", token, """{"source":"$magnet","fileIdx":0,"title":"Restarted"}""")
        val id = ServerJson.decodeFromString(Job.serializer(), body.decodeToString()).id
        // Stop once the torrent's details are known (the download may or may not be finished by then).
        val until = System.currentTimeMillis() + 60_000
        while (first.downloads.get(id)!!.filePath == null && System.currentTimeMillis() < until) Thread.sleep(100)
        first.stop()

        val second = StreamHubServer(
            File(root, "data"),
            portOverride = 0,
            configureTorrents = {
                it.listenInterfaces("127.0.0.1:47418")
                it.setEnableDht(false)
                it.setEnableLsd(false)
                it.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
            },
            minVideoBytes = 1,
            log = {},
        ).start()
        server = second
        assertEquals("the pairing is kept", 200, request("GET", "/api/status", token).first)
        val done = System.currentTimeMillis() + 90_000
        while (second.downloads.get(id)?.state != JobState.COMPLETED && System.currentTimeMillis() < done) Thread.sleep(250)
        val job = second.downloads.get(id)!!
        assertEquals(job.error.toString(), JobState.COMPLETED, job.state)
        assertArrayEquals(movie, File(job.filePath!!).readBytes())
    }

    @Test
    fun aMissingDriveStoresNothing() {
        val s = startServer(47415)
        val token = pair(s)
        storage.deleteRecursively()
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=x"
        val (_, body) = request("POST", "/api/downloads", token, """{"source":"$magnet","title":"Nowhere"}""")
        val id = ServerJson.decodeFromString(Job.serializer(), body.decodeToString()).id
        Thread.sleep(1_500)
        assertEquals(JobState.STORAGE_UNAVAILABLE, s.downloads.get(id)!!.state)
        assertTrue(!storage.exists())
    }

    @Test
    fun theControlPageWorksOnTheServerComputer() {
        val s = startServer(47419)
        val (code, page) = request("GET", "/")
        assertEquals(200, code)
        assertTrue(page.decodeToString().contains("StreamHub"))
        val admin = mapOf("X-StreamHub" to "admin")
        // Other websites in the browser can't send the page's header.
        assertEquals(403, request("GET", "/admin/api/status").first)
        val (_, statusBody) = request("GET", "/admin/api/status", headers = admin)
        val status = ServerJson.decodeFromString(AdminStatus.serializer(), statusBody.decodeToString())
        assertEquals(s.pairingCode, status.pairingCode)

        val drive = File(root, "drive").apply { mkdirs() }
        val (added, _) = request("POST", "/admin/api/storages/add", body = """{"path":"${drive.path}","label":"Elements"}""", headers = admin)
        assertEquals(200, added)
        val elements = s.config().storages.single { it.label == "Elements" }
        assertTrue(File(drive, Storages.MARKER).isFile)
        assertEquals(200, request("POST", "/admin/api/storages/default", body = """{"id":"${elements.id}"}""", headers = admin).first)
        assertEquals(elements.id, s.status().defaultStorage)
        val folders = ServerJson.decodeFromString(
            FolderListing.serializer(),
            request("GET", "/admin/api/folders?path=" + java.net.URLEncoder.encode(root.path, "UTF-8"), headers = admin).second.decodeToString(),
        )
        assertTrue(folders.folders.any { it.name == "drive" })

        val token = pair(s)
        val device = s.config().devices.single()
        assertEquals(200, request("POST", "/admin/api/devices/remove", body = """{"pairedAt":${device.pairedAt}}""", headers = admin).first)
        assertEquals("an unpaired TV is shut out", 401, request("GET", "/api/status", token).first)
    }

    private fun seed(movie: ByteArray, port: Int): String {
        val pack = File(root, "seed/pack").apply { mkdirs() }
        File(pack, "movie.mkv").writeBytes(movie)
        val info = TorrentInfo(TorrentBuilder().path(pack).pieceSize(256 * 1024).generate().entry().bencode())
        val session = SessionManager(false)
        val settings = SettingsPack().listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        settings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
        session.start(SessionParams(settings))
        session.download(info, File(root, "seed"))
        seeder = session
        return "magnet:?xt=urn:btih:${info.infoHash().toHex()}&dn=pack&x.pe=127.0.0.1:$port"
    }
}
