package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.addon.AddonUrls
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

/** One torrent in the engine, for the storage status screen. */
data class TorrentSummary(
    val name: String,
    val readers: Int,
    val downloadRate: Int,
    val peers: Int,
    val progress: Float,
    val cacheDir: File,
    /** Why it stopped (drive disconnected or full), or null. */
    val problem: String?,
)

/**
 * Built-in BitTorrent client (libtorrent via libtorrent4j) tuned for streaming: only the requested
 * file is downloaded, sequentially, and the pieces a reader is waiting for get deadlines so they
 * arrive first. Data is written straight to the chosen cache location (e.g. a USB drive) and read
 * from there; it is deleted when unused.
 *
 * Kept free of Android APIs so it can be exercised on a desktop JVM.
 */
class TorrentEngine(
    /** Every cache directory that currently exists (for cleanup and the cache size). */
    private val cacheDirs: () -> List<File>,
    /** Downloads the bytes of a .torrent file from an http(s)/content/file URL. */
    private val fetchTorrentFile: (String) -> ByteArray,
    private val idleTimeoutMs: Long = 5 * 60_000L,
    /** Where new torrents go. By default the cache directory with the most free space. */
    private val chooseCache: () -> CacheChoice = {
        cacheDirs().onEach { it.mkdirs() }.maxByOrNull { it.usableSpace }
            ?.let { CacheChoice.Ready(CacheVolume(it.path, it.path, it, false, it.totalSpace, it.usableSpace), it) }
            ?: CacheChoice.Unavailable("No storage available")
    },
    /** Cleanup target and free-space reserve of each cache directory. */
    private val cacheRules: (File) -> CacheRules = { CacheRules() },
    /** Extra listen settings, e.g. for tests. */
    private val configure: (SettingsPack) -> Unit = {},
    storageCheckSeconds: Long = 10,
    /** Told (once per occurrence) when the engine's lock has been held far too long, with the threads' stacks. */
    private val onStall: (String) -> Unit = {},
) {
    private val log = Logger.getLogger("TorrentEngine")
    /**
     * Guards the torrents and libtorrent calls. Held only briefly: never across file deletions or
     * waits. Calls from the UI only wait [UI_WAIT_MS] for it, so a busy engine can't freeze the screen.
     */
    private val lock = EngineLock()
    @Volatile private var session: SessionManager? = null
    /** Starting the session is slow; it has its own lock so it never holds up readers and the UI. */
    private val sessionLock = Any()
    private val torrents = HashMap<String, Entry>()
    private val sourcesToHash = HashMap<String, String>()
    private val janitor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "torrent-janitor").apply { isDaemon = true }
    }
    private val prefetching = HashSet<String>()
    private val prefetched = ArrayDeque<String>()
    private val prefetcher = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "torrent-prefetch").apply { isDaemon = true }
    }

    private val nextReaderId = AtomicLong()
    /** Where to write each torrent's resume data when libtorrent delivers it (by info hash). */
    private val resumeDirs = ConcurrentHashMap<String, File>()

    /** Writes resume data (which pieces are on disk) next to the torrent's folder, to reuse its data later. */
    private val resumeListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig())

        override fun alert(alert: Alert<*>) {
            val saved = alert as? SaveResumeDataAlert ?: return
            runCatching {
                val hash = saved.handle().infoHash().toHex().lowercase()
                val dir = resumeDirs[hash] ?: return
                writeAtomically(File(dir, "$hash$RESUME"), AddTorrentParams.writeResumeDataBuf(saved.params()))
            }.onFailure { log.log(Level.WARNING, "saving resume data failed", it) }
        }
    }

    /** The last storage problem that stopped a torrent (shown by the player), or null. */
    @Volatile var storageProblem: String? = null
        private set

    init {
        Thread({ watchLock() }, "torrent-watchdog").apply { isDaemon = true }.start()
        janitor.scheduleWithFixedDelay({ runCatching { trimCache() } }, 1, 1, TimeUnit.MINUTES)
        janitor.scheduleWithFixedDelay({ runCatching { checkStorage() } }, storageCheckSeconds, storageCheckSeconds, TimeUnit.SECONDS)
        janitor.scheduleWithFixedDelay({ runCatching { tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    internal class Entry(val hash: String, val handle: TorrentHandle, val saveDir: File, val volume: CacheVolume?) {
        /** Set when the drive holding [saveDir] went away or filled up: reading stops with this message. */
        @Volatile var lost: String? = null
        var refs = 0
        var lastReleased = System.currentTimeMillis()
        val openFiles = HashSet<Int>()
        /** Piece priorities were limited by a prefetch; open() lifts the limit. */
        var limitedPieces = false
        /**
         * Pieces known to be downloaded (they never go missing). Asking libtorrent is a call that
         * waits for its busy network thread, so it is asked once per piece at most.
         */
        val have = java.util.BitSet()
        /** Scheduling state of each open file. */
        val files = HashMap<Int, FileSchedule>()
        /** Recent download speeds (bytes/s), for the adaptive read-ahead. */
        val rates = ArrayDeque<Int>()
        var resumeSavedAt = 0L
    }

    /** Who needs which part of one open file, and what was last asked of libtorrent for it. */
    internal class FileSchedule(
        val index: Int,
        val size: Long,
        val fileOffset: Long,
        val pieceLength: Int,
        val firstPiece: Int,
        val lastPiece: Int,
    ) {
        val readers = HashMap<Long, ReaderWindow>()
        var durationMs = 0L
        var playheadMs = -1L
        var playheadAt = 0L
        var applied: PiecePlan? = null
        var prioritiesAt = 0L
        var deadlines: Set<Int> = emptySet()

        fun pieceAt(position: Long): Int = ((fileOffset + position.coerceIn(0, maxOf(0, size - 1))) / pieceLength).toInt()
    }

    private fun hasPieceLocked(entry: Entry, piece: Int): Boolean {
        if (entry.have.get(piece)) return true
        return (entry.handle.isValid && entry.handle.havePiece(piece)).also { if (it) entry.have.set(piece) }
    }

    /**
     * Brings libtorrent's piece priorities and deadlines in line with the readers of [file] (see
     * [PiecePlanner]). Deadlines follow every move; the full priority list is only sent again when
     * a reader came, went or jumped ([force]) or every [PRIORITY_REFRESH_MS], as it covers the
     * whole torrent. Called with [lock] held.
     */
    private fun replanLocked(entry: Entry, file: FileSchedule, force: Boolean) {
        if (!entry.handle.isValid || entry.lost != null) return
        val now = System.currentTimeMillis()
        val readers = file.readers.values.toMutableList()
        // The player's own position (while it reports it): what is on screen must never wait.
        if (file.playheadMs >= 0 && file.durationMs > 0 && now - file.playheadAt < PLAYHEAD_VALID_MS) {
            val byte = (file.size * (file.playheadMs.toDouble() / file.durationMs)).toLong()
            readers += ReaderWindow(PLAYHEAD_READER, file.pieceAt(byte), file.lastPiece, 0, now)
        }
        if (readers.isEmpty()) return
        val bitrate = PiecePlanner.bitrate(file.size, file.durationMs)
        val window = PiecePlanner.windowSeconds(bitrate, entry.rates.toList())
        val plan = PiecePlanner.plan(
            file.firstPiece, file.lastPiece, file.pieceLength.toLong(), bitrate, window, readers, now,
        ) { hasPieceLocked(entry, it) }
        val old = file.applied
        val changed = old == null || !old.priorities.contentEquals(plan.priorities)
        if (changed && (force || old == null || now - file.prioritiesAt >= PRIORITY_REFRESH_MS)) {
            val all = entry.handle.piecePriorities()
            for (i in plan.priorities.indices) {
                val piece = plan.firstPiece + i
                if (piece < all.size) all[piece] = Priority.fromSwig(plan.priorities[i])
            }
            entry.handle.prioritizePieces(all)
            file.applied = plan
            file.prioritiesAt = now
        }
        // Only this file's own obsolete deadlines are cancelled; other files and readers keep theirs.
        for (piece in file.deadlines) if (piece !in plan.deadlines) entry.handle.resetPieceDeadline(piece)
        for ((piece, deadline) in plan.deadlines) if (piece !in file.deadlines) entry.handle.setPieceDeadline(piece, deadline)
        file.deadlines = plan.deadlines.keys
    }

    /** One file inside a torrent, opened for reading (one HTTP request). Call [close] when done. */
    inner class TorrentFile internal constructor(
        private val entry: Entry,
        private val schedule: FileSchedule,
        val name: String,
        private val path: File,
    ) : AutoCloseable {
        val index: Int get() = schedule.index
        val size: Long get() = schedule.size
        private val pieceLength = schedule.pieceLength
        private val fileOffset = schedule.fileOffset
        private var raf: RandomAccessFile? = null
        @Volatile private var closed = false
        private val readerId = nextReaderId.incrementAndGet()
        private val startedAt = System.currentTimeMillis()
        private var lastPiece = schedule.lastPiece
        private var currentPiece = -1
        private var lastActive = startedAt

        val infoHash: String get() = entry.hash

        private fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

        /** Bytes that can be read at [position] without crossing into the next piece. */
        private fun bytesLeftInPiece(position: Long): Long {
            val absolute = fileOffset + position
            return (absolute / pieceLength + 1) * pieceLength - absolute
        }

        /**
         * The request only wants bytes up to [endInclusive] (e.g. the MKV index at the end): its
         * read-ahead stops there instead of reaching into the rest of the film.
         */
        fun limitTo(endInclusive: Long) {
            lastPiece = pieceAt(endInclusive.coerceIn(0, size - 1))
        }

        /**
         * Reads up to [length] bytes at [position], waiting (up to [timeoutMs]) for the piece to be
         * downloaded. Returns -1 at end of file, 0 if the piece did not arrive in time.
         */
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
            if (position >= size) return -1
            entry.lost?.let { throw TorrentException(it) }
            val piece = pieceAt(position)
            if (piece != currentPiece) moveTo(piece)
            if (!awaitPiece(piece, timeoutMs)) return 0
            val count = minOf(length.toLong(), bytesLeftInPiece(position), size - position).toInt()
            val file = raf ?: RandomAccessFile(path, "r").also { raf = it }
            file.seek(position)
            val read = file.read(buffer, offset, count)
            lastActive = System.currentTimeMillis()
            return read
        }

        /** Tells the scheduler where this reader is now. */
        private fun moveTo(piece: Int) {
            val jump = currentPiece < 0 || piece < currentPiece || piece > currentPiece + 2
            currentPiece = piece
            lock.withLock {
                schedule.readers[readerId] = ReaderWindow(readerId, piece, lastPiece, startedAt, lastActive)
                replanLocked(entry, schedule, force = jump)
            }
        }

        private fun awaitPiece(piece: Int, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed) {
                entry.lost?.let { throw TorrentException(it) }
                if (lock.withLock { hasPieceLocked(entry, piece) }) return true
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(50)
            }
            return false
        }

        fun progress(): Float = lock.withLock {
            if (!entry.handle.isValid || size == 0L) return 0f
            val done = entry.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(index) ?: 0L
            (done.toFloat() / size).coerceIn(0f, 1f)
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { raf?.close() }
            lock.withLock {
                // A closed request (finished, or the player seeked away) stops steering at once.
                if (schedule.readers.remove(readerId) != null) runCatching { replanLocked(entry, schedule, force = true) }
            }
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
            CachePolicy.fileTooBig(entry.volume, files.fileSize(index))?.let { problem ->
                storageProblem = problem
                throw TorrentException(problem)
            }
            lock.withLock {
                if (entry.openFiles.add(index)) {
                    val priorities = Array(files.numFiles()) { i ->
                        if (i in entry.openFiles) Priority.DEFAULT else Priority.IGNORE
                    }
                    entry.handle.prioritizeFiles(priorities)
                    if (entry.limitedPieces) {
                        entry.limitedPieces = false
                        val pieces = entry.handle.piecePriorities()
                        for (i in entry.openFiles) {
                            (files.pieceIndexAtFile(i)..files.lastPieceIndexAtFile(i)).forEach { pieces[it] = Priority.DEFAULT }
                        }
                        entry.handle.prioritizePieces(pieces)
                    }
                    entry.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    // Containers keep their index at the end (MP4 moov, MKV cues): fetch it early.
                    val last = files.lastPieceIndexAtFile(index)
                    entry.handle.setPieceDeadline(last, 500)
                }
            }
            val schedule = lock.withLock {
                entry.files.getOrPut(index) {
                    FileSchedule(
                        index = index,
                        size = files.fileSize(index),
                        fileOffset = files.fileOffset(index),
                        pieceLength = info.pieceLength(),
                        firstPiece = files.pieceIndexAtFile(index),
                        lastPiece = files.lastPieceIndexAtFile(index),
                    )
                }
            }
            return TorrentFile(entry, schedule, files.fileName(index), File(entry.saveDir, files.filePath(index)))
        } catch (e: Throwable) {
            release(entry)
            throw e
        }
    }

    /**
     * Starts fetching a torrent before it is played, so playing it starts sooner: its details
     * (file list), and with [warmStart] also the first few MB and the index at the end of the file
     * that would be played. Nothing else is downloaded; if it isn't played, it is removed like any
     * unused torrent.
     */
    fun prefetch(source: String, fileIdx: Int = -1, warmStart: Boolean = false) {
        // Called from the UI thread: everything, even taking the lock, happens in the background.
        prefetcher.execute { prefetchNow(source, fileIdx, warmStart) }
    }

    private fun prefetchNow(source: String, fileIdx: Int, warmStart: Boolean) {
        lock.withLock {
            if (source in prefetching || sourcesToHash[source]?.let { torrents[it] } != null) return
            prefetching += source
            // Browsing many titles: drop the oldest prepared torrents nobody went on to play.
            prefetched.remove(source)
            prefetched.addLast(source)
            while (prefetched.size > MAX_PREFETCHED) {
                val old = prefetched.removeFirst()
                sourcesToHash[old]?.let { torrents[it] }
                    ?.takeIf { it.refs == 0 && it.openFiles.isEmpty() }
                    ?.let(::removeLocked)
            }
        }
        run {
            var entry: Entry? = null
            try {
                val acquired = acquire(source).also { entry = it }
                val info = awaitMetadata(acquired, PREFETCH_METADATA_TIMEOUT_MS)
                val files = info.files()
                val index = if (fileIdx in 0 until files.numFiles() && !files.padFileAt(fileIdx)) fileIdx else pickFile(info)
                // The file stays wanted (libtorrent keeps pieces of unwanted files in a separate part
                // file, where the player can't read them); only its pieces are limited.
                val wanted = Array(files.numFiles()) { if (warmStart && it == index) Priority.DEFAULT else Priority.IGNORE }
                lock.withLock {
                    // Once someone reads the torrent, open() has set its own priorities.
                    if (acquired.openFiles.isNotEmpty() || !acquired.handle.isValid) return
                    acquired.handle.prioritizeFiles(wanted)
                }
                if (!warmStart) return
                // File priorities are applied later (on libtorrent's disk thread) and then reset the
                // piece priorities, so wait for them first.
                val until = System.currentTimeMillis() + 5_000
                while (!acquired.handle.filePriorities().contentEquals(wanted) && System.currentTimeMillis() < until) {
                    Thread.sleep(20)
                }
                lock.withLock {
                    if (acquired.openFiles.isNotEmpty() || !acquired.handle.isValid) return
                    val first = files.pieceIndexAtFile(index)
                    val last = files.lastPieceIndexAtFile(index)
                    val count = maxOf(MIN_READAHEAD_PIECES, (PREFETCH_BYTES / info.pieceLength()).toInt())
                    val pieces = Array(info.numPieces()) { Priority.IGNORE }
                    (first..minOf(last, first + count - 1)).forEach { pieces[it] = Priority.DEFAULT }
                    pieces[last] = Priority.DEFAULT
                    acquired.handle.prioritizePieces(pieces)
                    acquired.limitedPieces = true
                }
            } catch (e: Exception) {
                log.log(Level.FINE, "prefetch failed for $source", e)
            } finally {
                entry?.let(::release)
                lock.withLock { prefetching -= source }
            }
        }
    }

    /**
     * Why [source] can't be played from the torrent cache right now (its drive was disconnected or
     * is full, or the chosen cache location isn't available), or null.
     */
    fun problemFor(source: String): String? = lock.tryLocked(UI_WAIT_MS) {
        val entry = sourcesToHash[source]?.let { torrents[it] }
        entry?.lost ?: if (entry == null) storageProblem else null
    }

    /** Stats for a source that is currently open, or null. */
    fun stats(source: String, fileIdx: Int = -1): TorrentStats? = lock.tryLocked(UI_WAIT_MS) {
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

    /**
     * The player's position in [source] (file [fileIdx], -1 = the one being played) and the video's
     * length: what is on screen gets top priority, and the read-ahead adapts to the bitrate.
     * Returns how much video (ms) is downloaded without gaps from that position, or null.
     */
    fun reportPlayback(source: String, fileIdx: Int, positionMs: Long, durationMs: Long): Long? {
        val now = System.currentTimeMillis()
        val (entry, file, pieces) = lock.tryLocked(UI_WAIT_MS) {
            val entry = sourcesToHash[source]?.let { torrents[it] }?.takeIf { it.handle.isValid && it.lost == null } ?: return null
            val file = entry.files[fileIdx] ?: entry.files.values.firstOrNull() ?: return null
            val seeked = file.playheadMs < 0 || kotlin.math.abs(positionMs - file.playheadMs) > SEEK_THRESHOLD_MS
            file.playheadMs = positionMs
            file.playheadAt = now
            if (durationMs > 0) file.durationMs = durationMs
            // One call for the whole piece map (instead of asking piece by piece).
            val pieces = entry.handle.status(TorrentHandle.QUERY_PIECES).pieces()
            for (p in file.firstPiece..minOf(file.lastPiece, pieces.size() - 1)) if (pieces.getBit(p)) entry.have.set(p)
            replanLocked(entry, file, force = seeked)
            Triple(entry, file, entry.have.clone() as java.util.BitSet)
        } ?: return null
        if (file.durationMs <= 0) return null
        val byte = (file.size * (positionMs.toDouble() / file.durationMs)).toLong().coerceIn(0, file.size)
        val bytes = PiecePlanner.contiguousBytes(byte, file.fileOffset, file.size, file.pieceLength.toLong()) { pieces.get(it) }
        return (bytes / PiecePlanner.bitrate(file.size, file.durationMs) * 1000).toLong()
    }

    /**
     * Every [TICK_MS]: samples the download speed of torrents being played (for the adaptive
     * read-ahead), refreshes their piece plan, and saves their resume data now and then, so their
     * data is reused even if the app is killed.
     */
    private fun tick() {
        val now = System.currentTimeMillis()
        lock.withLock {
            for (entry in torrents.values) {
                if (!entry.handle.isValid || entry.lost != null) continue
                val busy = entry.files.values.any { it.readers.isNotEmpty() || now - it.playheadAt < PLAYHEAD_VALID_MS }
                if (!busy) continue
                val rate = runCatching { entry.handle.status().downloadPayloadRate() }.getOrDefault(0)
                entry.rates.addLast(rate)
                while (entry.rates.size > RATE_SAMPLES) entry.rates.removeFirst()
                entry.files.values.forEach { runCatching { replanLocked(entry, it, force = false) } }
                if (now - entry.resumeSavedAt > RESUME_SAVE_INTERVAL_MS) {
                    entry.resumeSavedAt = now
                    requestResumeLocked(entry)
                }
            }
        }
    }

    private fun requestResumeLocked(entry: Entry) {
        val dir = entry.saveDir.parentFile ?: return
        resumeDirs[entry.hash] = dir
        // With the torrent's details ("info"), so a magnet link needn't fetch them again.
        runCatching { if (entry.handle.isValid) entry.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.FLUSH_DISK_CACHE)) }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    /** Data of a torrent kept in [dir]: its folder and its resume data. */
    private fun keptFiles(dir: File, hash: String): List<File> = listOf(File(dir, hash), File(dir, hash + RESUME))

    /** Removes every torrent nobody is reading and deletes its data (also data kept from before). */
    fun clearCache() {
        val inUse = lock.withLock {
            torrents.values.filter { it.refs == 0 }.toList().forEach { removeLocked(it) }
            torrents.keys.toSet()
        }
        cacheDirs().forEach { dir ->
            dir.listFiles()?.filter { child -> inUse.none { child.name.startsWith(it) } }?.forEach { it.deleteRecursively() }
        }
    }

    /** Stops the session; the engine can be used again afterwards (it restarts lazily). */
    fun shutdown() {
        lock.withLock {
            torrents.clear()
            sourcesToHash.clear()
            session?.stop()
            session = null
        }
    }

    /**
     * The app went to the background: drops the torrents nobody is reading (prepared ones, ones
     * just watched) and, when none is left, stops the BitTorrent session so nothing keeps using the
     * TV's processor, network and storage. A running download keeps its torrent.
     */
    fun releaseIdle() {
        janitor.execute {
            val kept = lock.withLock {
                val idle = torrents.values.filter { it.refs == 0 }.toList()
                idle.forEach { removeLocked(it, keepData = it.files.isNotEmpty()) }
                prefetched.clear()
                idle.any { it.files.isNotEmpty() }
            }
            // Give libtorrent time to deliver the resume data before the session stops.
            if (kept) Thread.sleep(RESUME_SAVE_WAIT_MS)
            val stopped = synchronized(sessionLock) {
                lock.withLock {
                    if (torrents.isNotEmpty() || prefetching.isNotEmpty()) null
                    else session.also { session = null }
                }
            }
            stopped?.let { runCatching { it.stop() }.onFailure { e -> log.log(Level.WARNING, "stop failed", e) } }
        }
    }

    fun cacheSizeBytes(): Long = cacheDirs().sumOf(::sizeOf)

    /** Bytes of torrent data in [dir]. */
    fun sizeOf(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Every torrent in the engine, for the storage status screen. */
    fun summaries(): List<TorrentSummary> = lock.tryLocked(UI_WAIT_MS) {
        torrents.values.mapNotNull { entry ->
            if (!entry.handle.isValid) return@mapNotNull null
            val status = runCatching { entry.handle.status() }.getOrNull() ?: return@mapNotNull null
            TorrentSummary(
                name = status.name().ifEmpty { entry.hash.take(12) },
                readers = entry.refs,
                downloadRate = status.downloadPayloadRate(),
                peers = status.numPeers(),
                progress = status.progress(),
                cacheDir = entry.saveDir.parentFile ?: entry.saveDir,
                problem = entry.lost,
            )
        }
    } ?: emptyList()

    /**
     * Automatic cleanup: in each cache directory, removes torrents nobody is reading, least
     * recently used first, while the cache is over its cleanup target or its drive has less free
     * space than its reserve. Torrents being played or downloaded are never touched, so the target
     * can be exceeded while they are in use. Only cache directories are touched (never downloads).
     */
    fun trimCache() {
        for (dir in cacheDirs()) {
            val rules = cacheRules(dir)
            if (rules.targetBytes <= 0 && rules.reserveBytes <= 0) continue
            var size = sizeOf(dir)
            var free = dir.usableSpace
            fun over() = (rules.targetBytes > 0 && size > rules.targetBytes) || (rules.reserveBytes > 0 && free < rules.reserveBytes)
            if (!over()) continue
            // Least recently used first: torrents loaded but unused, and data kept from earlier.
            val candidates = lock.withLock {
                val loaded = torrents.values.filter { it.refs == 0 && it.saveDir.parentFile == dir }
                    .map { it.hash to it.lastReleased }
                val kept = dir.listFiles().orEmpty()
                    .filter { it.isDirectory && HASH.matches(it.name) && it.name !in torrents }
                    .map { it.name to maxOf(it.lastModified(), File(dir, it.name + RESUME).lastModified()) }
                (loaded + kept).sortedBy { it.second }.map { it.first }
            }
            for (hash in candidates) {
                if (!over()) break
                val bytes = keptFiles(dir, hash).sumOf(::sizeOf)
                val removed = lock.withLock {
                    val entry = torrents[hash]
                    when {
                        entry == null -> true
                        entry.refs == 0 -> true.also { removeLocked(entry) }
                        else -> false
                    }
                }
                // Deleting gigabytes (on a USB drive) takes a while: never while holding the lock.
                if (removed && lock.withLock { hash !in torrents }) keptFiles(dir, hash).forEach { it.deleteRecursively() }
                if (removed) {
                    size -= bytes
                    free += bytes
                }
            }
        }
    }

    /**
     * Watches the drives holding torrent data: when one is unplugged (its folder is gone) or is
     * nearly full, its torrents stop, readers get a clear error and nothing is written elsewhere.
     */
    private fun checkStorage() {
        val entries = lock.withLock { torrents.values.filter { it.lost == null }.toList() }
        if (entries.isEmpty()) return
        val full = HashSet<File>()
        for (entry in entries) {
            val dir = entry.saveDir.parentFile ?: continue
            val label = entry.volume?.label ?: "The torrent storage"
            val problem = when {
                !entry.saveDir.exists() ->
                    "$label was disconnected, so the torrent stopped. Reconnect it and play the title again."
                dir in full || dir.usableSpace in 1 until MIN_FREE_BYTES -> {
                    full += dir
                    trimCache()
                    if (dir.usableSpace in 1 until MIN_FREE_BYTES) {
                        "$label is full, so the torrent stopped. Free up space or clear the torrent cache."
                    } else {
                        full -= dir
                        null
                    }
                }
                else -> null
            } ?: continue
            storageProblem = problem
            lock.withLock {
                entry.lost = problem
                runCatching { if (entry.handle.isValid) entry.handle.pause() }
                if (entry.refs == 0) removeLocked(entry)
            }
        }
    }

    private fun acquire(source: String): Entry {
        val known = lock.withLock {
            sourcesToHash[source]?.let { torrents[it] }?.takeIf { it.handle.isValid && it.lost == null }?.also { it.refs++ }
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

        // Looking at the drives can be slow (a sleeping USB disk); never do it while holding the lock.
        val choice = pickCache()
        val cacheDir = choice.dir
        // Data kept from an earlier viewing on this drive: added with its resume data, libtorrent
        // continues from the pieces already there instead of downloading them again.
        val resumeFile = File(cacheDir, hash + RESUME).takeIf { it.isFile && File(cacheDir, hash).isDirectory }
        val keptInfo = if (resumeFile != null && info == null) {
            // Resume data saved with the torrent's "info" reads like a .torrent file.
            runCatching { TorrentInfo(resumeFile.readBytes()) }.getOrNull()
        } else null
        val addInfo = info ?: keptInfo
        val saveDir = lock.withLock {
            torrents[hash]?.takeIf { it.handle.isValid && it.lost == null }?.let { entry ->
                sourcesToHash[source] = hash
                entry.refs++
                return entry
            }
            val dir = File(cacheDir, hash).apply { mkdirs() }
            if (addInfo != null) {
                val priorities = Array(addInfo.numFiles()) { Priority.IGNORE }
                session.download(addInfo, dir, resumeFile, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
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

        return lock.withLock {
            val entry = torrents[hash]?.takeIf { it.lost == null }
                ?: Entry(hash, added, saveDir, choice.volume).also { torrents[hash] = it }
            sourcesToHash[source] = hash
            entry.refs++
            entry
        }
    }

    private fun awaitMetadata(entry: Entry, timeoutMs: Long): TorrentInfo {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val info = lock.withLock {
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
        lock.withLock {
            entry.refs = (entry.refs - 1).coerceAtLeast(0)
            entry.lastReleased = System.currentTimeMillis()
            // Its drive went away: nothing to keep it for.
            if (entry.refs == 0 && entry.lost != null && torrents[entry.hash] === entry) removeLocked(entry)
        }
        janitor.schedule({
            lock.withLock {
                if (entry.refs == 0 && System.currentTimeMillis() - entry.lastReleased >= idleTimeoutMs && torrents[entry.hash] === entry) {
                    // Played titles keep their data on the drive; ones only prepared don't.
                    removeLocked(entry, keepData = entry.files.isNotEmpty())
                }
            }
        }, idleTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
    }

    /**
     * Takes [entry] out of the engine. With [keepData] (a title that was played), its data stays on
     * the drive with fresh resume data, so playing it again continues from what is already there;
     * otherwise its data is deleted. A torrent whose drive is gone is only let go of.
     */
    private fun removeLocked(entry: Entry, keepData: Boolean = false) {
        if (torrents[entry.hash] === entry) torrents.remove(entry.hash)
        sourcesToHash.entries.removeAll { it.value == entry.hash }
        val dir = entry.saveDir.parentFile
        when {
            entry.lost != null -> runCatching { if (entry.handle.isValid) session?.remove(entry.handle) }
            keepData && dir != null -> {
                requestResumeLocked(entry)
                janitor.schedule({
                    lock.withLock {
                        // Played again in the meantime: it is in use, not removed.
                        if (!torrents.containsKey(entry.hash)) runCatching { if (entry.handle.isValid) session?.remove(entry.handle) }
                    }
                }, RESUME_SAVE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            else -> {
                runCatching {
                    if (entry.handle.isValid) session?.remove(entry.handle, SessionHandle.DELETE_FILES)
                }.onFailure { log.log(Level.WARNING, "remove failed", it) }
                janitor.schedule({
                    if (lock.withLock { !torrents.containsKey(entry.hash) }) {
                        entry.saveDir.deleteRecursively()
                        if (dir != null) keptFiles(dir, entry.hash).forEach { it.deleteRecursively() }
                    }
                }, 5, TimeUnit.SECONDS)
            }
        }
    }

    private fun session(): SessionManager {
        session?.let { return it }
        return synchronized(sessionLock) {
            session ?: SessionManager(false).also { s ->
                // Data kept with resume data is reused; other leftovers (e.g. from an app that was
                // killed before saving any) are removed. Moving them aside is instant; deleting them
                // (gigabytes, perhaps on a USB drive) happens in the background.
                val stale = cacheDirs().flatMap { dir ->
                    val children = dir.listFiles()?.toList().orEmpty()
                    val kept = children.filter { it.isDirectory && HASH.matches(it.name) && keptFiles(dir, it.name).all(File::exists) }
                        .flatMap { keptFiles(dir, it.name) }.toSet()
                    children.filter { it !in kept }
                }.mapNotNull { leftover ->
                    val aside = File(leftover.parentFile, ".old-" + System.nanoTime())
                    if (leftover.renameTo(aside)) aside else { leftover.deleteRecursively(); null }
                }
                if (stale.isNotEmpty()) janitor.execute { stale.forEach { runCatching { it.deleteRecursively() } } }
                val settings = SettingsPack()
                    // Modest limits: TVs have slow processors and storage, and one stream needs few peers.
                    .connectionsLimit(60)
                    .activeDownloads(4)
                    .activeSeeds(2)
                    .uploadRateLimit(2_000_000)
                settings.setInteger(settings_pack.int_types.request_timeout.swigValue(), 10)
                settings.setInteger(settings_pack.int_types.piece_timeout.swigValue(), 10)
                // Peers that dropped out are tried again after 15 s instead of a minute (speeds fluctuate).
                settings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 15)
                settings.setEnableDht(true)
                settings.setEnableLsd(true)
                configure(settings)
                s.addListener(resumeListener)
                s.start(SessionParams(settings))
                session = s
            }
        }
    }

    /** Reports (once) when one thread keeps the engine's lock for over [STALL_MS]: something is stuck. */
    private fun watchLock() {
        var reported: Thread? = null
        while (true) {
            Thread.sleep(5_000)
            val owner = lock.holder
            val since = lock.heldSince
            if (owner == null || owner === reported || System.currentTimeMillis() - since < STALL_MS) {
                if (owner == null) reported = null
                continue
            }
            reported = owner
            val report = buildString {
                appendLine("Torrent engine stuck for ${(System.currentTimeMillis() - since) / 1000} s in thread ${owner.name}")
                // The thread holding the lock first: that is where it is stuck.
                Thread.getAllStackTraces().filterKeys { it.name.startsWith("torrent") || it === owner || it.name.startsWith("pool") }
                    .entries.sortedBy { if (it.key === owner) 0 else 1 }
                    .forEach { (thread, stack) ->
                        appendLine()
                        appendLine("${thread.name} (${thread.state})")
                        stack.take(25).forEach { appendLine("    at $it") }
                    }
            }
            log.warning(report)
            runCatching { onStall(report) }
        }
    }

    /** Starts the BitTorrent session in the background so the first torrent doesn't wait for it. */
    fun warmUp() {
        prefetcher.execute { runCatching { session() }.onFailure { log.log(Level.WARNING, "warm-up failed", it) } }
    }

    /**
     * The chosen cache location for a new torrent. When it is too full, unused cached torrents are
     * removed first; if it still can't be used, nothing is stored anywhere else.
     */
    private fun pickCache(): CacheChoice.Ready {
        var choice = chooseCache()
        if (choice is CacheChoice.Unavailable && choice.lowSpace) {
            trimCache()
            choice = chooseCache()
        }
        return when (choice) {
            is CacheChoice.Ready -> {
                if (!choice.dir.isDirectory && !choice.dir.mkdirs()) {
                    val problem = "Can't create the torrent folder on ${choice.volume.label}"
                    storageProblem = problem
                    throw TorrentException(problem)
                }
                storageProblem = null
                choice
            }
            is CacheChoice.Unavailable -> {
                storageProblem = choice.message
                throw TorrentException(choice.message)
            }
        }
    }

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
        private const val MIN_READAHEAD_PIECES = 4
        /** How long calls from the screen wait for a busy engine before showing what they have. */
        private const val UI_WAIT_MS = 300L
        private const val STALL_MS = 30_000L
        private const val TICK_MS = 2_000L
        /** The full piece priority list is sent at most this often unless a reader comes, goes or jumps. */
        private const val PRIORITY_REFRESH_MS = 2_000L
        /** Id of the player's own position among a file's readers. */
        private const val PLAYHEAD_READER = -1L
        private const val PLAYHEAD_VALID_MS = 5_000L
        /** A reported position this far from the last one is a seek. */
        private const val SEEK_THRESHOLD_MS = 5_000L
        private const val RATE_SAMPLES = 15
        private const val RESUME_SAVE_INTERVAL_MS = 60_000L
        private const val RESUME_SAVE_WAIT_MS = 3_000L
        private const val RESUME = ".resume"
        private val HASH = Regex("[0-9a-f]{40}")
        /** A drive with less free space than this stops its torrents rather than fail mid-write. */
        private const val MIN_FREE_BYTES = 512L * 1024 * 1024
        private const val PREFETCH_BYTES = 8L * 1024 * 1024
        private const val MAX_PREFETCHED = 6
        private const val PREFETCH_METADATA_TIMEOUT_MS = 90_000L

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

/** A lock that knows who holds it and since when (for the stall report), with a timed attempt. */
private class EngineLock : ReentrantLock() {
    @Volatile var heldSince = 0L
        private set

    val holder: Thread? get() = owner

    override fun lock() {
        super.lock()
        if (holdCount == 1) heldSince = System.currentTimeMillis()
    }

    override fun tryLock(timeout: Long, unit: TimeUnit): Boolean =
        super.tryLock(timeout, unit).also { if (it && holdCount == 1) heldSince = System.currentTimeMillis() }

    /** Runs [block] with the lock if it can be had within [waitMs]; otherwise returns null. */
    inline fun <T> tryLocked(waitMs: Long, block: () -> T): T? {
        if (!tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
