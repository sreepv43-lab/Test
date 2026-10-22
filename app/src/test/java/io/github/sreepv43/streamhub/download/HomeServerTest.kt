package io.github.sreepv43.streamhub.download

import android.app.Application
import io.github.sreepv43.streamhub.data.Settings
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The app's side of the Home Server: pairing, sending a download, and showing the server's real numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeServerTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"

    private fun job(id: String, state: String, title: String = "Dune") =
        """{"id":"$id","source":"$magnet","fileIdx":2,"title":"$title","metaId":"tt1160419","type":"movie","videoId":"tt1160419",
            "storageId":"s1","fileName":"Dune.mkv","totalBytes":1000,"downloadedBytes":620,"downloadRate":2048,"peers":12,"seeds":5,
            "state":"$state","storageLabel":"Elements","createdAt":1}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = when (request.method + " " + request.path?.substringBefore('?')) {
                    "GET /api/hello" -> """{"app":"streamhub-server","name":"Den PC","version":"1.0.1"}"""
                    "POST /api/pair" -> return if (request.body.readUtf8().contains("\"123456\"")) {
                        MockResponse().setBody("""{"token":"abc","name":"Den PC"}""")
                    } else {
                        MockResponse().setResponseCode(403).setBody("""{"error":"That pairing code isn't right."}""")
                    }
                    "POST /api/downloads" -> job("j1", "metadata")
                    "GET /api/downloads" -> "[" + job("j1", "downloading") + "," + job("j2", "completed", "Dune: Part Two") + "]"
                    "GET /api/library" -> "[]"
                    "GET /api/status" -> """{"name":"Den PC","storages":[{"id":"s1","label":"Elements","available":true,"freeBytes":5,"totalBytes":9}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun pairsSendsADownloadAndShowsTheServersNumbers() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(context)
        val repository = DownloadRepository(context)
        val homeServer = HomeServer(OkHttpClient(), settings, repository)
        val address = "${server.hostName}:${server.port}"

        val wrong = runCatching { runBlocking { homeServer.pair(address, "000000", "TV") } }
        assertEquals("That pairing code isn't right.", wrong.exceptionOrNull()?.message)
        assertEquals("Den PC", runBlocking { homeServer.pair(address, "123456", "TV") })
        assertEquals("abc", settings.homeServerToken.value)

        val item = DownloadItem(
            id = "local-1",
            title = "Dune",
            metaId = "tt1160419",
            type = "movie",
            videoId = "tt1160419",
            url = TorrentLinks.logicalUrl(magnet, 2),
            location = DownloadLocation(DownloadLocation.Kind.SERVER, "s1", "Den PC · Elements"),
        )
        repository.add(item)
        homeServer.send(item)
        waitFor { repository.get("local-1")?.remoteId == "j1" }
        val sent = requests.first { it.method == "POST" && it.path == "/api/downloads" }
        assertEquals("Bearer abc", sent.getHeader("Authorization"))
        val sentBody = sent.body.readUtf8()
        assertTrue(sentBody, sentBody.contains("\"fileIdx\":2") && sentBody.contains("\"storageId\":\"s1\"") && sentBody.contains("btih"))

        homeServer.start()
        waitFor { repository.get("local-1")?.status == DownloadItem.Status.RUNNING && repository.get("local-1")?.detail == null }
        val running = repository.get("local-1")!!
        assertEquals(620L, running.downloadedBytes)
        assertEquals(2048L, running.downloadRate)
        assertEquals(12, running.peers)
        // A download started on the server by another device shows up too.
        waitFor { repository.get("server-j2") != null }
        assertEquals(DownloadItem.Status.COMPLETED, repository.get("server-j2")!!.status)
        assertEquals(DownloadLocation.Kind.SERVER, repository.get("server-j2")!!.location.kind)
        assertEquals("http://$address/media/j2", homeServer.mediaUrl("j2"))
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("condition not met in time", condition())
    }
}
