package io.github.sreepv43.streamhub.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.sreepv43.streamhub.data.Settings
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Runs downloads with HTTP range requests so that they survive pauses, network drops and app
 * restarts. A foreground [DownloadService] keeps the process alive while anything is running.
 */
class Downloader(
    private val context: Context,
    http: OkHttpClient,
    private val repository: DownloadRepository,
    private val storage: DownloadStorage,
    private val settings: Settings,
    /** Maps stored URLs (e.g. logical torrent URLs) to the URL to fetch right now. */
    private val resolveUrl: (String) -> String = { it },
) {
    // Downloads can take hours: no read timeout, but notice dead connections.
    private val http = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    // Cancelling the coroutine alone would wait for a blocked socket read; cancel the call too.
    private val calls = ConcurrentHashMap<String, Call>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    val items get() = repository.items

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _waitingForWifi = MutableStateFlow(false)
    /** Downloads are queued but "Wi-Fi only" is on and the connection is metered (mobile data). */
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    init {
        // Start (or hold) queued downloads when the connection or the "Wi-Fi only" setting changes.
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onNetworkChanged()
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onNetworkChanged()
                    override fun onLost(network: Network) = onNetworkChanged()
                },
            )
        }
        scope.launch { settings.downloadsWifiOnly.flow.collect { onNetworkChanged() } }
    }

    private fun networkAllowed(): Boolean {
        if (!settings.downloadsWifiOnly.value) return true
        val cm = connectivity ?: return true
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun onNetworkChanged() {
        if (networkAllowed()) schedule() else holdRunning()
    }

    /** Stops running downloads without pausing them, so they continue on Wi-Fi. */
    private fun holdRunning() {
        val held = synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.keys.toList().also { jobs.clear() }
        }
        held.forEach { id ->
            calls.remove(id)?.cancel()
            repository.update(id) { it.copy(status = DownloadItem.Status.QUEUED) }
        }
        refreshActive()
        _waitingForWifi.value = repository.items.value.any { it.status == DownloadItem.Status.QUEUED }
    }

    fun enqueue(
        title: String,
        subtitle: String?,
        poster: String?,
        metaId: String?,
        type: String?,
        videoId: String?,
        url: String,
        headers: Map<String, String>,
        suggestedFileName: String?,
        location: DownloadLocation,
    ): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            title = title,
            subtitle = subtitle,
            poster = poster,
            metaId = metaId,
            type = type,
            videoId = videoId,
            url = url,
            headers = headers,
            suggestedFileName = suggestedFileName,
            location = location,
        )
        repository.add(item)
        schedule()
        return item
    }

    fun pause(id: String) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        calls.remove(id)?.cancel()
        repository.update(id) { it.copy(status = DownloadItem.Status.PAUSED) }
        refreshActive()
        schedule()
    }

    fun resume(id: String) {
        repository.update(id) { it.copy(status = DownloadItem.Status.QUEUED, error = null) }
        schedule()
    }

    fun remove(id: String, deleteFile: Boolean) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        calls.remove(id)?.cancel()
        val item = repository.get(id)
        repository.remove(id)
        if (deleteFile) item?.fileUri?.let(storage::delete)
        refreshActive()
        schedule()
    }

    /** Continues every paused or failed download. */
    fun resumeAll() {
        repository.items.value
            .filter { it.status == DownloadItem.Status.PAUSED || it.status == DownloadItem.Status.FAILED }
            .forEach { item -> repository.update(item.id) { it.copy(status = DownloadItem.Status.QUEUED, error = null) } }
        schedule()
    }

    /** Starts a (failed) download again from the beginning in another place. */
    fun moveTo(id: String, location: DownloadLocation) {
        synchronized(jobs) { jobs.remove(id) }?.cancel()
        calls.remove(id)?.cancel()
        val old = repository.get(id) ?: return
        old.fileUri?.let { runCatching { storage.delete(it) } }
        repository.update(id) {
            it.copy(
                location = location,
                fileUri = null,
                downloadedBytes = 0,
                totalBytes = -1,
                status = DownloadItem.Status.QUEUED,
                error = null,
            )
        }
        refreshActive()
        schedule()
    }

    /** Pauses everything, e.g. when the system stops the foreground service. */
    fun pauseAll() {
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        calls.values.forEach { it.cancel() }
        calls.clear()
        repository.items.value
            .filter { it.status == DownloadItem.Status.RUNNING || it.status == DownloadItem.Status.QUEUED }
            .forEach { item -> repository.update(item.id) { it.copy(status = DownloadItem.Status.PAUSED) } }
        refreshActive()
    }

    /** Called when the UI starts: downloads interrupted by a process death continue. */
    fun resumeInterrupted() {
        repository.items.value
            .filter { it.status == DownloadItem.Status.RUNNING && !synchronized(jobs) { it.id in jobs } }
            .forEach { item -> repository.update(item.id) { it.copy(status = DownloadItem.Status.QUEUED) } }
        schedule()
    }

    private fun schedule() {
        if (!networkAllowed()) {
            _waitingForWifi.value = repository.items.value.any { it.status == DownloadItem.Status.QUEUED }
            return
        }
        _waitingForWifi.value = false
        val toStart = synchronized(jobs) {
            val free = MAX_PARALLEL - jobs.size
            if (free <= 0) return
            repository.items.value
                .filter { it.status == DownloadItem.Status.QUEUED && it.id !in jobs }
                .reversed() // oldest first
                .take(free)
                .onEach { item ->
                    repository.update(item.id) { it.copy(status = DownloadItem.Status.RUNNING) }
                    jobs[item.id] = scope.launch { runWithRetries(item.id) }
                }
        }
        refreshActive()
        if (toStart.isNotEmpty()) startService()
    }

    private suspend fun runWithRetries(id: String) {
        var attempt = 0
        try {
            while (true) {
                try {
                    download(id)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cancelling the HTTP call surfaces as an IOException; treat it as cancellation.
                    coroutineContext.ensureActive()
                    attempt++
                    Log.w(TAG, "Download $id failed (attempt $attempt)", e)
                    if (attempt >= MAX_ATTEMPTS || e is PermanentFailure) {
                        repository.update(id) {
                            it.copy(status = DownloadItem.Status.FAILED, error = e.message ?: e.javaClass.simpleName)
                        }
                        break
                    }
                    delay(2_000L * attempt * attempt)
                }
            }
        } finally {
            synchronized(jobs) { jobs.remove(id) }
            refreshActive()
            if (coroutineContext[Job]?.isCancelled != true) schedule()
        }
    }

    private suspend fun download(id: String) {
        val item = repository.get(id) ?: return
        storage.unavailableReason(item.location)?.let { reason ->
            throw PermanentFailure("Can't save to ${item.location.label}: $reason")
        }
        val existing = item.fileUri?.takeIf(storage::exists)?.let(storage::length) ?: 0L

        val request = Request.Builder().url(resolveUrl(item.url)).apply {
            item.headers.forEach { (name, value) -> header(name, value) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = http.newCall(request)
        calls[id] = call

        try {
            call.execute().use { response ->
                if (response.code == 416 && existing > 0) {
                    repository.update(id) {
                        it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = existing, totalBytes = existing)
                    }
                    return
                }
                if (!response.isSuccessful) {
                    val message = "Server returned HTTP ${response.code}"
                    throw if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                        PermanentFailure(message)
                    } else IOException(message)
                }
                val body = response.body ?: throw IOException("Empty response")
                val append = existing > 0 && response.code == 206
                val total = if (response.code == 206) {
                    FileNames.totalFromContentRange(response.header("Content-Range"))
                        ?: body.contentLength().takeIf { it >= 0 }?.plus(existing)
                } else {
                    body.contentLength().takeIf { it >= 0 }
                } ?: -1L

                val fileName = item.fileName ?: FileNames.choose(
                    suggested = item.suggestedFileName,
                    contentDisposition = response.header("Content-Disposition"),
                    url = if (TorrentLinks.isLogicalUrl(item.url)) "" else response.request.url.toString(),
                    title = listOfNotNull(item.title, item.subtitle).joinToString(" "),
                    mime = response.header("Content-Type"),
                )
                val fileUri = item.fileUri?.takeIf(storage::exists) ?: storage.create(item.location, fileName)
                var done = if (append) existing else 0L
                repository.update(id) {
                    it.copy(
                        fileUri = fileUri,
                        fileName = fileName,
                        totalBytes = total,
                        downloadedBytes = done,
                        status = DownloadItem.Status.RUNNING,
                        error = null,
                    )
                }

                storage.openOutput(fileUri, append).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastUiUpdate = 0L
                    var lastPersist = SystemClock.elapsedRealtime()
                    var windowStart = lastPersist
                    var windowBytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        // Speed limit (shared by the downloads running at the same time).
                        val limit = settings.downloadSpeedLimitKb.value * 1024L / activeCount.value.coerceAtLeast(1)
                        if (limit > 0) {
                            windowBytes += read
                            val ahead = windowBytes * 1000 / limit - (SystemClock.elapsedRealtime() - windowStart)
                            if (ahead > 0) delay(ahead)
                            if (SystemClock.elapsedRealtime() - windowStart > 2_000) {
                                windowStart = SystemClock.elapsedRealtime()
                                windowBytes = 0
                            }
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUiUpdate > 500) {
                            val persist = now - lastPersist > 10_000
                            if (persist) lastPersist = now
                            repository.update(id, persist) { it.copy(downloadedBytes = done) }
                            lastUiUpdate = now
                        }
                    }
                    out.flush()
                }
                if (total > 0 && done < total) throw IOException("Connection closed early ($done of $total bytes)")
                repository.update(id) {
                    it.copy(
                        status = DownloadItem.Status.COMPLETED,
                        downloadedBytes = done,
                        totalBytes = if (total > 0) total else done,
                    )
                }
            }
        } finally {
            calls.remove(id, call)
        }
    }

    private fun refreshActive() {
        _activeCount.value = synchronized(jobs) { jobs.size }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            // Starting a foreground service from the background is not allowed on Android 12+;
            // downloads keep running while the app is open and resume on next launch.
            Log.w(TAG, "Could not start download service", e)
        }
    }

    private class PermanentFailure(message: String) : IOException(message)

    private companion object {
        const val TAG = "Downloader"
        const val MAX_PARALLEL = 2
        const val MAX_ATTEMPTS = 6
        const val BUFFER_SIZE = 256 * 1024
    }
}
