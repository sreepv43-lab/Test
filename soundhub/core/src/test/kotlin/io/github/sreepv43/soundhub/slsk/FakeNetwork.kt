package io.github.sreepv43.soundhub.slsk

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A stand-in for server.slsknet.org: accepts logins, hands out peer addresses and relays requests. */
internal class FakeServer : AutoCloseable {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort

    /** username -> (ip, port) returned for GetPeerAddress. */
    val addresses = ConcurrentHashMap<String, Pair<String, Int>>()
    val received = CopyOnWriteArrayList<Int>()
    var loginReply: (String) -> ByteArray = { user ->
        MessageWriter().bool(true).str("Welcome $user").u32(Messages.ipValue("127.0.0.1")).str("hash").bool(false).toByteArray()
    }
    var onSearch: (token: Int, query: String) -> Unit = { _, _ -> }
    var onConnectToPeer: (token: Int, username: String, type: String) -> Unit = { _, _, _ -> }

    @Volatile var clientListenPort: Int = 0
        private set

    @Volatile private var client: Socket? = null

    init {
        thread(isDaemon = true, name = "fake-server") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                client = connection
                thread(isDaemon = true) { serve(connection) }
            }
        }
    }

    fun send(code: Int, payload: ByteArray) {
        client!!.getOutputStream().write(message(code, payload))
    }

    private fun serve(connection: Socket) {
        val input = BufferedInputStream(connection.getInputStream())
        runCatching {
            while (true) {
                val frame = input.readFrame(1 shl 20)
                val reader = MessageReader(frame)
                val code = reader.u32()
                received += code
                when (code) {
                    ServerCode.LOGIN -> send(ServerCode.LOGIN, loginReply(reader.str()))
                    ServerCode.SET_WAIT_PORT -> clientListenPort = reader.u32()
                    ServerCode.FILE_SEARCH -> onSearch(reader.u32(), reader.str())
                    ServerCode.GET_PEER_ADDRESS -> {
                        val user = reader.str()
                        val (ip, port) = addresses[user] ?: ("0.0.0.0" to 0)
                        send(
                            ServerCode.GET_PEER_ADDRESS,
                            MessageWriter().str(user).u32(Messages.ipValue(ip)).u32(port).u32(0).toByteArray(),
                        )
                    }
                    ServerCode.CONNECT_TO_PEER -> onConnectToPeer(reader.u32(), reader.str(), reader.str())
                }
            }
        }
    }

    /** Relays a peer's "please connect to me" (ConnectToPeer) to the client. */
    fun relayConnectToPeer(username: String, type: String, port: Int, token: Int) {
        send(
            ServerCode.CONNECT_TO_PEER,
            MessageWriter().str(username).str(type).u32(Messages.ipValue("127.0.0.1")).u32(port).u32(token).bool(false)
                .toByteArray(),
        )
    }

    override fun close() {
        socket.close()
        client?.close()
    }
}

/** Another Soulseek user, driven step by step by a test. */
internal class FakePeer(val username: String) : AutoCloseable {
    val listener = ServerSocket(0)
    val port: Int get() = listener.localPort

    fun accept(): PeerSocket {
        listener.soTimeout = 10_000
        return PeerSocket(listener.accept())
    }

    fun connectTo(port: Int): PeerSocket =
        PeerSocket(Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 5_000) })

    override fun close() = listener.close()
}

internal class PeerSocket(val socket: Socket) : AutoCloseable {
    val input: InputStream = BufferedInputStream(socket.getInputStream())

    init {
        socket.soTimeout = 10_000
    }

    fun write(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** Reads the init message: code and a reader over the rest. */
    fun readInit(): Pair<Int, MessageReader> {
        val reader = MessageReader(input.readFrame(4096))
        return reader.u8() to reader
    }

    fun readMessage(): Pair<Int, MessageReader> {
        val reader = MessageReader(input.readFrame(1 shl 20))
        return reader.u32() to reader
    }

    /** Reads messages until one with [code] arrives. */
    fun expect(code: Int): MessageReader {
        while (true) {
            val (received, reader) = readMessage()
            if (received == code) return reader
        }
    }

    override fun close() = socket.close()
}

internal fun freePort(): Int = ServerSocket(0).use { it.localPort }
