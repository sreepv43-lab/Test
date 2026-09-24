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
            StreamResolver.resolve(stream),
        )
    }

    @Test
    fun infoHashBecomesTorrentWithTrackers() {
        val stream = Stream(
            infoHash = "ABCDEF",
            fileIdx = 2,
            sources = listOf("tracker:udp://t.example:80/announce", "dht:abcdef"),
            behaviorHints = StreamHints(filename = "a b.mkv"),
        )
        assertEquals(
            PlaybackTarget.Torrent("magnet:?xt=urn:btih:abcdef&dn=a%20b.mkv&tr=udp%3A%2F%2Ft.example%3A80%2Fannounce", 2),
            StreamResolver.resolve(stream),
        )
        assertEquals(PlaybackTarget.Torrent("magnet:?xt=urn:btih:abcdef", -1), StreamResolver.resolve(Stream(infoHash = "abcdef")))
    }

    @Test
    fun magnetAndTorrentFileUrlsAreTorrents() {
        assertEquals(
            PlaybackTarget.Torrent("magnet:?xt=urn:btih:abc", -1),
            StreamResolver.resolve(Stream(url = "magnet:?xt=urn:btih:abc")),
        )
        assertEquals(
            PlaybackTarget.Torrent("https://site.example/files/x.torrent?k=1", 3),
            StreamResolver.resolve(Stream(url = "https://site.example/files/x.torrent?k=1", fileIdx = 3)),
        )
    }

    @Test
    fun youtubeAndExternal() {
        assertEquals(
            PlaybackTarget.External("https://www.youtube.com/watch?v=abc"),
            StreamResolver.resolve(Stream(ytId = "abc")),
        )
        assertEquals(
            PlaybackTarget.External("https://site.example/x"),
            StreamResolver.resolve(Stream(externalUrl = "https://site.example/x")),
        )
        assertNull(StreamResolver.resolve(Stream()))
    }
}
