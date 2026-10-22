package io.github.sreepv43.streamhub.server

import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Everything playable on the server: finished downloads (with the catalogue ids they were downloaded
 * for), plus videos found in the storage folders (e.g. copied there earlier), identified from their
 * file names. The scan runs in the background and is kept in library.json, so asking is instant.
 */
class Library(
    private val dataDir: File,
    private val storages: () -> List<StorageDef>,
    private val jobs: () -> List<Job>,
    private val minVideoBytes: Long = MIN_VIDEO_BYTES,
) {
    private val file = File(dataDir, "library.json")
    private var scanned: List<ScannedFile> = load()
    private val scanner = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "library-scan").apply { isDaemon = true } }

    fun start() {
        scanner.scheduleWithFixedDelay({ runCatching { scan() } }, 0, SCAN_EVERY_MINUTES, TimeUnit.MINUTES)
    }

    fun stop() {
        scanner.shutdownNow()
    }

    /** Asks for a scan now (in the background). */
    fun requestScan() {
        scanner.execute { runCatching { scan() } }
    }

    fun items(): List<LibraryItem> {
        val all = storages()
        fun label(storageId: String) = all.firstOrNull { it.id == storageId }?.label ?: "Server"
        fun available(storageId: String, path: String) =
            all.firstOrNull { it.id == storageId }?.let(Storages::isAvailable) == true && File(path).isFile
        val downloads = jobs().filter { it.state == JobState.COMPLETED && it.filePath != null }.map { job ->
            LibraryItem(
                id = job.id,
                title = job.title,
                fileName = job.fileName ?: File(job.filePath!!).name,
                sizeBytes = job.totalBytes,
                metaId = job.metaId,
                type = job.type,
                videoId = job.videoId,
                media = job.media,
                storageLabel = label(job.storageId),
                available = available(job.storageId, job.filePath!!),
                fromDownload = true,
            )
        }
        val found = synchronized(this) { scanned }.map { f ->
            LibraryItem(
                id = f.id,
                title = f.parsedTitle ?: File(f.path).nameWithoutExtension,
                fileName = File(f.path).name,
                sizeBytes = f.sizeBytes,
                parsedTitle = f.parsedTitle,
                year = f.year,
                season = f.season,
                episode = f.episode,
                media = f.media,
                storageLabel = label(f.storageId),
                available = available(f.storageId, f.path),
                fromDownload = false,
            )
        }
        return downloads + found
    }

    /** The file behind a library id, if it can be read now. */
    fun fileFor(id: String): File? {
        jobs().firstOrNull { it.id == id && it.state == JobState.COMPLETED }?.filePath?.let { path ->
            return File(path).takeIf { it.isFile }
        }
        return synchronized(this) { scanned }.firstOrNull { it.id == id }?.let { File(it.path) }?.takeIf { it.isFile }
    }

    /**
     * Looks through the connected storages for video files. Files already known keep their details;
     * a storage that isn't connected keeps its entries (shown as unavailable) until it is back.
     */
    fun scan() {
        val known = synchronized(this) { scanned }.associateBy { it.path }
        val downloaded = jobs().mapNotNull { it.filePath }.toSet()
        val result = mutableListOf<ScannedFile>()
        for (storage in storages()) {
            if (!Storages.isAvailable(storage)) {
                result += known.values.filter { it.storageId == storage.id }
                continue
            }
            File(storage.path).walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in Downloads.VIDEO_EXTENSIONS && it.length() >= minVideoBytes }
                .filter { it.path !in downloaded && !it.name.contains("sample", ignoreCase = true) }
                .forEach { video ->
                    val old = known[video.path]
                    result += if (old != null && old.sizeBytes == video.length() && old.modified == video.lastModified()) old else {
                        val parsed = ReleaseName.parse(video.name)
                        ScannedFile(
                            id = idFor(video.path),
                            path = video.path,
                            storageId = storage.id,
                            sizeBytes = video.length(),
                            modified = video.lastModified(),
                            parsedTitle = parsed.title,
                            year = parsed.year,
                            season = parsed.season,
                            episode = parsed.episode,
                        )
                    }
                }
        }
        synchronized(this) { scanned = result }
        save()
        // Video details for new files, one at a time (ffprobe reads only the start of the file).
        if (MediaProbe.available) {
            result.filter { it.media == null }.forEach { entry ->
                val media = MediaProbe.probe(File(entry.path)) ?: return@forEach
                synchronized(this) { scanned = scanned.map { if (it.id == entry.id) it.copy(media = media) else it } }
            }
            save()
        }
    }

    private fun load(): List<ScannedFile> =
        runCatching { ServerJson.decodeFromString(ListSerializer(ScannedFile.serializer()), file.readText()) }.getOrDefault(emptyList())

    private fun save() {
        val text = ServerJson.encodeToString(ListSerializer(ScannedFile.serializer()), synchronized(this) { scanned })
        runCatching { writeAtomically(file, text.toByteArray()) }
    }

    companion object {
        const val MIN_VIDEO_BYTES = 50L * 1024 * 1024
        private const val MAX_DEPTH = 8
        private const val SCAN_EVERY_MINUTES = 15L

        fun idFor(path: String): String =
            "f" + MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
