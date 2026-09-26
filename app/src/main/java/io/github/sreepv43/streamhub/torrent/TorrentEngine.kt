package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.addon.AddonUrls
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class TorrentException(message: String) : IOException(message)

/** Live numbers for the UI. */
data class TorrentStats(
    val name: String?,
    val hasMetadata: Boolean,
    val peers: Int,
    val seeds: Int,
    val downloadRate: Int,
    val uploadRate: Int,
    /** Progress of the file being played / downloaded, 0..1. */
    val fileProgress: Float,
    /** Bytes of that file downloaded so far. */
    val fileDownloadedBytes: Long = 0,
)

/**
 * Built-in BitTorrent client (libtorrent via libtorrent4j) tuned for streaming: only the requested
 * file is downloaded, sequentially, and the pieces a reader is waiting for get deadlines so they
 * arrive first. Data is cached in the largest available storage and deleted when unused.
 *
 * Kept free of Android APIs so it can be exercised on a desktop JVM.
 */
class TorrentEngine(
    /** Candidate cache directories; the one with the most free space is used for each torrent. */
    private val cacheDirs: () -> List<File>,
    /** Downloads the bytes of a .torrent file from an http(s)/content/file URL. */
    private val fetchTorrentFile: (String) -> ByteArray,
    private val idleTimeoutMs: Long = 5 * 60_000L,
    /** Cache size above which torrents nobody is watching are removed at once (0 = no limit). */
    private val cacheLimitBytes: () -> Long = { 0L },
    /** Extra listen settings, e.g. for tests. */
    private val configure: (SettingsPack) -> Unit = {},
) {
    private val log = Logger.getLogger("TorrentEngine")
    private val lock = Any()
    private var session: SessionManager? = null
    private val torrents = HashMap<String, Entry>()
    private val sourcesToHash = HashMap<String, String>()
    private val janitor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "torrent-janitor").apply { isDaemon = true }
    }

    init {
        janitor.scheduleWithFixedDelay({ runCatching { trimCache() } }, 1, 1, TimeUnit.MINUTES)
    }

    internal class Entry(val hash: String, val handle: TorrentHandle, val saveDir: File) {
        var refs = 0
        var lastReleased = System.currentTimeMillis()
        val openFiles = HashSet<Int>()
    }

    /** One file inside a torrent, opened for reading. Call [close] when done. */
    inner class TorrentFile internal constructor(
        private val entry: Entry,
        val index: Int,
        val name: String,
        val size: Long,
        private val fileOffset: Long,
        private val pieceLength: Int,
        private val lastPiece: Int,
        private val path: File,
    ) : AutoCloseable {
        private var raf: RandomAccessFile? = null
        private var closed = false
        private var windowStart = -1

        val infoHash: String get() = entry.hash

        private fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

        /** Bytes that can be read at [position] without crossing into the next piece. */
        private fun bytesLeftInPiece(position: Long): Long {
            val absolute = fileOffset + position
            return (absolute / pieceLength + 1) * pieceLength - absolute
        }

        /**
         * Reads up to [length] bytes at [position], waiting (up to [timeoutMs]) for the piece to be
         * downloaded. Returns -1 at end of file, 0 if the piece did not arrive in time.
         */
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
            if (position >= size) return -1
            val piece = pieceAt(position)
            if (!awaitPiece(piece, timeoutMs)) return 0
            val count = minOf(length.toLong(), bytesLeftInPiece(position), size - position).toInt()
            val file = raf ?: RandomAccessFile(path, "r").also { raf = it }
            file.seek(position)
            return file.read(buffer, offset, count)
        }

        private fun awaitPiece(piece: Int, timeoutMs: Long): Boolean {
            prioritizeWindow(piece)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed) {
                if (entry.handle.havePiece(piece)) return true
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(50)
            }
            return false
        }

        /** Asks libtorrent to fetch the pieces right after the read position first. */
        private fun prioritizeWindow(piece: Int) {
            if (piece == windowStart) return
            val jump = windowStart < 0 || piece < windowStart || piece > windowStart + 2
            windowStart = piece
            val count = maxOf(MIN_READAHEAD_PIECES, (READAHEAD_BYTES / pieceLength).toInt())
            synchronized(lock) {
                if (!entry.handle.isValid) return
                if (jump) entry.handle.clearPieceDeadlines()
                for (i in 0 until count) {
                    val p = piece + i
                    if (p > lastPiece) break
                    if (!entry.handle.havePiece(p)) entry.handle.setPieceDeadline(p, 100 + i * 150)
                }
            }
        }

        fun progress(): Float = synchronized(lock) {
            if (!entry.handle.isValid || size == 0L) return 0f
            val done = entry.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(index) ?: 0L
            (done.toFloat() / size).coerceIn(0f, 1f)
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { raf?.close() }
            release(entry)
        }
    }

    /**
     * Adds the torrent if needed, waits for its metadata and opens one of its files. [fileIdx] -1
     * picks the largest video file. Blocks; call from a background thread.
     */
    fun open(source: String, fileIdx: Int, metadataTimeoutMs: Long = 90_000): TorrentFile {
        val entry = acquire(source)
        try {
            val info = awaitMetadata(entry, metadataTimeoutMs)
            val files = info.files()
            val index = if (fileIdx in 0 until files.numFiles() && !files.padFileAt(fileIdx)) fileIdx else pickFile(info)
            synchronized(lock) {
                if (entry.openFiles.add(index)) {
                    val priorities = Array(files.numFiles()) { i ->
                        if (i in entry.openFiles) Priority.DEFAULT else Priority.IGNORE
                    }
                    entry.handle.prioritizeFiles(priorities)
                    entry.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    // Containers keep their index at the end (MP4 moov, MKV cues): fetch it early.
                    val last = files.lastPieceIndexAtFile(index)
                    entry.handle.setPieceDeadline(last, 500)
                }
            }
            return TorrentFile(
                entry = entry,
                index = index,
                name = files.fileName(index),
                size = files.fileSize(index),
                fileOffset = files.fileOffset(index),
                pieceLength = info.pieceLength(),
                lastPiece = files.lastPieceIndexAtFile(index),
                path = File(entry.saveDir, files.filePath(index)),
            )
        } catch (e: Throwable) {
            release(entry)
            throw e
        }
    }

    /** Stats for a source that is currently open, or null. */
    fun stats(source: String, fileIdx: Int = -1): TorrentStats? = synchronized(lock) {
        val entry = sourcesToHash[source]?.let { torrents[it] } ?: return null
        if (!entry.handle.isValid) return null
        val status = entry.handle.status()
        val info = if (status.hasMetadata()) entry.handle.torrentFile() else null
        val index = when {
            info == null -> -1
            fileIdx in 0 until info.numFiles() -> fileIdx
            else -> entry.openFiles.firstOrNull() ?: -1
        }
        val fileDone = if (index >= 0) entry.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(index) ?: 0L else 0L
        val fileProgress = if (index >= 0) {
            val size = info!!.files().fileSize(index)
            if (size > 0) (fileDone.toFloat() / size).coerceIn(0f, 1f) else 0f
        } else 0f
        TorrentStats(
            name = info?.name(),
            hasMetadata = status.hasMetadata(),
            peers = status.numPeers(),
            seeds = status.numSeeds(),
            downloadRate = status.downloadPayloadRate(),
            uploadRate = status.uploadPayloadRate(),
            fileProgress = fileProgress,
            fileDownloadedBytes = fileDone,
        )
    }

    /** Removes every torrent nobody is reading and deletes its data. */
    fun clearCache() {
        synchronized(lock) {
            torrents.values.filter { it.refs == 0 }.forEach(::removeLocked)
        }
        cacheDirs().forEach { dir ->
            dir.listFiles()?.filter { child -> synchronized(lock) { torrents.values.none { it.saveDir == child } } }
                ?.forEach { it.deleteRecursively() }
        }
    }

    /** Stops the session; the engine can be used again afterwards (it restarts lazily). */
    fun shutdown() {
        synchronized(lock) {
            torrents.clear()
            sourcesToHash.clear()
            session?.stop()
            session = null
        }
    }

    fun cacheSizeBytes(): Long = cacheDirs().sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Over the cache limit: removes torrents nobody is reading, least recently used first. */
    fun trimCache() {
        val limit = cacheLimitBytes()
        if (limit <= 0) return
        var size = cacheSizeBytes()
        if (size <= limit) return
        val idle = synchronized(lock) { torrents.values.filter { it.refs == 0 }.sortedBy { it.lastReleased } }
        for (entry in idle) {
            if (size <= limit) break
            val bytes = entry.saveDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            synchronized(lock) { if (entry.refs == 0) removeLocked(entry) }
            size -= bytes
        }
    }

    private fun acquire(source: String): Entry {
        val known = synchronized(lock) {
            sourcesToHash[source]?.let { torrents[it] }?.takeIf { it.handle.isValid }?.also { it.refs++ }
        }
        if (known != null) return known

        val session = session()
        val (hash, info) = if (TorrentLinks.isMagnet(source)) {
            val hash = TorrentLinks.infoHashFromMagnet(source) ?: throw TorrentException("Invalid magnet link")
            hash to null
        } else {
            val info = try {
                TorrentInfo(fetchTorrentFile(source))
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw TorrentException("Not a valid .torrent file")
            }
            info.infoHash().toHex().lowercase() to info
        }

        val saveDir = synchronized(lock) {
            torrents[hash]?.takeIf { it.handle.isValid }?.let { entry ->
                sourcesToHash[source] = hash
                entry.refs++
                return entry
            }
            val dir = File(pickCacheDir(), hash).apply { mkdirs() }
            if (info != null) {
                val priorities = Array(info.numFiles()) { Priority.IGNORE }
                session.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            } else {
                session.download(withDefaultTrackers(source), dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
            dir
        }

        // Adding is asynchronous; wait for the handle to appear.
        val sha1 = Sha1Hash.parseHex(hash)
        val until = System.currentTimeMillis() + 10_000
        var handle: TorrentHandle? = null
        while (handle == null && System.currentTimeMillis() < until) {
            handle = session.find(sha1)?.takeIf { it.isValid }
            if (handle == null) Thread.sleep(50)
        }
        val added = handle ?: throw TorrentException("Could not add torrent")

        return synchronized(lock) {
            val entry = torrents.getOrPut(hash) { Entry(hash, added, saveDir) }
            sourcesToHash[source] = hash
            entry.refs++
            entry
        }
    }

    private fun awaitMetadata(entry: Entry, timeoutMs: Long): TorrentInfo {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val info = synchronized(lock) {
                if (!entry.handle.isValid) throw TorrentException("Torrent was removed")
                entry.handle.torrentFile()?.takeIf { it.isValid && it.numFiles() > 0 }
            }
            if (info != null) return info
            if (System.currentTimeMillis() > until) {
                throw TorrentException("No peers found for this torrent (timed out fetching metadata)")
            }
            Thread.sleep(200)
        }
    }

    private fun release(entry: Entry) {
        synchronized(lock) {
            entry.refs = (entry.refs - 1).coerceAtLeast(0)
            entry.lastReleased = System.currentTimeMillis()
        }
        janitor.schedule({
            synchronized(lock) {
                if (entry.refs == 0 && System.currentTimeMillis() - entry.lastReleased >= idleTimeoutMs) {
                    removeLocked(entry)
                }
            }
        }, idleTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
    }

    private fun removeLocked(entry: Entry) {
        torrents.remove(entry.hash)
        sourcesToHash.entries.removeAll { it.value == entry.hash }
        runCatching {
            if (entry.handle.isValid) session?.remove(entry.handle, SessionHandle.DELETE_FILES)
        }.onFailure { log.log(Level.WARNING, "remove failed", it) }
        janitor.schedule({ entry.saveDir.deleteRecursively() }, 5, TimeUnit.SECONDS)
    }

    private fun session(): SessionManager = synchronized(lock) {
        session ?: SessionManager(false).also { s ->
            // Leftovers from a previous run are never reused.
            cacheDirs().forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
            val settings = SettingsPack()
                .connectionsLimit(200)
                .activeDownloads(8)
                .activeSeeds(8)
            settings.setInteger(settings_pack.int_types.request_timeout.swigValue(), 10)
            settings.setInteger(settings_pack.int_types.piece_timeout.swigValue(), 10)
            settings.setEnableDht(true)
            settings.setEnableLsd(true)
            configure(settings)
            s.start(SessionParams(settings))
            session = s
        }
    }

    private fun pickCacheDir(): File =
        cacheDirs().onEach { it.mkdirs() }.maxByOrNull { it.usableSpace } ?: throw TorrentException("No storage available")

    private fun pickFile(info: TorrentInfo): Int {
        val files = info.files()
        val indices = (0 until files.numFiles()).filterNot { files.padFileAt(it) }
        val videos = indices.filter { files.fileName(it).substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
        return (videos.ifEmpty { indices }).maxByOrNull { files.fileSize(it) } ?: 0
    }

    private fun withDefaultTrackers(magnet: String): String {
        val existing = magnet.lowercase()
        return magnet + DEFAULT_TRACKERS
            .filterNot { existing.contains(AddonUrls.encodeComponent(it).lowercase()) }
            .joinToString("") { "&tr=" + AddonUrls.encodeComponent(it) }
    }

    companion object {
        private const val READAHEAD_BYTES = 24L * 1024 * 1024
        private const val MIN_READAHEAD_PIECES = 4

        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "wmv", "flv", "mpg", "mpeg", "m2ts")

        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
        )
    }
}
