package io.github.sreepv43.soundhub.slsk

/**
 * The parts of the Soulseek protocol SoundHub speaks, as documented by the Nicotine+ project
 * (doc/SLSKPROTOCOL.md). Codes are grouped by connection type.
 */
internal object ServerCode {
    const val LOGIN = 1
    const val SET_WAIT_PORT = 2
    const val GET_PEER_ADDRESS = 3
    const val CONNECT_TO_PEER = 18
    const val FILE_SEARCH = 26
    const val SET_STATUS = 28
    const val SERVER_PING = 32
    const val SHARED_FOLDERS_FILES = 35
    const val RELOGGED = 41
    const val HAVE_NO_PARENT = 71
    const val ACCEPT_CHILDREN = 100
    const val CANT_CONNECT_TO_PEER = 1001
}

internal object PeerCode {
    const val SHARED_FILE_LIST_REQUEST = 4
    const val SHARED_FILE_LIST_RESPONSE = 5
    const val FILE_SEARCH_RESPONSE = 9
    const val USER_INFO_REQUEST = 15
    const val USER_INFO_RESPONSE = 16
    const val TRANSFER_REQUEST = 40
    const val TRANSFER_RESPONSE = 41
    const val QUEUE_UPLOAD = 43
    const val PLACE_IN_QUEUE_RESPONSE = 44
    const val UPLOAD_FAILED = 46
    const val UPLOAD_DENIED = 50
    const val PLACE_IN_QUEUE_REQUEST = 51
}

internal object InitCode {
    const val PIERCE_FIREWALL = 0
    const val PEER_INIT = 1
}

/** Peer connection types sent in PeerInit / ConnectToPeer. */
internal object ConnType {
    const val PEER = "P"
    const val FILE = "F"
    const val DISTRIBUTED = "D"
}

internal object TransferDirection {
    const val DOWNLOAD = 0
    const val UPLOAD = 1
}

/** A file in someone's share, as found by a search. [filename] is the full remote path (backslashes). */
data class SharedFile(
    val filename: String,
    val size: Long,
    val extension: String = "",
    val attributes: Map<Int, Int> = emptyMap(),
) {
    val bitrate: Int? get() = attributes[ATTR_BITRATE]
    val durationSec: Int? get() = attributes[ATTR_DURATION]
    val vbr: Boolean get() = attributes[ATTR_VBR] == 1
    val sampleRate: Int? get() = attributes[ATTR_SAMPLE_RATE]
    val bitDepth: Int? get() = attributes[ATTR_BIT_DEPTH]

    companion object {
        const val ATTR_BITRATE = 0
        const val ATTR_DURATION = 1
        const val ATTR_VBR = 2
        const val ATTR_SAMPLE_RATE = 4
        const val ATTR_BIT_DEPTH = 5
    }
}

/** One user's answer to a search. [avgSpeed] is in bytes per second. */
data class SearchResponse(
    val username: String,
    val token: Int,
    val files: List<SharedFile>,
    val slotFree: Boolean,
    val avgSpeed: Int,
    val queueLength: Long,
    val privateFiles: List<SharedFile> = emptyList(),
)

internal sealed interface LoginReply {
    data class Success(val greeting: String, val ownIp: String) : LoginReply
    data class Failure(val reason: String) : LoginReply
}

internal data class PeerAddress(val username: String, val ip: String, val port: Int) {
    val online: Boolean get() = port != 0 && ip != "0.0.0.0"
}

/** The server asking us to connect to a peer who could not reach us directly. */
internal data class ConnectToPeer(
    val username: String,
    val type: String,
    val ip: String,
    val port: Int,
    val token: Int,
)

internal data class TransferRequest(val direction: Int, val token: Int, val filename: String, val size: Long)

internal object Messages {
    const val PROTOCOL_VERSION = 160
    const val MINOR_VERSION = 1

    // ---- server, outgoing ----

