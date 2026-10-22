package io.github.sreepv43.streamhub.server

import com.sun.net.httpserver.HttpServer
import org.libtorrent4j.SettingsPack
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

sealed interface PairResult {
    data class Paired(val token: String) : PairResult
    data object WrongCode : PairResult
    data object TooManyAttempts : PairResult
}

/**
 * StreamHub Server: downloads torrents for the app into its storages, keeps them in a library and
 * streams them to StreamHub (and any player) on the home network. Settings and state live in
 * [dataDir] (config.json, jobs.json, library.json, resume/).
 */
class StreamHubServer(
    private val dataDir: File,
    private val portOverride: Int? = null,
    configureTorrents: (SettingsPack) -> Unit = {},
    minVideoBytes: Long = Library.MIN_VIDEO_BYTES,
    private val log: (String) -> Unit = ::println,
) {
    val version: String = StreamHubServer::class.java.`package`?.implementationVersion ?: "dev"
    private val configFile = File(dataDir, "config.json")
    private var config: ServerConfig = loadConfig()
    private val random = SecureRandom()
    /** Shown on the server (console and pairing-code.txt); entered once on the TV. */
    @Volatile var pairingCode: String = newCode()
        private set
    private val failedPairings = ArrayDeque<Long>()

    val downloads = Downloads(dataDir, { config().storages }, configureTorrents)
    val library = Library(dataDir, { config().storages }, { downloads.list() }, minVideoBytes)
    private var http: HttpServer? = null

    val port: Int get() = http?.address?.port ?: portOverride ?: config.port

    fun config(): ServerConfig = synchronized(this) { config }

    fun start(): StreamHubServer {
        dataDir.mkdirs()
        prepareStorages()
        downloads.start()
        library.start()
        val server = HttpServer.create(InetSocketAddress(portOverride ?: config.port), 64)
        server.createContext("/", Api(this))
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
        server.start()
        http = server
        showPairingCode()
        log("StreamHub Server $version is running on port $port. Data folder: ${dataDir.absolutePath}")
        config.storages.forEach { s ->
            log("Storage \"${s.label}\": ${s.path} — " + if (Storages.isAvailable(s)) "available" else "NOT available")
        }
        return this
    }

    fun stop() {
        http?.stop(1)
        library.stop()
        downloads.stop()
    }

    fun status(): ServerStatus {
        val c = config()
        return ServerStatus(c.name, version, c.storages.map(Storages::view), c.defaultStorage ?: c.storages.firstOrNull()?.id, c.smbPath)
    }

    fun authorized(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return config().devices.any { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }
    }

    fun pair(code: String, device: String): PairResult = synchronized(this) {
        val now = System.currentTimeMillis()
        while (failedPairings.isNotEmpty() && now - failedPairings.first() > 60_000) failedPairings.removeFirst()
        if (failedPairings.size >= MAX_FAILED_PAIRINGS) return PairResult.TooManyAttempts
        if (!MessageDigest.isEqual(code.toByteArray(), pairingCode.toByteArray())) {
            failedPairings.addLast(now)
            return PairResult.WrongCode
        }
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        config = config.copy(devices = config.devices + PairedDevice(device.take(60), token, now))
        saveConfig()
        // A code works once; the next device gets a new one.
        pairingCode = newCode()
        showPairingCode()
        log("Paired with \"$device\".")
        PairResult.Paired(token)
    }

    /** Adds a storage folder (e.g. a drive) and marks it as connected. */
    fun addStorage(label: String, path: String): StorageDef = synchronized(this) {
        val dir = File(path).absoluteFile
        require(dir.isDirectory || dir.mkdirs()) { "Can't use $path: the folder doesn't exist and can't be created" }
        val storage = StorageDef(id = "s" + (config.storages.size + 1) + "-" + System.currentTimeMillis().toString(36), label = label, path = dir.path)
        Storages.mark(storage)
        config = config.copy(storages = config.storages + storage)
        saveConfig()
        storage
    }

    /**
     * Storages that are folders on another disk than the server's own data (an external drive, a
     * NAS volume) are marked as real the first time they are seen connected; a missing marker later
     * means the drive isn't mounted. The default folder in the user's home is always fine.
     */
    private fun prepareStorages() {
        if (config.storages.isEmpty()) {
            val fromEnv = System.getenv("STREAMHUB_STORAGE")?.split(';', '|')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            if (fromEnv.isEmpty()) {
                addStorage("Server storage", File(System.getProperty("user.home"), "StreamHub Downloads").path)
            } else {
                fromEnv.forEachIndexed { i, entry ->
                    val label = entry.substringBefore('=', "").ifEmpty { "Storage ${i + 1}" }
                    addStorage(label, entry.substringAfter('='))
                }
            }
        }
        val home = runCatching { Files.getFileStore(dataDir.toPath()) }.getOrNull()
        config.storages.forEach { storage ->
            val dir = File(storage.path)
            if (Storages.isAvailable(storage) || !dir.isDirectory) return@forEach
            val store = runCatching { Files.getFileStore(dir.toPath()) }.getOrNull()
            if (store != null && store != home) {
                Storages.mark(storage)
            } else {
                log("Storage \"${storage.label}\" (${storage.path}) is on the server's own disk and has no ${Storages.MARKER} file; " +
                    "if this is right, create that file in the folder (StreamHub doesn't store downloads there until then).")
            }
        }
    }

    private fun showPairingCode() {
        runCatching { File(dataDir, "pairing-code.txt").writeText(pairingCode + "\n") }
        log("Pairing code for StreamHub (Settings → Downloads → Home Server): $pairingCode")
    }

    private fun newCode(): String = (100_000 + random.nextInt(900_000)).toString()

    private fun loadConfig(): ServerConfig =
        runCatching { ServerJson.decodeFromString(ServerConfig.serializer(), configFile.readText()) }.getOrNull() ?: ServerConfig()

    private fun saveConfig() {
        dataDir.mkdirs()
        writeAtomically(configFile, ServerJson.encodeToString(ServerConfig.serializer(), config).toByteArray())
    }

    companion object {
        private const val MAX_FAILED_PAIRINGS = 5
    }
}
