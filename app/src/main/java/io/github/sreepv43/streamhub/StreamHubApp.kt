package io.github.sreepv43.streamhub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.sreepv43.streamhub.addon.AddonClient
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.PlaybackTarget
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamRanking
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.data.CrashReports
import io.github.sreepv43.streamhub.data.IntroMemory
import io.github.sreepv43.streamhub.data.Library
import io.github.sreepv43.streamhub.data.HomeCache
import io.github.sreepv43.streamhub.sync.StremioImport
import io.github.sreepv43.streamhub.sync.ImdbImport
import io.github.sreepv43.streamhub.data.WatchNext
import io.github.sreepv43.streamhub.sync.Trakt
import io.github.sreepv43.streamhub.data.Settings
import io.github.sreepv43.streamhub.update.Updater
import io.github.sreepv43.streamhub.data.WatchHistory
import io.github.sreepv43.streamhub.download.DownloadRepository
import io.github.sreepv43.streamhub.download.DownloadStorage
import io.github.sreepv43.streamhub.download.Downloader
import io.github.sreepv43.streamhub.download.HomeServer
import io.github.sreepv43.streamhub.download.MediaAvailability
import io.github.sreepv43.streamhub.torrent.CacheChoice
import io.github.sreepv43.streamhub.torrent.CachePolicy
import io.github.sreepv43.streamhub.torrent.CacheRules
import io.github.sreepv43.streamhub.torrent.CacheVolume
import io.github.sreepv43.streamhub.torrent.TorrentEngine
import io.github.sreepv43.streamhub.torrent.TorrentHttpServer
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

class StreamHubApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(BackgroundWatcher())
    }

    /** Lets the torrent engine rest once no StreamHub screen has been visible for a while. */
    private inner class BackgroundWatcher : ActivityLifecycleCallbacks {
        private var started = 0
        private val handler = Handler(Looper.getMainLooper())
        private val release = Runnable { if (started == 0) container.torrents.releaseIdle() }

        override fun onActivityStarted(activity: Activity) {
            started++
            handler.removeCallbacks(release)
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) handler.postDelayed(release, BACKGROUND_RELEASE_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private companion object {
        const val BACKGROUND_RELEASE_MS = 30_000L
    }

    /** Poster/backdrop loading tuned for TV boxes with little RAM and slow storage. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.mediaHttp }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .build()
}

/** Hand-rolled dependency container shared by activities, view models and the download service. */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
        .build()

    /** For video playback and downloads: large bodies must never go through the HTTP cache. */
    val mediaHttp: OkHttpClient = http.newBuilder().cache(null).build()

    val addonClient = AddonClient(http)
    val addons = AddonRepository(context, addonClient)
    val settings = Settings(context)
    val history = WatchHistory(context)
    val introMemory = IntroMemory(context)
    val library = Library(context)
    val homeCache = HomeCache(File(context.filesDir, "home-rows.json"))
    val storage = DownloadStorage(context)
    val downloads = DownloadRepository(context)

    /**
     * Built-in BitTorrent engine; torrent files are exposed to the player/downloader over local HTTP.
     * Its data goes to the torrent cache location chosen in Settings → Torrents (with a USB drive
     * chosen, only there: never to internal storage).
     */
    val torrents = TorrentEngine(
        cacheDirs = { torrentCacheDirs(context) },
        fetchTorrentFile = { url -> fetchBytes(context, url) },
        chooseCache = { chooseTorrentCache() },
        cacheRules = { dir -> torrentCacheRules(dir) },
        // Shown like a crash report on the next start, so the cause can be found.
        onStall = { report -> CrashReports.record(context, report) },
        downloadWholeFile = { settings.torrentDownloadWholeFile.value },
    )

    /** Where new torrent data goes now (see [CachePolicy.choose]). */
    fun chooseTorrentCache(volumes: List<CacheVolume> = cacheVolumesForMode()): CacheChoice = CachePolicy.choose(
        mode = settings.torrentCacheMode.value,
        usbId = settings.torrentCacheVolumeId.value.ifEmpty { null },
        usbLabel = settings.torrentCacheVolumeLabel.value.ifEmpty { null },
        volumes = volumes,
    )

    /** With "Internal storage" chosen, the drives aren't looked at at all (a stuck drive can't matter). */
    private fun cacheVolumesForMode(): List<CacheVolume> =
        if (settings.torrentCacheMode.value == CachePolicy.INTERNAL) listOf(storage.internalVolume()) else storage.cacheVolumes()

    /** Every torrent cache folder that exists: on each volume, plus where older versions kept it. */
    private fun torrentCacheDirs(context: Context): List<File> {
        // (Only internal storage: asking Android for the app's folders on USB drives can hang on some TVs.)
        val legacy = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, "torrent-cache") }
        val internal = (listOfNotNull(storage.internalVolume().dir) + legacy).distinct().filter { it.isDirectory }
        // With internal storage chosen the drives are left alone; otherwise the last known drives
        // (without waiting for a drive check, and without touching them: they were checked then).
        if (settings.torrentCacheMode.value == CachePolicy.INTERNAL) return internal
        return (internal + storage.cacheVolumes(waitMs = 0).filter { it.removable }.mapNotNull { it.dir }).distinct()
    }

    private fun torrentCacheRules(dir: File): CacheRules {
        val volume = (listOf(storage.internalVolume()) + storage.cacheVolumes(waitMs = 0)).firstOrNull { it.dir == dir }
            ?: return CacheRules()
        if (!settings.torrentCacheCleanup.value) return CacheRules()
        val targetBytes = if (volume.removable) {
            settings.torrentCacheUsbTargetGb.value * CachePolicy.GB
        } else {
            when (val gb = settings.torrentCacheLimitGb.value) {
                0 -> 1L // No cache: anything nobody is watching goes.
                in Int.MIN_VALUE..-1 -> 0L // Unlimited.
                else -> gb * CachePolicy.GB
            }
        }
        return CacheRules(targetBytes = targetBytes, reserveBytes = CachePolicy.reserveFor(volume))
    }

    val torrentServer = TorrentHttpServer(torrents)

    /** Local torrent streams may wait for peers, so they get generous timeouts. */
    val torrentHttp: OkHttpClient = mediaHttp.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Turns the logical `torrent:?…` URLs stored in downloads/history into local HTTP URLs. */
    fun playableUrl(url: String): String =
        TorrentLinks.parseLogicalUrl(url)?.let { (source, file) -> torrentServer.urlFor(source, file) } ?: url

    /**
     * Starts fetching the torrents among the streams "Play best" would pick first, so playing one
     * starts sooner (the very first one also gets its opening minutes). Settings: prepare torrents.
     */
    fun prepareTorrents(streams: List<Stream>) {
        if (!settings.prepareTorrents.value) return
        StreamRanking.rank(streams, settings.maxResolution.value, settings.surroundPassthrough.value).take(PREPARED_STREAMS).forEachIndexed { i, stream ->
            val target = StreamResolver.resolve(stream) as? PlaybackTarget.Torrent ?: return@forEachIndexed
            torrents.prefetch(target.source, target.fileIdx, warmStart = i == 0)
        }
    }

    /** The paired StreamHub Server (Settings → Downloads → Home Server), if any. */
    val homeServer = HomeServer(http, settings, downloads).also { it.start() }

    /** Downloaded copies of titles, wherever they are (Play downloaded, poster badges). */
    val availability = MediaAvailability(downloads, homeServer, storage)

    val downloader = Downloader(
        context, torrentHttp, downloads, storage, settings, ::playableUrl,
        // A torrent download keeps its temporary torrent data on the same drive as the file (in a
        // hidden folder next to it), never on internal storage, and deletes it when done.
        torrentStarting = { item ->
            val source = TorrentLinks.parseLogicalUrl(item.url)?.first
            if (source != null && item.location.kind == DownloadLocation.Kind.DIRECTORY) {
                torrents.useCacheDir(source, File(item.location.value, ".torrent-cache"))
            }
        },
        torrentDone = { item -> TorrentLinks.parseLogicalUrl(item.url)?.first?.let(torrents::discard) },
        homeServer = homeServer,
    )

    val updater = Updater(context, mediaHttp)
    val trakt = Trakt(context, http, library, addons)
    val stremioImport = StremioImport(http, addons, library, history)
    val imdbImport = ImdbImport(http, addons, library)
    val watchNext = WatchNext(context, history, library, settings).also { it.start() }

    private companion object {
        const val PREPARED_STREAMS = 3
    }

    private fun fetchBytes(context: Context, url: String): ByteArray {
        val uri = Uri.parse(url)
        return when (uri.scheme) {
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Cannot open $url")
            else -> http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                response.body?.bytes() ?: throw IOException("Empty response")
            }
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as StreamHubApp).container
