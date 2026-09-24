package io.github.sreepv43.streamhub.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentLinksTest {

    @Test
    fun readsHexAndBase32InfoHashes() {
        assertEquals(
            "87d433e732388eecd42039105f31e2139322b8d4",
            TorrentLinks.infoHashFromMagnet("magnet:?xt=urn:btih:87D433E732388EECD42039105F31E2139322B8D4&dn=x"),
        )
        // Base32 of the same 20 bytes.
        assertEquals(
            "87d433e732388eecd42039105f31e2139322b8d4",
            TorrentLinks.infoHashFromMagnet("magnet:?dn=x&xt=urn:btih:Q7KDHZZSHCHOZVBAHEIF6MPCCOJSFOGU"),
        )
        assertNull(TorrentLinks.infoHashFromMagnet("magnet:?dn=nothing"))
    }

    @Test
    fun recognisesSources() {
        assertTrue(TorrentLinks.isTorrentSource("magnet:?xt=urn:btih:abc"))
        assertTrue(TorrentLinks.isTorrentSource("https://a.example/b.torrent?x=1"))
        assertFalse(TorrentLinks.isTorrentSource("https://a.example/b.mkv"))
        assertEquals(
            "magnet:?xt=urn:btih:87d433e732388eecd42039105f31e2139322b8d4",
            TorrentLinks.normalizeSource(" 87d433e732388eecd42039105f31e2139322b8d4 "),
        )
    }

    @Test
    fun logicalUrlsRoundTrip() {
        val magnet = "magnet:?xt=urn:btih:abc&dn=A+B&tr=udp%3A%2F%2Ft%3A1"
        val url = TorrentLinks.logicalUrl(magnet, 4)
        assertTrue(TorrentLinks.isLogicalUrl(url))
        assertEquals(magnet to 4, TorrentLinks.parseLogicalUrl(url))
        assertEquals(magnet to -1, TorrentLinks.parseQuery(TorrentLinks.streamPath(magnet, -1).substringAfter('?')))
        assertNull(TorrentLinks.parseLogicalUrl("https://x"))
    }

    @Test
    fun displayNames() {
        assertEquals("My Movie", TorrentLinks.displayName("magnet:?xt=urn:btih:abc&dn=My%20Movie"))
        assertEquals("pack", TorrentLinks.displayName("https://a/b/pack.torrent"))
    }

    @Test
    fun parsesRanges() {
        assertNull(ByteRange.parse(null, 100))
        assertEquals(ByteRange(10, 99), ByteRange.parse("bytes=10-", 100))
        assertEquals(ByteRange(10, 20), ByteRange.parse("bytes=10-20", 100))
        assertEquals(ByteRange(10, 99), ByteRange.parse("bytes=10-500", 100))
        assertEquals(ByteRange(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(11L, ByteRange(10, 20).length)
    }
}
