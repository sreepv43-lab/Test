package io.github.sreepv43.streamhub

import android.app.Application
import android.content.Context
import io.github.sreepv43.streamhub.addon.AddonClient
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.data.Settings
import io.github.sreepv43.streamhub.data.WatchHistory
import io.github.sreepv43.streamhub.download.DownloadRepository
import io.github.sreepv43.streamhub.download.DownloadStorage
import io.github.sreepv43.streamhub.download.Downloader
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class StreamHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
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
    val downloader = Downloader(context, mediaHttp, downloads, storage)
}

val Context.container: AppContainer
    get() = (applicationContext as StreamHubApp).container
