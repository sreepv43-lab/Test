package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.Codec
import io.github.sreepv43.soundhub.slsk.ConnType
import io.github.sreepv43.soundhub.slsk.FakePeer
import io.github.sreepv43.soundhub.slsk.FakeServer
import io.github.sreepv43.soundhub.slsk.MessageReader
import io.github.sreepv43.soundhub.slsk.MessageWriter
import io.github.sreepv43.soundhub.slsk.Messages
import io.github.sreepv43.soundhub.slsk.PeerCode
import io.github.sreepv43.soundhub.slsk.SharedFile
import io.github.sreepv43.soundhub.slsk.SoulseekClient
import io.github.sreepv43.soundhub.slsk.TransferDirection
import io.github.sreepv43.soundhub.slsk.TransferRequest
import io.github.sreepv43.soundhub.slsk.freePort
import io.github.sreepv43.soundhub.slsk.readExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class MusicDownloadsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun downloadedSongsLandInTheLibraryWithTheirRealFormat() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = FakeServer()
        val port = freePort()
        val client = SoulseekClient("127.0.0.1", server.port, port..port)
        try {
            runBlocking { client.connect("me", "pw") }
            val alice = FakePeer("alice")
            server.addresses["alice"] = "127.0.0.1" to alice.port
            val library = LibraryStore(temp.root.resolve("library.json"))
            val downloads = MusicDownloads(client, library, { temp.root.resolve("Music") }, scope)

            // Named like CD quality, but the file is really 24-bit/96 kHz.
            val content = flacHeader(96_000, 24) + Random(3).nextBytes(400_000)
            val remote = "@@alice\\Music\\Artist - Album\\01 - Song.flac"
            val file = SharedFile(remote, content.size.toLong(), "", mapOf(4 to 44_100, 5 to 16))
            val track = SearchTrack("alice", file, AudioFormats.classify(remote, file.size, sampleRate = 44_100, bitDepth = 16), PathNames.describe(remote))
            assertFalse(track.info.hiRes)

            val transfer = downloads.fetch(track)
            alice.accept().use { peer ->
                peer.readInit()
                peer.expect(PeerCode.QUEUE_UPLOAD)
                peer.write(Messages.transferRequest(TransferRequest(TransferDirection.UPLOAD, 5, remote, content.size.toLong())))
                peer.expect(PeerCode.TRANSFER_RESPONSE)
            }
            alice.connectTo(port).use { connection ->
                connection.write(Messages.peerInit("alice", ConnType.FILE, 0))
                connection.write(MessageWriter().u32(5).toByteArray())
                MessageReader(connection.input.readExactly(8)).u64()
                connection.write(content)
                waitUntil { library.tracks.value.isNotEmpty() }
            }

            val song = library.tracks.value.single()
            assertEquals("Song", song.title)
            assertEquals("Artist", song.artist)
            assertEquals(Codec.FLAC, song.info.codec)
            assertEquals(96_000, song.info.sampleRate)
            assertEquals(24, song.info.bitDepth)
            assertTrue(song.info.hiRes)
            assertEquals(temp.root.resolve("Music/Artist - Album/01 - Song.flac").path, song.path)
            assertEquals(96_000, downloads.infos.value[transfer.id]?.sampleRate)
        } finally {
            client.close()
            server.close()
            scope.cancel()
        }
    }

    private fun flacHeader(rate: Int, bits: Int): ByteArray {
        val info = ByteArray(34)
        info[10] = (rate ushr 12).toByte()
        info[11] = (rate ushr 4).toByte()
        info[12] = (((rate and 0x0F) shl 4) or (1 shl 1) or ((bits - 1) ushr 4)).toByte()
        info[13] = (((bits - 1) and 0x0F) shl 4).toByte()
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out")
            Thread.sleep(20)
        }
    }
}
