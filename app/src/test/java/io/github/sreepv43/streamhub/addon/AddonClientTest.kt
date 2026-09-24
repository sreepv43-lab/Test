package io.github.sreepv43.streamhub.addon

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonClientTest {
    private val server = MockWebServer()
    private val client = AddonClient(OkHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun installsAndQueriesAnAddon() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"t","name":"Test","types":["movie"],"resources":["stream"],"catalogs":[{"type":"movie","id":"c"}]}"""))
        server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1","type":"movie","name":"One"}]}"""))
        server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://v/1.mp4","name":"HD"}]}"""))

        val addon = client.fetchManifest(server.url("/cfg/manifest.json").toString())
        assertEquals("Test", addon.manifest.name)
        assertEquals("/cfg/manifest.json", server.takeRequest().path)

        val metas = client.catalog(addon, "movie", "c", listOf("search" to "one two"))
        assertEquals("One", metas.single().name)
        assertEquals("/cfg/catalog/movie/c/search=one%20two.json", server.takeRequest().path)

        val streams = client.streams(addon, "movie", "tt1")
        assertEquals("HD", streams.single().name)
        assertEquals("/cfg/stream/movie/tt1.json", server.takeRequest().path)
    }

    @Test
    fun httpErrorsBecomeAddonExceptions() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val addon = InstalledAddon(server.url("/manifest.json").toString(), Manifest(id = "x"))
        val error = runCatching { client.streams(addon, "movie", "tt1") }.exceptionOrNull()
        assertTrue(error is AddonException)
    }
}
