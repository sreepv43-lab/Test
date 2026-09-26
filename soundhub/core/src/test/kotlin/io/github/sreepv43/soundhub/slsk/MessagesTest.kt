package io.github.sreepv43.soundhub.slsk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test
    fun ipAddressesAreSentMostSignificantByteFirstInALittleEndianInt() {
        val bytes = MessageWriter().u32(Messages.ipValue("192.168.1.20")).toByteArray()
        assertEquals(listOf(20, 1, 168, 192), bytes.map { it.toInt() and 0xFF })
        assertEquals("192.168.1.20", Messages.ipString(MessageReader(bytes).u32()))
    }

    @Test
    fun loginCarriesVersionAndHash() {
        val reader = MessageReader(Messages.login("user", "pass"))
        reader.u32() // length
        assertEquals(ServerCode.LOGIN, reader.u32())
        assertEquals("user", reader.str())
        assertEquals("pass", reader.str())
        assertEquals(Messages.PROTOCOL_VERSION, reader.u32())
        // md5("userpass")
        assertEquals("63e780c3f321d13109c71bf81805476e", reader.str())
        assertEquals(Messages.MINOR_VERSION, reader.u32())
    }

    @Test
    fun searchResponsesRoundTrip() {
        val response = SearchResponse(
            username = "ünïcode user",
            token = 1234,
            files = listOf(
                SharedFile("@@x\\A\\01.flac", 5_000_000_000L, "flac", mapOf(0 to 1411, 1 to 300, 4 to 44_100, 5 to 16)),
                SharedFile("@@x\\A\\02.mp3", 8_000_000, "", mapOf(0 to 320, 2 to 1)),
            ),
            slotFree = false,
            avgSpeed = 250_000,
            queueLength = 3,
            privateFiles = listOf(SharedFile("@@x\\private.flac", 1, "")),
        )
        val reader = MessageReader(Messages.searchResponse(response))
        reader.u32()
        assertEquals(PeerCode.FILE_SEARCH_RESPONSE, reader.u32())
        val parsed = Messages.parseSearchResponse(reader.rest())
        assertEquals(response, parsed)
        assertEquals(44_100, parsed.files[0].sampleRate)
        assertTrue(parsed.files[1].vbr)
    }

    @Test
    fun oldClientsWithoutPrivateResultsStillParse() {
        val payload = MessageWriter().str("old").u32(7).u32(0).bool(true).u32(100).u64(5).toByteArray()
        val parsed = Messages.parseSearchResponse(zlibCompress(payload))
        assertEquals(SearchResponse("old", 7, emptyList(), true, 100, 5), parsed)
    }

    @Test
    fun latin1TextFromOldClientsIsReadable() {
        val payload = MessageWriter().bytes("Café".toByteArray(Charsets.ISO_8859_1)).toByteArray()
        assertEquals("Café", MessageReader(payload).str())
    }
}
