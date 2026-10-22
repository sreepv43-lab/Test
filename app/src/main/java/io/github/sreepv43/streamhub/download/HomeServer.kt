package io.github.sreepv43.streamhub.download

import io.github.sreepv43.streamhub.data.Settings
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// The StreamHub Server API (see server/), as the app uses it.

@Serializable
data class ServerHello(val app: String = "", val name: String = "", val version: String = "")

@Serializable
data class ServerStorage(val id: String, val label: String, val path: String = "", val available: Boolean, val freeBytes: Long = 0, val totalBytes: Long = 0)

@Serializable
data class ServerStatus(
    val name: String,
    val version: String = "",
    val storages: List<ServerStorage> = emptyList(),
    val defaultStorage: String? = null,
    val smbPath: String? = null,
)

@Serializable
private data class PairRequest(val code: String, val device: String)

@Serializable
private data class PairResponse(val token: String, val name: String)

@Serializable
private data class ServerError(val error: String = "")

@Serializable
data class ServerNewDownload(
    val source: String,
    val fileIdx: Int = -1,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val storageId: String? = null,
)

@Serializable
data class ServerMedia(
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val hdr: String? = null,
    val audio: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class ServerJob(
    val id: String,
    val source: String = "",
    val fileIdx: Int = -1,
    val title: String = "",
    val subtitle: String? = null,
    val poster: String? = null,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val storageId: String = "",
    val fileName: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val downloadRate: Long = 0,
    val uploadRate: Long = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val state: String = "queued",
    val error: String? = null,
    val paused: Boolean = false,
    val storageLabel: String? = null,
    val createdAt: Long = 0,
    val media: ServerMedia? = null,
)

@Serializable
data class ServerLibraryItem(
    val id: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val media: ServerMedia? = null,
    val storageLabel: String = "",
    val available: Boolean = true,
    val fromDownload: Boolean = false,
)

class HomeServerException(message: String) : IOException(message)

/**
 * The paired Home Server: sends downloads to it and keeps their entries in [DownloadRepository]
 * up to date with the server's real numbers (polled every 2 s while something is downloading, else
 * every 15 s), so they show in My Downloads like the TV's own. Also keeps its library (finished
 * files) for the movie pages. Nothing is ever downloaded on the TV instead.
 */
class HomeServer(
    http: OkHttpClient,
    private val settings: Settings,
    private val repository: DownloadRepository,
) {
    private val http = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cache(null)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val _online = MutableStateFlow<Boolean?>(null)
    /** Whether the server answered the last time (null: not asked yet / not set up). */
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    private val _status = MutableStateFlow<ServerStatus?>(null)
    val status: StateFlow<ServerStatus?> = _status.asStateFlow()

    private val _library = MutableStateFlow<List<ServerLibraryItem>>(emptyList())
    /** Finished files on the server (last known; [online] says whether they can be played now). */
    val library: StateFlow<List<ServerLibraryItem>> = _library.asStateFlow()

    private val refreshNow = MutableStateFlow(0)
    private val sending = java.util.concurrent.atomic.AtomicInteger()

    val configured: Boolean get() = settings.homeServerAddress.value.isNotEmpty() && settings.homeServerToken.value.isNotEmpty()

    fun start() {
        scope.launch {
            var lastLibrary = 0L
            var seen = refreshNow.value
            while (true) {
                if (configured) {
                    val ok = runCatching { sync() }.isSuccess
                    _online.value = ok
                    if (ok && (System.currentTimeMillis() - lastLibrary > LIBRARY_EVERY_MS || refreshNow.value != seen)) {
                        runCatching { _library.value = get("/api/library", ListSerializer(ServerLibraryItem.serializer())) }
                        runCatching { _status.value = get("/api/status", ServerStatus.serializer()) }
                        lastLibrary = System.currentTimeMillis()
                    }
                } else {
                    _online.value = null
                }
                seen = refreshNow.value
                val busy = repository.items.value.any { it.onServer && it.status == DownloadItem.Status.RUNNING || it.onServer && it.status == DownloadItem.Status.QUEUED }
                val wait = if (busy) FAST_MS else SLOW_MS
                // Wakes early when asked to refresh (e.g. a download was just added).
                val until = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < until && refreshNow.value == seen) delay(200)
            }
        }
    }

    /** Asks for fresh numbers (and the library) right away. */
    fun refresh() {
        refreshNow.value++
    }

    /** "192.168.1.100" → "http://192.168.1.100:8095". */
    fun baseUrl(address: String = settings.homeServerAddress.value): String {
        val clean = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "http://" + if (clean.substringAfterLast(']').contains(':')) clean else "$clean:$DEFAULT_PORT"
    }

    /** Connects to the server at [address] with the code it shows; keeps the key it gives. */
    suspend fun pair(address: String, code: String, device: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl(address)
        val hello = runCatching { request(Request.Builder().url("$base/api/hello").build(), ServerHello.serializer(), base) }
            .getOrElse { throw HomeServerException("No StreamHub Server answered at ${base.removePrefix("http://")}. Check the address and that the server is running.") }
        if (hello.app != "streamhub-server") throw HomeServerException("That address isn't a StreamHub Server")
        val body = json.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), device)).toRequestBody(jsonType)
        val paired = request(Request.Builder().url("$base/api/pair").post(body).build(), PairResponse.serializer(), base)
        settings.homeServerAddress.set(address.trim())
        settings.homeServerToken.set(paired.token)
        settings.homeServerName.set(paired.name)
        refresh()
        paired.name
    }

    fun forget() {
        settings.homeServerToken.set("")
        settings.homeServerName.set("")
        _online.value = null
        _status.value = null
        _library.value = emptyList()
    }

    suspend fun loadStatus(): ServerStatus = withContext(Dispatchers.IO) { get("/api/status", ServerStatus.serializer()).also { _status.value = it } }

    /**
     * Sends [item] (a torrent download) to the server. The entry stays in the list either way:
     * if the server can't be reached it fails with the reason (nothing goes to the TV instead).
     */
    fun send(item: DownloadItem) {
        sending.incrementAndGet()
        scope.launch {
          try {
            val torrent = TorrentLinks.parseLogicalUrl(item.url)
            if (torrent == null) {
                repository.update(item.id) {
                    it.copy(status = DownloadItem.Status.FAILED, error = "The Home Server downloads torrents; this source is a direct link. Choose a TV location for it.")
                }
                return@launch
            }
            val request = ServerNewDownload(
                source = torrent.first,
                fileIdx = torrent.second,
                title = item.title,
                subtitle = item.subtitle,
                poster = item.poster,
                metaId = item.metaId,
                type = item.type,
                videoId = item.videoId,
                storageId = item.location.value.ifEmpty { null },
            )
            runCatching {
                val body = json.encodeToString(ServerNewDownload.serializer(), request).toRequestBody(jsonType)
                post("/api/downloads", body, ServerJob.serializer())
            }.onSuccess { job ->
                repository.update(item.id) { withJob(it.copy(remoteId = job.id), job) }
                refresh()
            }.onFailure { e ->
                repository.update(item.id) {
                    it.copy(status = DownloadItem.Status.FAILED, detail = null, error = "Home Server unavailable: ${e.message}")
                }
            }
          } finally {
            sending.decrementAndGet()
          }
        }
    }

    fun pause(item: DownloadItem) = control(item, "pause")

    fun resume(item: DownloadItem) {
        if (item.remoteId == null) {
            // Never reached the server (it was offline): send it now.
            repository.update(item.id) { it.copy(status = DownloadItem.Status.QUEUED, error = null, detail = "Sending to the Home Server…") }
            send(item)
        } else {
            control(item, "resume")
        }
    }

    fun remove(item: DownloadItem, deleteFile: Boolean) {
        val id = item.remoteId ?: return
        scope.launch {
            runCatching { delete("/api/downloads/$id?deleteFile=$deleteFile") }
        }
    }

    /** Where the player streams a server file from, and the key it sends. */
    fun mediaUrl(id: String): String = baseUrl() + "/media/" + id

    fun mediaHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer " + settings.homeServerToken.value)

    private fun control(item: DownloadItem, action: String) {
        val id = item.remoteId ?: return
        scope.launch {
            runCatching { post("/api/downloads/$id/$action", "".toRequestBody(jsonType), ServerJob.serializer()) }
                .onSuccess { job -> repository.update(item.id) { withJob(it, job) } }
                .onFailure { e -> repository.update(item.id) { it.copy(error = "Home Server unavailable: ${e.message}") } }
        }
    }

    /** Brings every server download in the list up to date; adds the server's downloads the list doesn't have. */
    private fun sync() {
        val jobs = get("/api/downloads", ListSerializer(ServerJob.serializer()))
        val byRemote = jobs.associateBy { it.id }
        val local = repository.items.value
        for (item in local.filter { it.onServer && it.remoteId != null }) {
            val job = byRemote[item.remoteId]
            repository.update(item.id, persist = false) { current ->
                if (job == null) {
                    current.copy(status = DownloadItem.Status.FAILED, error = "This download is no longer on the Home Server", downloadRate = 0)
                } else {
                    withJob(current, job)
                }
            }
        }
        // (Not while a download is being sent: its entry gets the server's id only when the answer comes.)
        if (sending.get() > 0) return
        val known = repository.items.value.mapNotNull { it.remoteId }.toSet()
        jobs.filter { it.id !in known }.forEach { job ->
            repository.add(
                withJob(
                    DownloadItem(
                        id = "server-" + job.id,
                        title = job.title,
                        subtitle = job.subtitle,
                        poster = job.poster,
                        metaId = job.metaId,
                        type = job.type,
                        videoId = job.videoId,
                        url = TorrentLinks.logicalUrl(job.source, job.fileIdx),
                        location = DownloadLocation(DownloadLocation.Kind.SERVER, job.storageId, serverLabel(job)),
                        createdAt = job.createdAt,
                        remoteId = job.id,
                    ),
                    job,
                ),
            )
        }
        repository.flush()
    }

    private fun serverLabel(job: ServerJob): String =
        listOfNotNull(settings.homeServerName.value.ifEmpty { "Home Server" }, job.storageLabel).joinToString(" · ")

    /** The server's numbers in the list's terms. */
    private fun withJob(item: DownloadItem, job: ServerJob): DownloadItem {
        val (status, detail) = when (job.state) {
            "queued" -> DownloadItem.Status.QUEUED to "Queued on the Home Server"
            "metadata" -> DownloadItem.Status.RUNNING to "Fetching torrent details"
            "connecting" -> DownloadItem.Status.RUNNING to "Connecting to peers"
            "downloading" -> DownloadItem.Status.RUNNING to null
            "stalled" -> DownloadItem.Status.RUNNING to "Waiting for peers"
            "checking" -> DownloadItem.Status.RUNNING to "Checking files"
            "paused" -> DownloadItem.Status.PAUSED to null
            "completed" -> DownloadItem.Status.COMPLETED to null
            "storage_unavailable" -> DownloadItem.Status.FAILED to "Storage unavailable"
            else -> DownloadItem.Status.FAILED to null
        }
        return item.copy(
            status = status,
            detail = detail,
            error = job.error.takeIf { status == DownloadItem.Status.FAILED || detail != null && job.error != null },
            fileName = job.fileName ?: item.fileName,
            totalBytes = job.totalBytes,
            downloadedBytes = job.downloadedBytes,
            downloadRate = job.downloadRate,
            peers = job.peers,
            seeds = job.seeds,
            location = item.location.copy(label = serverLabel(job)),
        )
    }

    private fun <T> get(path: String, serializer: KSerializer<T>): T =
        request(authorized(Request.Builder().url(baseUrl() + path)).build(), serializer)

    private fun <T> post(path: String, body: okhttp3.RequestBody, serializer: KSerializer<T>): T =
        request(authorized(Request.Builder().url(baseUrl() + path).post(body)).build(), serializer)

    private fun delete(path: String) {
        http.newCall(authorized(Request.Builder().url(baseUrl() + path).delete()).build()).execute().close()
    }

    private fun authorized(builder: Request.Builder) = builder.header("Authorization", "Bearer " + settings.homeServerToken.value)

    private fun <T> request(request: Request, serializer: KSerializer<T>, base: String = baseUrl()): T {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString(ServerError.serializer(), text).error }.getOrNull()?.ifEmpty { null }
                if (response.code == 401) _online.value = false
                throw HomeServerException(message ?: "${base.removePrefix("http://")} answered ${response.code}")
            }
            return json.decodeFromString(serializer, text)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8095
        private const val FAST_MS = 2_000L
        private const val SLOW_MS = 15_000L
        private const val LIBRARY_EVERY_MS = 30_000L
    }
}
