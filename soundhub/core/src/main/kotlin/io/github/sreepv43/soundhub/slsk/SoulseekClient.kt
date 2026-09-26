package io.github.sreepv43.soundhub.slsk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val username: String, val listenPort: Int?) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** A running search; answers from other users keep arriving in [responses] for a while. */
class Search internal constructor(val token: Int, val query: String) {
    private val _responses = MutableStateFlow<List<SearchResponse>>(emptyList())
    val responses: StateFlow<List<SearchResponse>> = _responses.asStateFlow()

    @Volatile private var fileCount = 0

    internal fun add(response: SearchResponse) {
        if (fileCount >= MAX_FILES) return
        fileCount += response.files.size
        _responses.update { it + response }
    }

    private companion object {
        const val MAX_FILES = 20_000
    }
}

/**
 * A Soulseek client: signs in to the server, searches, and downloads files from other users
 * (directly, or through the server's "connect to me" relay when a user can't be reached).
 * Sharing is not implemented yet: the client tells the server it shares nothing and politely
 * declines upload requests.
 */
class SoulseekClient(
    private val serverHost: String = DEFAULT_SERVER_HOST,
    private val serverPort: Int = DEFAULT_SERVER_PORT,
    private val listenPorts: IntRange = DEFAULT_LISTEN_PORTS,
    private val description: String = "SoundHub",
    private val timeouts: Timeouts = Timeouts(),
    private val log: (String) -> Unit = {},
) {
    data class Timeouts(
        val connectMs: Int = 8_000,
        val serverReplyMs: Long = 10_000,
        val indirectMs: Long = 20_000,
        val initReadMs: Int = 30_000,
        val idleMs: Int = 120_000,
        val fileReadMs: Int = 60_000,
        val retryDelayMs: Long = 5_000,
        val queueRefreshMs: Long = 60_000,
    )

    // Every peer connection blocks one thread while reading; searches can bring hundreds at once.
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "slsk-io").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferInfo>>(emptyList())
    val transfers: StateFlow<List<TransferInfo>> = _transfers.asStateFlow()

    @Volatile private var server: Socket? = null
    @Volatile private var username: String? = null
    @Volatile private var credentials: Pair<String, String>? = null
    @Volatile private var listener: ServerSocket? = null
    private val serverWriteLock = Any()
    @Volatile private var sessionJobs = emptyList<Job>()

    val listenPort: Int? get() = listener?.localPort

    private val tokens = AtomicInteger(Random.nextInt(1, Int.MAX_VALUE / 2))
    private val transferIds = AtomicLong(1)
    private val searches = ConcurrentHashMap<Int, Search>()
    private val addressWaiters = ConcurrentHashMap<String, CompletableDeferred<PeerAddress>>()
    private val pierceWaiters = ConcurrentHashMap<Int, CompletableDeferred<Link>>()
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val peerLocks = ConcurrentHashMap<String, Mutex>()
    private val transfersById = ConcurrentHashMap<Long, Transfer>()

    /** Signs in (a new username is registered with this password on first use). Never throws. */
    suspend fun connect(username: String, password: String): Unit = withContext(dispatcher) {
        closeSession()
        credentials = username to password
        _state.value = ConnectionState.Connecting
        try {
            ensureListener()
            val socket = Socket()
            socket.connect(InetSocketAddress(serverHost, serverPort), timeouts.connectMs)
            socket.keepAlive = true
            socket.soTimeout = timeouts.serverReplyMs.toInt() * 2
            val input = BufferedInputStream(socket.getInputStream())
            socket.getOutputStream().write(Messages.login(username, password))
            var reply: LoginReply? = null
            while (reply == null) {
                val frame = input.readFrame(MAX_SERVER_FRAME)
                if (MessageReader(frame).u32() == ServerCode.LOGIN) reply = Messages.parseLogin(frame.payload())
            }
            when (reply) {
                is LoginReply.Failure -> {
                    socket.close()
                    credentials = null
                    _state.value = ConnectionState.Failed(loginFailureText(reply.reason))
                }
                is LoginReply.Success -> {
                    socket.soTimeout = 0
                    server = socket
                    this@SoulseekClient.username = username
                    listenPort?.let { sendServer(Messages.setWaitPort(it)) }
                    sendServer(Messages.sharedFoldersFiles(0, 0))
                    sendServer(Messages.haveNoParent(true))
                    sendServer(Messages.acceptChildren(false))
                    sendServer(Messages.setStatus(STATUS_ONLINE))
                    _state.value = ConnectionState.Connected(username, listenPort)
                    log("Signed in as $username, listening on $listenPort")
                    sessionJobs = listOf(
                        scope.launch { serverLoop(socket, input) },
                        scope.launch { keepAlive(socket) },
                        scope.launch { refreshQueuePositions(socket) },
                    )
                }
            }
        } catch (e: IOException) {
            _state.value = ConnectionState.Failed("Can't reach the Soulseek server (${e.message ?: e.javaClass.simpleName})")
            scheduleReconnect()
        }
    }

    /** Signs out; downloads that are waiting in someone's queue are stopped. */
    fun disconnect() {
        credentials = null
        closeSession()
        searches.clear()
        transfersById.values.filter { it.info.active }.forEach { finish(it, TransferStatus.FAILED, "Signed out") }
        _state.value = ConnectionState.Disconnected
    }

    /** Drops the server and peer connections; queued downloads survive a reconnect. */
    private fun closeSession() {
        val socket = server
        server = null
        username = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        socket?.closeQuietly()
        peers.values.forEach { it.socket.closeQuietly() }
        peers.clear()
    }

    fun close() {
        disconnect()
        listener?.closeQuietly()
        listener = null
        scope.cancel()
        executor.shutdownNow()
    }

    fun search(query: String): Search {
        val search = Search(tokens.incrementAndGet(), query)
        searches[search.token] = search
        scope.launch {
            try {
                sendServer(Messages.fileSearch(search.token, query))
            } catch (e: IOException) {
                log("Search failed: ${e.message}")
            }
        }
        return search
    }

    fun stopSearch(search: Search) {
        searches.remove(search.token)
    }

    fun transfer(id: Long): Transfer? = transfersById[id]

    /**
     * Asks [username] for [filename] and writes it to [target]. Resumes from what [target] already
     * holds. Returns the existing transfer when this file is already being fetched.
     */
    fun download(username: String, filename: String, size: Long, target: File): Transfer {
        transfersById.values.firstOrNull {
            it.username == username && it.filename == filename && it.status != TransferStatus.CANCELLED
        }?.let { existing ->
            if (existing.status == TransferStatus.FAILED) retry(existing.id)
            return existing
        }
        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()
        if (size > 0 && target.length() > size) RandomAccessFile(target, "rw").use { it.setLength(size) }
        val transfer = Transfer(transferIds.getAndIncrement(), username, filename, GrowingFile(target, size))
        transfersById[transfer.id] = transfer
        if (transfer.data.complete) {
            transfer.status = TransferStatus.COMPLETED
            publish()
        } else {
            request(transfer)
        }
        return transfer
    }

    fun cancel(id: Long) {
        val transfer = transfersById[id] ?: return
        if (!transfer.info.active) return
        finish(transfer, TransferStatus.CANCELLED, "Cancelled")
    }

    fun retry(id: Long) {
        val transfer = transfersById[id] ?: return
        if (transfer.info.active || transfer.status == TransferStatus.COMPLETED) return
        transfer.attempts = 0
        request(transfer)
    }

    /** Forgets a finished transfer (and optionally deletes what was downloaded). */
    fun remove(id: Long, deleteFile: Boolean) {
        val transfer = transfersById[id] ?: return
        if (transfer.info.active) finish(transfer, TransferStatus.CANCELLED, "Cancelled")
        transfersById.remove(id)
        if (deleteFile) transfer.data.file.delete()
        publish()
    }

    // ---- server ----

    private fun sendServer(bytes: ByteArray) {
        val socket = server ?: throw IOException("Not signed in")
        synchronized(serverWriteLock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun serverLoop(socket: Socket, input: InputStream) {
        try {
            while (true) {
                val frame = input.readFrame(MAX_SERVER_FRAME)
                val code = MessageReader(frame).u32()
                try {
                    handleServerMessage(code, frame.payload())
                } catch (e: ProtocolException) {
                    log("Bad server message $code: ${e.message}")
                }
            }
        } catch (e: IOException) {
            if (server === socket) {
                server = null
                username = null
                _state.value = ConnectionState.Failed("Lost the connection to the Soulseek server")
                scheduleReconnect()
            }
        }
    }

    private fun handleServerMessage(code: Int, payload: ByteArray) {
        when (code) {
            ServerCode.GET_PEER_ADDRESS -> {
                val address = Messages.parsePeerAddress(payload)
                addressWaiters.remove(address.username)?.complete(address)
            }
            ServerCode.CONNECT_TO_PEER -> {
                val request = Messages.parseConnectToPeer(payload)
                scope.launch { answerConnectToPeer(request) }
            }
            ServerCode.CANT_CONNECT_TO_PEER -> {
                val token = Messages.parseCantConnectToPeer(payload)
                pierceWaiters.remove(token)?.completeExceptionally(IOException("The user can't connect to us"))
            }
            ServerCode.RELOGGED -> {
                credentials = null
                _state.value = ConnectionState.Failed("Signed in somewhere else with this username")
                server?.closeQuietly()
                server = null
            }
        }
    }

    private suspend fun keepAlive(socket: Socket) {
        while (scope.isActive && server === socket) {
            delay(PING_INTERVAL_MS)
            runCatching { sendServer(Messages.serverPing()) }
        }
    }

    private fun scheduleReconnect() {
        val (user, password) = credentials ?: return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (credentials == user to password && server == null) connect(user, password)
        }
    }

    private suspend fun requestAddress(username: String): PeerAddress {
        val waiter = addressWaiters.computeIfAbsent(username) { CompletableDeferred() }
        sendServer(Messages.getPeerAddress(username))
        return withTimeoutOrNull(timeouts.serverReplyMs) { waiter.await() }
            ?: throw IOException("The server didn't give $username's address").also { addressWaiters.remove(username, waiter) }
    }

    // ---- peers ----

    private class Link(val socket: Socket, val input: InputStream)

    private inner class PeerConnection(val username: String, val socket: Socket, val input: InputStream) {
        private val writeLock = Any()

        val open: Boolean get() = !socket.isClosed

        fun send(bytes: ByteArray) = synchronized(writeLock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun ensureListener() {
        if (listener != null) return
        for (port in listenPorts) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                continue
            }
            listener = socket
            scope.launch { acceptLoop(socket) }
            return
        }
        log("No free listening port in $listenPorts; only outgoing connections will work")
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (e: IOException) {
                break
            }
            scope.launch { handleIncoming(socket) }
        }
    }

    /** A user connected to us: a peer or file connection (PeerInit), or an answer to our relay request. */
    private fun handleIncoming(socket: Socket) {
        try {
            socket.soTimeout = timeouts.initReadMs
            val input = BufferedInputStream(socket.getInputStream())
            val reader = MessageReader(input.readFrame(MAX_INIT_FRAME))
            when (reader.u8()) {
                InitCode.PEER_INIT -> {
                    val user = reader.str()
                    when (reader.str()) {
                        ConnType.PEER -> runPeer(PeerConnection(user, socket, input))
                        ConnType.FILE -> receiveFile(user, socket, input)
                        else -> socket.close()
                    }
                }
                InitCode.PIERCE_FIREWALL -> {
                    val waiter = pierceWaiters.remove(reader.u32())
                    if (waiter == null || !waiter.complete(Link(socket, input))) socket.close()
                }
                else -> socket.close()
            }
        } catch (e: IOException) {
            socket.closeQuietly()
        }
    }

    /** The server relays that [request].username couldn't reach us, so we connect to them. */
    private fun answerConnectToPeer(request: ConnectToPeer) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(request.ip, request.port), timeouts.connectMs)
            socket.getOutputStream().write(Messages.pierceFirewall(request.token))
            val input = BufferedInputStream(socket.getInputStream())
            when (request.type) {
                ConnType.PEER -> runPeer(PeerConnection(request.username, socket, input))
                ConnType.FILE -> receiveFile(request.username, socket, input)
                else -> socket.close()
            }
        } catch (e: IOException) {
            socket.closeQuietly()
            runCatching { sendServer(Messages.cantConnectToPeer(request.token, request.username)) }
        }
    }

    /** A peer connection to [username]: an existing one, a direct one, or one through the server. */
    private suspend fun peer(username: String): PeerConnection {
        peers[username]?.takeIf { it.open }?.let { return it }
        return peerLocks.computeIfAbsent(username) { Mutex() }.withLock {
            peers[username]?.takeIf { it.open }?.let { return@withLock it }
            val link = openLink(username, ConnType.PEER)
            PeerConnection(username, link.socket, link.input).also { connection ->
                peers[username] = connection
                scope.launch { runPeer(connection) }
            }
        }
    }

    private suspend fun openLink(username: String, type: String): Link {
        val me = this.username ?: throw IOException("Not signed in")
        val address = requestAddress(username)
        if (!address.online) throw IOException("$username is offline")
        val direct = Socket()
        try {
            direct.connect(InetSocketAddress(address.ip, address.port), timeouts.connectMs)
            direct.getOutputStream().write(Messages.peerInit(me, type, tokens.incrementAndGet()))
            return Link(direct, BufferedInputStream(direct.getInputStream()))
        } catch (e: IOException) {
            direct.closeQuietly()
        }
        // They're behind a firewall: ask them (through the server) to connect to us instead.
        val token = tokens.incrementAndGet()
        val waiter = CompletableDeferred<Link>()
        pierceWaiters[token] = waiter
        try {
            sendServer(Messages.connectToPeer(token, username, type))
            return withTimeoutOrNull(timeouts.indirectMs) { waiter.await() }
                ?: throw IOException("Can't connect to $username (both of you may be behind firewalls)")
        } finally {
            pierceWaiters.remove(token)
        }
    }

    private fun runPeer(connection: PeerConnection) {
        peers.compute(connection.username) { _, old -> if (old != null && old.open) old else connection }
        try {
            connection.socket.soTimeout = timeouts.idleMs
            while (true) {
                val frame = connection.input.readFrame(MAX_PEER_FRAME)
                val code = MessageReader(frame).u32()
                try {
                    handlePeerMessage(connection, code, frame.payload())
                } catch (e: ProtocolException) {
                    log("Bad message $code from ${connection.username}: ${e.message}")
                }
            }
        } catch (e: SocketTimeoutException) {
            // Idle: the user connects again when a queued file is ready.
        } catch (e: IOException) {
            // Closed.
        } finally {
            connection.socket.closeQuietly()
            peers.remove(connection.username, connection)
        }
    }

    private fun handlePeerMessage(connection: PeerConnection, code: Int, payload: ByteArray) {
        val user = connection.username
        when (code) {
            PeerCode.FILE_SEARCH_RESPONSE -> {
                val response = Messages.parseSearchResponse(payload)
                searches[response.token]?.add(response)
            }
            PeerCode.TRANSFER_REQUEST -> {
                val request = Messages.parseTransferRequest(payload)
                val transfer = findTransfer(user, request.filename)
                if (request.direction == TransferDirection.UPLOAD && transfer != null && transfer.status != TransferStatus.CANCELLED &&
                    transfer.status != TransferStatus.COMPLETED
                ) {
                    transfer.token = request.token
                    if (request.size > 0) transfer.data.totalSize = request.size
                    transfer.status = TransferStatus.CONNECTING
                    transfer.error = null
                    publish()
                    connection.send(Messages.transferResponse(request.token, allowed = true))
                } else {
                    val reason = if (request.direction == TransferDirection.UPLOAD) "Cancelled" else "File not shared."
                    connection.send(Messages.transferResponse(request.token, allowed = false, reason = reason))
                }
            }
            PeerCode.PLACE_IN_QUEUE_RESPONSE -> {
                val reader = MessageReader(payload)
                val transfer = findTransfer(user, reader.str()) ?: return
                transfer.queuePosition = reader.u32()
                if (transfer.status == TransferStatus.REQUESTING) transfer.status = TransferStatus.QUEUED
                publish()
            }
            PeerCode.UPLOAD_FAILED -> {
                val transfer = findTransfer(user, MessageReader(payload).str()) ?: return
                if (transfer.info.active) failOrRetry(transfer, "$user's client couldn't send the file")
            }
            PeerCode.UPLOAD_DENIED -> {
                val reader = MessageReader(payload)
                val transfer = findTransfer(user, reader.str()) ?: return
                if (transfer.info.active) finish(transfer, TransferStatus.FAILED, deniedText(reader.str()))
            }
            PeerCode.USER_INFO_REQUEST -> connection.send(Messages.userInfoResponse(description))
            PeerCode.SHARED_FILE_LIST_REQUEST -> connection.send(Messages.emptySharedFileList())
            PeerCode.QUEUE_UPLOAD -> connection.send(Messages.uploadDenied(MessageReader(payload).str(), "File not shared."))
        }
    }

    // ---- transfers ----

    private fun findTransfer(username: String, filename: String): Transfer? =
        transfersById.values.filter { it.username == username && it.filename == filename }.maxByOrNull { it.id }

    private fun request(transfer: Transfer) {
        transfer.status = TransferStatus.REQUESTING
        transfer.error = null
        transfer.queuePosition = null
        transfer.data.reset()
        publish()
        transfer.job = scope.launch {
            try {
                val connection = peer(transfer.username)
                connection.send(Messages.queueUpload(transfer.filename))
                if (transfer.status == TransferStatus.REQUESTING) transfer.status = TransferStatus.QUEUED
                publish()
                connection.send(Messages.placeInQueueRequest(transfer.filename))
            } catch (e: IOException) {
                if (transfer.status == TransferStatus.REQUESTING) finish(transfer, TransferStatus.FAILED, e.message ?: "Can't reach ${transfer.username}")
            }
        }
    }

    private suspend fun refreshQueuePositions(socket: Socket) {
        while (scope.isActive && server === socket) {
            delay(timeouts.queueRefreshMs)
            transfersById.values.filter { it.status == TransferStatus.QUEUED }.forEach { transfer ->
                scope.launch { runCatching { peer(transfer.username).send(Messages.placeInQueueRequest(transfer.filename)) } }
            }
        }
    }

    /** A file connection: the uploader names the transfer, we say where to resume, the bytes follow. */
    private fun receiveFile(username: String, socket: Socket, input: InputStream) {
        var transfer: Transfer? = null
        try {
            socket.soTimeout = timeouts.fileReadMs
            val token = MessageReader(input.readExactly(4)).u32()
            transfer = transfersById.values.firstOrNull { it.username == username && it.token == token && it.info.active }
                ?: return socket.close()
            transfer.socket = socket
            val size = transfer.size
            val file = transfer.data.file
            val offset = if (size > 0) minOf(file.length(), size) else file.length()
            socket.getOutputStream().write(MessageWriter().u64(offset).toByteArray())
            socket.getOutputStream().flush()
            transfer.status = TransferStatus.TRANSFERRING
            publish()
            RandomAccessFile(file, "rw").use { out ->
                out.seek(offset)
                var position = offset
                var windowStart = System.nanoTime()
                var windowBytes = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                while (size <= 0 || position < size) {
                    val wanted = if (size > 0) minOf(buffer.size.toLong(), size - position).toInt() else buffer.size
                    val n = input.read(buffer, 0, wanted)
                    if (n < 0) {
                        if (size <= 0) break
                        throw EOFException("$username closed the connection")
                    }
                    out.write(buffer, 0, n)
                    position += n
                    windowBytes += n
                    transfer.data.update(position)
                    val elapsed = System.nanoTime() - windowStart
                    if (elapsed >= 1_000_000_000L) {
                        transfer.speed = windowBytes * 1_000_000_000L / elapsed
                        windowStart = System.nanoTime()
                        windowBytes = 0
                        publish()
                    }
                }
                if (size <= 0) transfer.data.totalSize = position
                transfer.data.update(position)
            }
            finish(transfer, TransferStatus.COMPLETED, null)
        } catch (e: IOException) {
            transfer?.takeIf { it.info.active }?.let { failOrRetry(it, e.message ?: "The transfer was interrupted") }
        } finally {
            socket.closeQuietly()
            transfer?.socket = null
        }
    }

    /** Interrupted transfers are asked for again (resuming where they stopped) a few times. */
    private fun failOrRetry(transfer: Transfer, reason: String) {
        transfer.socket?.closeQuietly()
        if (++transfer.attempts > MAX_RETRIES) {
            finish(transfer, TransferStatus.FAILED, reason)
            return
        }
        log("Retrying ${transfer.filename}: $reason")
        transfer.status = TransferStatus.REQUESTING
        transfer.error = reason
        publish()
        transfer.job = scope.launch {
            delay(timeouts.retryDelayMs)
            if (transfer.status == TransferStatus.REQUESTING) request(transfer)
        }
    }

    private fun finish(transfer: Transfer, status: TransferStatus, error: String?) {
        transfer.status = status
        transfer.error = error
        transfer.speed = 0
        if (status != TransferStatus.COMPLETED) {
            transfer.data.fail(error ?: "Stopped")
            transfer.job?.cancel()
            transfer.socket?.closeQuietly()
        }
        publish()
    }

    private fun publish() {
        _transfers.value = transfersById.values.sortedBy { it.id }.map { it.info }
    }

    private fun loginFailureText(reason: String): String = when (reason) {
        "INVALIDPASS" -> "Wrong password for this username"
        "INVALIDUSERNAME" -> "This username isn't allowed; try another"
        "EMPTYPASSWORD" -> "Enter a password"
        "INVALIDVERSION" -> "The server doesn't accept this client version"
        "SVRFULL" -> "The Soulseek server is full; try again later"
        "SVRPRIVATE" -> "The Soulseek server is private"
        else -> "Sign-in refused: $reason"
    }

    private fun deniedText(reason: String): String = when {
        reason.equals("File not shared.", ignoreCase = true) -> "The user no longer shares this file"
        reason.startsWith("Too many", ignoreCase = true) -> "The user's queue for you is full ($reason)"
        reason.equals("Banned", ignoreCase = true) -> "The user doesn't allow you to download"
        else -> "Refused: $reason"
    }

    companion object {
        const val DEFAULT_SERVER_HOST = "server.slsknet.org"
        const val DEFAULT_SERVER_PORT = 2242
        val DEFAULT_LISTEN_PORTS = 2234..2239

        private const val STATUS_ONLINE = 2
        private const val MAX_SERVER_FRAME = 32 * 1024 * 1024
        private const val MAX_PEER_FRAME = 32 * 1024 * 1024
        private const val MAX_INIT_FRAME = 4096
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RETRIES = 3
        private const val PING_INTERVAL_MS = 5 * 60_000L
        private const val RECONNECT_DELAY_MS = 15_000L
    }
}

private fun ByteArray.payload(): ByteArray = copyOfRange(4, size)

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