    fun login(username: String, password: String): ByteArray = message(
        ServerCode.LOGIN,
        MessageWriter()
            .str(username)
            .str(password)
            .u32(PROTOCOL_VERSION)
            .str(md5Hex(username + password))
            .u32(MINOR_VERSION)
            .toByteArray(),
    )

    fun setWaitPort(port: Int) = message(ServerCode.SET_WAIT_PORT, MessageWriter().u32(port).toByteArray())

    fun getPeerAddress(username: String) = message(ServerCode.GET_PEER_ADDRESS, MessageWriter().str(username).toByteArray())

    fun connectToPeer(token: Int, username: String, type: String) =
        message(ServerCode.CONNECT_TO_PEER, MessageWriter().u32(token).str(username).str(type).toByteArray())

    fun fileSearch(token: Int, query: String) =
        message(ServerCode.FILE_SEARCH, MessageWriter().u32(token).str(query).toByteArray())

    fun setStatus(status: Int) = message(ServerCode.SET_STATUS, MessageWriter().u32(status).toByteArray())

    fun serverPing() = message(ServerCode.SERVER_PING)

    fun sharedFoldersFiles(folders: Int, files: Int) =
        message(ServerCode.SHARED_FOLDERS_FILES, MessageWriter().u32(folders).u32(files).toByteArray())

    fun haveNoParent(noParent: Boolean) = message(ServerCode.HAVE_NO_PARENT, MessageWriter().bool(noParent).toByteArray())

    fun acceptChildren(accept: Boolean) = message(ServerCode.ACCEPT_CHILDREN, MessageWriter().bool(accept).toByteArray())

    fun cantConnectToPeer(token: Int, username: String) =
        message(ServerCode.CANT_CONNECT_TO_PEER, MessageWriter().u32(token).str(username).toByteArray())

    // ---- server, incoming ----

    fun parseLogin(payload: ByteArray): LoginReply {
        val reader = MessageReader(payload)
        return if (reader.bool()) {
            val greeting = reader.str()
            val ip = if (reader.remaining >= 4) ipString(reader.u32()) else ""
            LoginReply.Success(greeting, ip)
        } else {
            LoginReply.Failure(if (reader.remaining >= 4) reader.str() else "UNKNOWN")
        }
    }

    fun parsePeerAddress(payload: ByteArray): PeerAddress {
        val reader = MessageReader(payload)
        return PeerAddress(reader.str(), ipString(reader.u32()), reader.u32())
    }

    fun parseConnectToPeer(payload: ByteArray): ConnectToPeer {
        val reader = MessageReader(payload)
        val username = reader.str()
        val type = reader.str()
        val ip = ipString(reader.u32())
        val port = reader.u32()
        val token = reader.u32()
        return ConnectToPeer(username, type, ip, port, token)
    }

    fun parseCantConnectToPeer(payload: ByteArray): Int = MessageReader(payload).u32()

    // ---- peer init ----

    fun peerInit(username: String, type: String, token: Int) =
        initMessage(InitCode.PEER_INIT, MessageWriter().str(username).str(type).u32(token).toByteArray())

    fun pierceFirewall(token: Int) = initMessage(InitCode.PIERCE_FIREWALL, MessageWriter().u32(token).toByteArray())

    // ---- peer ----

    fun queueUpload(filename: String) = message(PeerCode.QUEUE_UPLOAD, MessageWriter().str(filename).toByteArray())

    fun placeInQueueRequest(filename: String) =
        message(PeerCode.PLACE_IN_QUEUE_REQUEST, MessageWriter().str(filename).toByteArray())

    fun transferResponse(token: Int, allowed: Boolean, reason: String? = null): ByteArray {
        val writer = MessageWriter().u32(token).bool(allowed)
        if (!allowed) writer.str(reason ?: "Cancelled")
        return message(PeerCode.TRANSFER_RESPONSE, writer.toByteArray())
    }

    fun uploadDenied(filename: String, reason: String) =
        message(PeerCode.UPLOAD_DENIED, MessageWriter().str(filename).str(reason).toByteArray())

