package io.github.sreepv43.streamhub.server

import kotlinx.serialization.decodeFromString
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The server's downloads: each job downloads one file of a torrent with libtorrent into one of the
 * configured storages. Jobs and libtorrent's resume data are kept in the data folder, so downloads
 * continue after a restart. The numbers the app shows (bytes, speeds, peers, state) come from
 * libtorrent once a second.
 */
class Downloads(
    private val dataDir: File,
    private val storages: () -> List<StorageDef>,
    private val configure: (SettingsPack) -> Unit = {},
    private val onCompleted: (Job) -> Unit = {},
    private val tickMs: Long = 1_000,
) {
    private val jobs = LinkedHashMap<String, Job>()
    private val jobsFile = File(dataDir, "jobs.json")
    private val resumeDir = File(dataDir, "resume").apply { mkdirs() }
    private val metadataDir = File(dataDir, "metadata").apply { mkdirs() }
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    /** When each job last received data, for "stalled". */
    private val lastProgress = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "download-worker").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "download-ticker").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    private val session = SessionManager(false)
    private var ticks = 0L

    private val resumeListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig())

        override fun alert(alert: Alert<*>) {
            val saved = alert as? SaveResumeDataAlert ?: return
            val hash = runCatching { saved.handle().infoHash().toHex().lowercase() }.getOrNull() ?: return
            val job = synchronized(jobs) { jobs.values.firstOrNull { it.infoHash == hash } } ?: return
            runCatching { writeAtomically(File(resumeDir, job.id + ".resume"), AddTorrentParams.writeResumeDataBuf(saved.params())) }
        }
    }

    fun start() {
        load()
        val settings = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(6)
            .activeSeeds(4)
        settings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 15)
        settings.setEnableDht(true)
        settings.setEnableLsd(true)
        configure(settings)
        session.addListener(resumeListener)
        session.start(SessionParams(settings))
        synchronized(jobs) { jobs.values.toList() }
            .filter { it.state != JobState.COMPLETED && !it.paused }
            .forEach { launch(it.id) }
        ticker.scheduleWithFixedDelay({ runCatching { tick() } }, tickMs, tickMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        ticker.shutdownNow()
        handles.values.forEach { runCatching { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } }
        Thread.sleep(1_500)
        save()
        runCatching { session.stop() }
        workers.shutdownNow()
    }

    fun list(): List<Job> = synchronized(jobs) { jobs.values.toList() }.sortedByDescending { it.createdAt }

    fun get(id: String): Job? = synchronized(jobs) { jobs[id] }

    fun add(request: NewDownload): Job {
        require(request.source.startsWith("magnet:", ignoreCase = true) || request.source.startsWith("http", ignoreCase = true) ||
            request.source.startsWith("file:", ignoreCase = true)) { "The source must be a magnet link or a .torrent link" }
        val all = storages()
        val storage = all.firstOrNull { it.id == request.storageId } ?: all.firstOrNull()
            ?: throw IllegalStateException("No storage is configured on the server")
        val job = Job(
            id = UUID.randomUUID().toString(),
            source = request.source,
            fileIdx = request.fileIdx,
            title = request.title,
            subtitle = request.subtitle,
            poster = request.poster,
            metaId = request.metaId,
            type = request.type,
            videoId = request.videoId,
            storageId = storage.id,
            storageLabel = storage.label,
            createdAt = System.currentTimeMillis(),
        )
        update(job.id) { job }
        launch(job.id)
        return job
    }

    fun pause(id: String): Job? {
        val job = update(id) { it.copy(paused = true, state = if (it.state == JobState.COMPLETED) it.state else JobState.PAUSED, downloadRate = 0, uploadRate = 0) }
        handles[id]?.let { handle ->
            runCatching {
                // Not auto-managed, or libtorrent's queue would start it again.
                handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
                handle.pause()
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
        }
        return job
    }

    fun resume(id: String): Job? {
        val job = update(id) { if (it.state == JobState.COMPLETED) it else it.copy(paused = false, state = JobState.QUEUED, error = null) } ?: return null
        if (job.state == JobState.COMPLETED) return job
        val handle = handles[id]
        if (handle != null && handle.isValid) {
            runCatching {
                handle.setFlags(TorrentFlags.AUTO_MANAGED)
                handle.resume()
            }
        } else {
            launch(id)
        }
        return job
    }

    /** Removes a job; with [deleteFile], also its file (finished or partial). */
    fun remove(id: String, deleteFile: Boolean): Boolean {
        val job = synchronized(jobs) { jobs.remove(id) } ?: return false
        save()
        handles.remove(id)?.let { runCatching { session.remove(it) } }
        File(resumeDir, "$id.resume").delete()
        if (deleteFile) {
            workers.execute {
                // libtorrent may still be closing the file; try for a moment.
                repeat(10) {
                    val file = job.filePath?.let(::File)
                    if (file == null || !file.exists() || file.delete()) return@execute
                    Thread.sleep(500)
                }
            }
        }
        return true
    }

    /** Adds the torrent of [id] to libtorrent (on a worker thread: fetching its details can take minutes). */
    private fun launch(id: String) {
        if (!starting.add(id)) return
        workers.execute {
            try {
                startJob(id)
            } catch (e: Exception) {
                update(id) { it.copy(state = JobState.ERROR, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                starting.remove(id)
            }
        }
    }

    private fun startJob(id: String) {
        var job = get(id) ?: return
        if (job.paused || handles.containsKey(id)) return
        val storage = storages().firstOrNull { it.id == job.storageId }
        if (storage == null) {
            update(id) { it.copy(state = JobState.ERROR, error = "The storage this download used is no longer configured") }
            return
        }
        val dir = File(storage.path)
        if (!Storages.isAvailable(storage)) {
            update(id) { it.copy(state = JobState.STORAGE_UNAVAILABLE, error = Storages.unavailableMessage(storage)) }
            return
        }

        val resumeFile = File(resumeDir, "$id.resume").takeIf { it.isFile }
        val info = resumeFile?.let { runCatching { TorrentInfo(it.readBytes()) }.getOrNull() }
            ?: File(metadataDir, "$id.torrent").takeIf { it.isFile }?.let { runCatching { TorrentInfo(it.readBytes()) }.getOrNull() }
        if (info == null && job.source.startsWith("magnet:", ignoreCase = true)) {
            // The torrent's details come from peers; tick() picks the file once they arrive.
            val hash = Magnets.infoHash(job.source) ?: throw IllegalArgumentException("Invalid magnet link")
            update(id) { it.copy(state = JobState.METADATA, infoHash = hash) }
            session.download(withTrackers(job.source), dir, TorrentFlags.AUTO_MANAGED)
            handles[id] = awaitHandle(hash)
            if (get(id)?.paused == true) pause(id)
            return
        }
        addWithInfo(id, info ?: TorrentInfo(torrentBytes(job.source).also { writeAtomically(File(metadataDir, "$id.torrent"), it) }), storage, resumeFile)
    }

    /** Adds a torrent whose details are known, downloading only the job's file. */
    private fun addWithInfo(id: String, info: TorrentInfo, storage: StorageDef, resumeFile: File?) {
        var job = get(id) ?: return
        val dir = File(storage.path)
        val files = info.files()
        val index = job.fileIdx.takeIf { it in 0 until files.numFiles() } ?: largestVideo(info)
        val hash = info.infoHash().toHex().lowercase()
        val path = File(dir, files.filePath(index)).path
        val size = files.fileSize(index)
        job = update(id) {
            it.copy(infoHash = hash, fileName = files.fileName(index), filePath = path, totalBytes = size, error = null)
        } ?: return
        if (job.paused) return

        val existing = File(path).takeIf { it.isFile }?.length() ?: 0L
        if (dir.usableSpace < size - existing + SPACE_RESERVE) {
            update(id) {
                it.copy(
                    state = JobState.ERROR,
                    error = "Not enough space on ${storage.label}: this download needs ${gb(size - existing)}, " +
                        "${gb(dir.usableSpace)} is free (StreamHub keeps ${gb(SPACE_RESERVE)} free)",
                )
            }
            return
        }
        val priorities = Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE }
        update(id) { it.copy(state = JobState.CONNECTING) }
        session.download(info, dir, resumeFile, priorities, peersFrom(job.source), TorrentFlags.AUTO_MANAGED)
        handles[id] = awaitHandle(hash)
        if (get(id)?.paused == true) pause(id)
    }

    /** A magnet download whose details just arrived: only its file is downloaded from now on. */
    private fun metadataArrived(id: String, handle: TorrentHandle, info: TorrentInfo) {
        val job = get(id) ?: return
        val storage = storages().firstOrNull { it.id == job.storageId } ?: return
        val dir = File(storage.path)
        val files = info.files()
        val index = job.fileIdx.takeIf { it in 0 until files.numFiles() } ?: largestVideo(info)
        handle.prioritizeFiles(Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
        val size = files.fileSize(index)
        val path = File(dir, files.filePath(index)).path
        // Resume data (with the torrent's details) lets a restart continue without asking peers again.
        runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
        update(id) { it.copy(fileIdx = index, fileName = files.fileName(index), filePath = path, totalBytes = size, state = JobState.CONNECTING, error = null) }
        val existing = File(path).takeIf { it.isFile }?.length() ?: 0L
        if (dir.usableSpace < size - existing + SPACE_RESERVE) {
            pause(id)
            update(id) {
                it.copy(
                    state = JobState.ERROR,
                    error = "Not enough space on ${storage.label}: this download needs ${gb(size - existing)}, " +
                        "${gb(dir.usableSpace)} is free (StreamHub keeps ${gb(SPACE_RESERVE)} free)",
                )
            }
        }
    }

    private fun awaitHandle(hash: String): TorrentHandle {
        val sha1 = Sha1Hash.parseHex(hash)
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            session.find(sha1)?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(100)
        }
        throw IllegalStateException("libtorrent didn't accept the torrent")
    }

    /** A .torrent file from a link (or a file: URL). */
    private fun torrentBytes(source: String): ByteArray = when {
        source.startsWith("file:", ignoreCase = true) -> File(URI(source)).readBytes()
        else -> {
            val response = http.send(HttpRequest.newBuilder(URI(source)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() in 200..299) { "The .torrent link answered ${response.statusCode()}" }
            response.body()
        }
    }

    private fun tick() {
        ticks++
        val now = System.currentTimeMillis()
        for ((id, handle) in handles.entries.toList()) {
            val job = get(id) ?: continue
            if (!handle.isValid) {
                handles.remove(id)
                continue
            }
            // A storage that disappeared (unplugged, unmounted): stop before libtorrent writes anywhere else.
            if (ticks % STORAGE_CHECK_TICKS == 0L) {
                val storage = storages().firstOrNull { it.id == job.storageId }
                if (storage == null || !Storages.isAvailable(storage)) {
                    runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
                    handles.remove(id)
                    runCatching { session.remove(handle) }
                    update(id) {
                        it.copy(state = JobState.STORAGE_UNAVAILABLE, error = storage?.let(Storages::unavailableMessage), downloadRate = 0, uploadRate = 0)
                    }
                    continue
                }
            }
            val status = runCatching { handle.status() }.getOrNull() ?: continue
            if (job.filePath == null) {
                val info = runCatching { handle.torrentFile() }.getOrNull()?.takeIf { it.isValid && it.numFiles() > 0 }
                if (info != null) {
                    metadataArrived(id, handle, info)
                } else {
                    update(id) { it.copy(state = if (job.paused) JobState.PAUSED else JobState.METADATA, peers = status.numPeers(), seeds = status.numSeeds()) }
                }
                continue
            }
            val index = (job.fileIdx.takeIf { it >= 0 } ?: indexOf(handle, job)) ?: continue
            val done = runCatching { handle.fileProgress()[index] }.getOrDefault(job.downloadedBytes)
            val (lastBytes, lastAt) = lastProgress[id] ?: (done to now)
            if (done != lastBytes || !lastProgress.containsKey(id)) lastProgress[id] = done to now
            val sinceProgress = now - (lastProgress[id]?.second ?: now)
            if (job.totalBytes > 0 && done >= job.totalBytes) {
                complete(id, handle)
                continue
            }
            val state = when {
                job.paused -> JobState.PAUSED
                status.state() == TorrentStatus.State.CHECKING_FILES || status.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> JobState.CHECKING
                status.errorCode()?.isError == true -> JobState.ERROR
                status.numPeers() == 0 && done == 0L -> JobState.CONNECTING
                status.downloadPayloadRate() == 0 && sinceProgress > STALL_MS -> JobState.STALLED
                status.numPeers() == 0 -> JobState.STALLED
                else -> JobState.DOWNLOADING
            }
            update(id) {
                it.copy(
                    downloadedBytes = done,
                    downloadRate = status.downloadPayloadRate().toLong(),
                    uploadRate = status.uploadPayloadRate().toLong(),
                    peers = status.numPeers(),
                    seeds = status.numSeeds(),
                    state = state,
                    error = if (state == JobState.ERROR) status.errorCode()?.message else null,
                )
            }
            if (ticks % RESUME_SAVE_TICKS == 0L) runCatching { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
        }
        // Downloads waiting for their drive start again once it is back.
        if (ticks % STORAGE_CHECK_TICKS == 0L) {
            list().filter { it.state == JobState.STORAGE_UNAVAILABLE && !it.paused && !handles.containsKey(it.id) }.forEach { job ->
                val storage = storages().firstOrNull { it.id == job.storageId } ?: return@forEach
                if (Storages.isAvailable(storage)) launch(job.id)
            }
        }
        if (ticks % SAVE_TICKS == 0L) save()
    }

    private fun complete(id: String, handle: TorrentHandle) {
        handles.remove(id)
        runCatching { session.remove(handle) }
        File(resumeDir, "$id.resume").delete()
        val job = get(id) ?: return
        val file = job.filePath?.let(::File)
        if (file == null || !file.isFile || file.length() != job.totalBytes) {
            update(id) { it.copy(state = JobState.ERROR, error = "The finished file is missing or incomplete") }
            return
        }
        val finished = update(id) {
            it.copy(state = JobState.COMPLETED, downloadedBytes = it.totalBytes, downloadRate = 0, uploadRate = 0, peers = 0, seeds = 0,
                error = null, completedAt = System.currentTimeMillis())
        } ?: return
        save()
        workers.execute {
            val media = MediaProbe.probe(file)
            val withMedia = if (media != null) update(id) { it.copy(media = media) } else finished
            save()
            withMedia?.let(onCompleted)
        }
    }

    private fun indexOf(handle: TorrentHandle, job: Job): Int? {
        val info = handle.torrentFile() ?: return null
        val files = info.files()
        return (0 until files.numFiles()).firstOrNull { files.fileName(it) == job.fileName }
    }

    private fun largestVideo(info: TorrentInfo): Int {
        val files = info.files()
        val all = (0 until files.numFiles())
        return all.filter { files.fileName(it).substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS }.maxByOrNull { files.fileSize(it) }
            ?: all.maxBy { files.fileSize(it) }
    }

    private fun update(id: String, change: (Job) -> Job): Job? {
        val changed = synchronized(jobs) {
            val current = jobs[id]
            val next = if (current == null) {
                change(Job(id = id, source = "", fileIdx = -1, title = "", storageId = "", createdAt = 0)).takeIf { it.source.isNotEmpty() }
            } else {
                change(current)
            } ?: return null
            jobs[id] = next
            next
        }
        if (changed.state != JobState.DOWNLOADING && changed.state != JobState.CONNECTING && changed.state != JobState.STALLED) save()
        return changed
    }

    private fun load() {
        if (!jobsFile.isFile) return
        val saved = runCatching { ServerJson.decodeFromString<List<Job>>(jobsFile.readText()) }.getOrDefault(emptyList())
        synchronized(jobs) {
            saved.forEach { job ->
                jobs[job.id] = if (job.state == JobState.COMPLETED || job.paused) job
                else job.copy(state = JobState.QUEUED, downloadRate = 0, uploadRate = 0, peers = 0, seeds = 0)
            }
        }
    }

    private fun save() {
        val text = synchronized(jobs) { ServerJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Job.serializer()), jobs.values.toList()) }
        runCatching { writeAtomically(jobsFile, text.toByteArray()) }
    }

    /** Peer addresses written into a magnet link (x.pe=host:port). */
    private fun peersFrom(source: String): List<TcpEndpoint> {
        if (!source.startsWith("magnet:", ignoreCase = true)) return emptyList()
        return source.substringAfter('?', "").split('&')
            .filter { it.startsWith("x.pe=") }
            .mapNotNull { part ->
                val value = URLDecoder.decode(part.removePrefix("x.pe="), Charsets.UTF_8)
                val host = value.substringBeforeLast(':')
                val port = value.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
                runCatching { TcpEndpoint(host, port) }.getOrNull()
            }
    }

    private fun withTrackers(magnet: String): String =
        magnet + DEFAULT_TRACKERS.filterNot { magnet.contains(java.net.URLEncoder.encode(it, Charsets.UTF_8)) }
            .joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")
        const val SPACE_RESERVE = 1L shl 30
        private const val STALL_MS = 30_000L
        private const val STORAGE_CHECK_TICKS = 5L
        private const val RESUME_SAVE_TICKS = 60L
        private const val SAVE_TICKS = 10L
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
        )

        fun gb(bytes: Long): String = "%.1f GB".format(bytes.coerceAtLeast(0) / (1024.0 * 1024 * 1024))
    }
}

/** Writes through a temporary file, so a crash never leaves half a file behind. */
fun writeAtomically(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
