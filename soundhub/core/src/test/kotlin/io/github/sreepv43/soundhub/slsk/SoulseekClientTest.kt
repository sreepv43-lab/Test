package io.github.sreepv43.soundhub.slsk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

class SoulseekClientTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var client: SoulseekClient
    private val listenPort = freePort()

    @Before
    fun setUp() {
        server = FakeServer()
        client = SoulseekClient(
            serverHost = "127.0.0.1",
            serverPort = server.port,
            listenPorts = listenPort..listenPort,
            timeouts = SoulseekClient.Timeouts(connectMs = 2_000, indirectMs = 5_000, retryDelayMs = 100),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun signIn() = runBlocking {
        client.connect("me", "secret")
        assertEquals(ConnectionState.Connected("me", listenPort), client.state.value)
    }

    @Test
    fun signsInAndAnnouncesListeningPort() {
        signIn()
        waitUntil { server.clientListenPort == listenPort }
        waitUntil { ServerCode.SHARED_FOLDERS_FILES in server.received }
    }

    @Test
    fun reportsRejectedPassword() = runBlocking {
        server.loginReply = { MessageWriter().bool(false).str("INVALIDPASS").toByteArray() }
        client.connect("me", "wrong")
        assertEquals(ConnectionState.Failed("Wrong password for this username"), client.state.value)
    }

    @Test
    fun collectsSearchResultsSentByPeers() = runBlocking {
        signIn()
        val alice = FakePeer("alice")
        server.onSearch = { token, query ->
            thread {
                alice.connectTo(listenPort).use { peer ->
                    peer.write(Messages.peerInit("alice", ConnType.PEER, 0))
                    val files = listOf(
                        SharedFile("@@alice\\Music\\Artist\\Album\\01 - $query.flac", 30_000_000, "", mapOf(1 to 240, 4 to 96_000, 5 to 24)),
                    )
                    peer.write(Messages.searchResponse(SearchResponse("alice", token, files, true, 1_000_000, 0)))
                    Thread.sleep(500)
                }
            }
        }
        val search = client.search("song")
        val responses = withTimeout(5_000) { search.responses.first { it.isNotEmpty() } }
        val file = responses.single().files.single()
        assertEquals("@@alice\\Music\\Artist\\Album\\01 - song.flac", file.filename)
        assertEquals(96_000, file.sampleRate)
        assertEquals(24, file.bitDepth)
        assertTrue(responses.single().slotFree)
    }

    @Test
    fun downloadsDirectlyWhileAReaderStreamsTheFile() {
        signIn()
        val alice = FakePeer("alice")
        server.addresses["alice"] = "127.0.0.1" to alice.port
        val content = Random(1).nextBytes(300_000)
        val filename = "@@alice\\Music\\Album\\01.flac"
        val target = temp.root.resolve("01.flac")

        val transfer = client.download("alice", filename, content.size.toLong(), target)
        // Someone plays the file while it downloads.
        val streamed = CompletableFuture.supplyAsync { readWhileGrowing(transfer.data) }

        alice.accept().use { peer ->
            val (code, init) = peer.readInit()
            assertEquals(InitCode.PEER_INIT, code)
            assertEquals("me", init.str())
            assertEquals(ConnType.PEER, init.str())
            assertEquals(filename, peer.expect(PeerCode.QUEUE_UPLOAD).str())
            peer.write(Messages.transferRequest(TransferRequest(TransferDirection.UPLOAD, 77, filename, content.size.toLong())))
            val response = peer.expect(PeerCode.TRANSFER_RESPONSE)
            assertEquals(77, response.u32())
            assertTrue(response.bool())
        }
        alice.connectTo(listenPort).use { file ->
            file.write(Messages.peerInit("alice", ConnType.FILE, 0))
            file.write(MessageWriter().u32(77).toByteArray())
            val offset = MessageReader(file.input.readExactly(8)).u64()
            assertEquals(0L, offset)
            // Slowly, in pieces.
            content.toList().chunked(50_000).forEach {
                file.write(it.toByteArray())
                Thread.sleep(20)
            }
            waitUntil { client.transfer(transfer.id)?.status == TransferStatus.COMPLETED }
        }
        assertArrayEquals(content, target.readBytes())
        assertArrayEquals(content, streamed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun connectsThroughTheServerAndResumesPartialFiles() {
        signIn()
        val bob = FakePeer("bob")
        // Bob's advertised port is closed, so the client must ask the server to have Bob connect.
        server.addresses["bob"] = "127.0.0.1" to freePort()
        val relayed = CompletableFuture<Int>()
        server.onConnectToPeer = { token, username, type ->
            assertEquals("bob", username)
            assertEquals(ConnType.PEER, type)
            relayed.complete(token)
        }
        val content = Random(2).nextBytes(100_000)
        val filename = "@@bob\\Album\\02.flac"
        val target = temp.root.resolve("02.flac").apply { writeBytes(content.copyOfRange(0, 40_000)) }

        val transfer = client.download("bob", filename, content.size.toLong(), target)
        val token = relayed.get(10, TimeUnit.SECONDS)
        bob.connectTo(listenPort).use { peer ->
            peer.write(Messages.pierceFirewall(token))
            assertEquals(filename, peer.expect(PeerCode.QUEUE_UPLOAD).str())
            peer.write(Messages.transferRequest(TransferRequest(TransferDirection.UPLOAD, 9, filename, content.size.toLong())))
            peer.expect(PeerCode.TRANSFER_RESPONSE)
        }
        // Bob can't reach us for the file either: the server relays his request and we connect to him.
        server.relayConnectToPeer("bob", ConnType.FILE, bob.port, token = 555)
        bob.accept().use { file ->
            val (code, init) = file.readInit()
            assertEquals(InitCode.PIERCE_FIREWALL, code)
            assertEquals(555, init.u32())
            file.write(MessageWriter().u32(9).toByteArray())
            val offset = MessageReader(file.input.readExactly(8)).u64()
            assertEquals(40_000L, offset)
            file.write(content.copyOfRange(offset.toInt(), content.size))
            waitUntil { client.transfer(transfer.id)?.status == TransferStatus.COMPLETED }
        }
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun reportsDeniedUploads() {
        signIn()
        val carol = FakePeer("carol")
        server.addresses["carol"] = "127.0.0.1" to carol.port
        val filename = "@@carol\\x.mp3"
        val transfer = client.download("carol", filename, 1_000, temp.root.resolve("x.mp3"))
        carol.accept().use { peer ->
            peer.readInit()
            peer.expect(PeerCode.QUEUE_UPLOAD)
            peer.write(message(PeerCode.PLACE_IN_QUEUE_RESPONSE, MessageWriter().str(filename).u32(12).toByteArray()))
            waitUntil { client.transfer(transfer.id)?.queuePosition == 12 }
            peer.write(Messages.uploadDenied(filename, "File not shared."))
            waitUntil { client.transfer(transfer.id)?.status == TransferStatus.FAILED }
        }
        assertEquals("The user no longer shares this file", client.transfer(transfer.id)?.error)
        try {
            transfer.data.awaitAvailable(0)
            throw AssertionError("A reader should see the failure")
        } catch (e: java.io.IOException) {
            assertEquals("The user no longer shares this file", e.message)
        }
    }

    @Test
    fun declinesRequestsForOurFiles() {
        signIn()
        val dave = FakePeer("dave")
        dave.connectTo(listenPort).use { peer ->
            peer.write(Messages.peerInit("dave", ConnType.PEER, 0))
            peer.write(Messages.queueUpload("@@me\\song.flac"))
            val denied = peer.expect(PeerCode.UPLOAD_DENIED)
            assertEquals("@@me\\song.flac", denied.str())
            assertEquals("File not shared.", denied.str())
        }
    }

    private fun readWhileGrowing(file: GrowingFile): ByteArray {
        val out = ByteArrayOutputStream()
        java.io.RandomAccessFile(file.file, "r").use { raf ->
            var position = 0L
            val buffer = ByteArray(7_000)
            while (true) {
                val available = file.awaitAvailable(position)
                if (available <= position) break
                raf.seek(position)
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), available - position).toInt())
                out.write(buffer, 0, n)
                position += n
            }
        }
        return out.toByteArray()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out")
            Thread.sleep(20)
        }
    }
}