    fun userInfoResponse(description: String) = message(
        PeerCode.USER_INFO_RESPONSE,
        MessageWriter()
            .str(description)
            .bool(false) // no picture
            .u32(0) // upload slots
            .u32(0) // queue size
            .bool(false) // slots free
            .toByteArray(),
    )

    /** An empty share list (folders, an unknown always-zero field, private folders). */
    fun emptySharedFileList() = message(
        PeerCode.SHARED_FILE_LIST_RESPONSE,
        zlibCompress(MessageWriter().u32(0).u32(0).u32(0).toByteArray()),
    )

    fun parseSearchResponse(payload: ByteArray): SearchResponse {
        val reader = MessageReader(zlibDecompress(payload, MAX_SEARCH_RESPONSE))
        val username = reader.str()
        val token = reader.u32()
        val files = readFiles(reader)
        val slotFree = reader.bool()
        val avgSpeed = reader.u32()
        // Older clients send a uint64 queue length; newer ones a uint32 followed by an unused uint32.
        val queueLength = reader.u32().toLong() and 0xFFFFFFFFL
        if (reader.remaining >= 4) reader.u32()
        val privateFiles = if (reader.remaining >= 4) runCatching { readFiles(reader) }.getOrDefault(emptyList()) else emptyList()
        return SearchResponse(username, token, files, slotFree, avgSpeed, queueLength, privateFiles)
    }

    fun searchResponse(response: SearchResponse): ByteArray {
        val writer = MessageWriter().str(response.username).u32(response.token)
        writeFiles(writer, response.files)
        writer.bool(response.slotFree).u32(response.avgSpeed).u32(response.queueLength.toInt()).u32(0)
        writeFiles(writer, response.privateFiles)
        return message(PeerCode.FILE_SEARCH_RESPONSE, zlibCompress(writer.toByteArray()))
    }

    fun parseTransferRequest(payload: ByteArray): TransferRequest {
        val reader = MessageReader(payload)
        val direction = reader.u32()
        val token = reader.u32()
        val filename = reader.str()
        val size = if (direction == TransferDirection.UPLOAD && reader.remaining >= 8) reader.u64() else -1L
        return TransferRequest(direction, token, filename, size)
    }

    fun transferRequest(request: TransferRequest): ByteArray {
        val writer = MessageWriter().u32(request.direction).u32(request.token).str(request.filename)
        if (request.direction == TransferDirection.UPLOAD) writer.u64(request.size)
        return message(PeerCode.TRANSFER_REQUEST, writer.toByteArray())
    }

    private fun readFiles(reader: MessageReader): List<SharedFile> {
        val count = reader.u32()
        if (count < 0 || count > MAX_FILES) throw ProtocolException("Bad file count $count")
        return List(count) {
            reader.u8() // code, always 1
            val filename = reader.str()
            val size = reader.u64()
            val extension = reader.str()
            val attributeCount = reader.u32()
            if (attributeCount < 0 || attributeCount > 32) throw ProtocolException("Bad attribute count")
            val attributes = HashMap<Int, Int>(attributeCount)
            repeat(attributeCount) { attributes[reader.u32()] = reader.u32() }
            SharedFile(filename, size, extension, attributes)
        }
    }

    private fun writeFiles(writer: MessageWriter, files: List<SharedFile>) {
        writer.u32(files.size)
        files.forEach { file ->
            writer.u8(1).str(file.filename).u64(file.size).str(file.extension).u32(file.attributes.size)
            file.attributes.forEach { (code, value) -> writer.u32(code).u32(value) }
        }
    }

    /** IPs are sent as a little-endian uint32 whose most significant byte is the first octet. */
    fun ipString(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    fun ipValue(ip: String): Int = ip.split('.').map { it.toInt() }.fold(0) { acc, octet -> (acc shl 8) or octet }

    private fun md5Hex(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val MAX_SEARCH_RESPONSE = 16 * 1024 * 1024
    private const val MAX_FILES = 100_000
}
