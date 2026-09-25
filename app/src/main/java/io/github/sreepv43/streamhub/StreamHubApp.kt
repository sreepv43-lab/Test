package io.github.sreepv43.streamhub

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.sreepv43.streamhub.addon.AddonClient
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.data.Settings
import io.github.sreepv43.streamhub.data.WatchHistory
import io.github.sreepv43.streamhub.download.DownloadRepository
import io.github.sreepv43.streamhub.download.DownloadStorage
import io.github.sreepv43.streamhub.download.Downloader
import io.github.sreepv43.streamhub.torrent.TorrentEngine
import io.github.sreepv43.streamhub.torrent.TorrentHttpServer
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
        container = AppContainer(this)
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
    val storage = DownloadStorage(context)
    val downloads = DownloadRepository(context)

    /** Built-in BitTorrent engine; torrent files are exposed to the player/downloader over local HTTP. */
    val torrents = TorrentEngine(
        cacheDirs = {
            (ContextCompat.getExternalFilesDirs(context, null).filterNotNull() + context.filesDir)
                .map { File(it, "torrent-cache") } + storage.writableDriveFolders(".torrent-cache")
        },
        fetchTorrentFile = { url -> fetchBytes(context, url) },
    )
    val torrentServer = TorrentHttpServer(torrents)

    /** Local torrent streams may wait for peers, so they get generous timeouts. */
    val torrentHttp: OkHttpClient = mediaHttp.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Turns the logical `torrent:?…` URLs stored in downloads/history into local HTTP URLs. */
    fun playableUrl(url: String): String =
        TorrentLinks.parseLogicalUrl(url)?.let { (source, file) -> torrentServer.urlFor(source, file) } ?: url

    val downloader = Downloader(context, torrentHttp, downloads, storage, ::playableUrl)

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
