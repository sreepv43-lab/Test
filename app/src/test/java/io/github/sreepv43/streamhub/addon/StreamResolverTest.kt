package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolverTest {

    @Test
    fun directUrlKeepsProxyHeaders() {
        val stream = Stream(
            url = "https://cdn.example/v.mp4",
            behaviorHints = StreamHints(proxyHeaders = ProxyHeaders(request = mapOf("Referer" to "https://x"))),
        )
        assertEquals(
            PlaybackTarget.Direct("https://cdn.example/v.mp4", mapOf("Referer" to "https://x")),
            StreamResolver.resolve(stream, null),
        )
    }

    @Test
    fun torrentUsesStreamingServerWhenConfigured() {
        val stream = Stream(infoHash = "ABCDEF", fileIdx = 2)
        assertEquals(
            PlaybackTarget.Direct("http://192.168.1.5:11470/abcdef/2", emptyMap()),
            StreamResolver.resolve(stream, "http://192.168.1.5:11470/"),
        )
        assertEquals(
            PlaybackTarget.Direct("http://srv:11470/abcdef/-1", emptyMap()),
            StreamResolver.resolve(Stream(infoHash = "abcdef"), "http://srv:11470"),
        )
    }

    @Test
    fun torrentWithoutServerBecomesMagnet() {
        val stream = Stream(
            infoHash = "abcdef",
            sources = listOf("tracker:udp://t.example:80/announce", "dht:abcdef"),
            behaviorHints = StreamHints(filename = "a b.mkv"),
        )
        assertEquals(
            PlaybackTarget.External("magnet:?xt=urn:btih:abcdef&dn=a%20b.mkv&tr=udp%3A%2F%2Ft.example%3A80%2Fannounce"),
            StreamResolver.resolve(stream, null),
        )
    }

    @Test
    fun youtubeAndExternal() {
        assertEquals(
            PlaybackTarget.External("https://www.youtube.com/watch?v=abc"),
            StreamResolver.resolve(Stream(ytId = "abc"), null),
        )
        assertEquals(
            PlaybackTarget.External("https://site.example/x"),
            StreamResolver.resolve(Stream(externalUrl = "https://site.example/x"), null),
        )
        assertNull(StreamResolver.resolve(Stream(), null))
    }
}
