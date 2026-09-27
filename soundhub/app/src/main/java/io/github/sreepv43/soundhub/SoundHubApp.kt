package io.github.sreepv43.soundhub

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.sreepv43.soundhub.data.Settings
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.MusicDownloads
import io.github.sreepv43.soundhub.library.SearchFolder
import io.github.sreepv43.soundhub.library.SearchSession
import io.github.sreepv43.soundhub.library.SearchTrack
import io.github.sreepv43.soundhub.player.PlaybackController
import io.github.sreepv43.soundhub.player.QueueItem
import io.github.sreepv43.soundhub.player.SoundHubDataSourceFactory
import io.github.sreepv43.soundhub.player.toQueueItem
import io.github.sreepv43.soundhub.service.TransferService
import io.github.sreepv43.soundhub.slsk.ConnectionState
import io.github.sreepv43.soundhub.slsk.SoulseekClient
import io.github.sreepv43.soundhub.slsk.Upnp
import io.github.sreepv43.soundhub.ui.components.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.io.File

class SoundHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** A drive or folder downloads can go to. */
data class MusicFolder(val dir: File, val label: String, val freeBytes: Long)

/** Hand-rolled dependency container shared by the activity and the services. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = Settings(context)

    val client = SoulseekClient(
        listenPorts = settings.listenPort.value..(settings.listenPort.value + 5),
        description = "SoundHub music player",
        log = { Log.i("SoundHub", it) },
    )
    val library = LibraryStore(File(context.filesDir, "library.json"))
    val downloads = MusicDownloads(client, library, ::musicFolder, ioScope)
    val search = SearchSession(client, appScope)
    val atmosSearch = SearchSession(client, appScope)
    val playback = PlaybackController(context, this)

    private val _portStatus = MutableStateFlow<String?>(null)

    /** What the router said about opening the listening port. */
    val portStatus: StateFlow<String?> = _portStatus.asStateFlow()

    fun start() {
        ioScope.launch { library.pruneMissing() }
        if (settings.username.value.isNotBlank() && settings.password.value.isNotEmpty()) {
            signIn(settings.username.value, settings.password.value)
        }
        appScope.launch {
            client.state.filterIsInstance<ConnectionState.Connected>().distinctUntilChanged().collect {
                if (settings.upnp.value) openPort()
            }
        }
    }

    fun signIn(username: String, password: String) {
        settings.username.set(username.trim())
        settings.password.set(password)
        appScope.launch { client.connect(username.trim(), password) }
    }

    /** Runs a search (remembered for one-press repeats); the Atmos page adds "atmos" to the words. */
    fun startSearch(text: String, atmos: Boolean = false) {
        val query = text.trim()
        if (query.isEmpty()) return
        if (atmos) {
            atmosSearch.start(if (query.contains("atmos", ignoreCase = true)) query else "$query atmos")
        } else {
            settings.addRecentSearch(query)
            search.start(query)
        }
        if (client.state.value !is ConnectionState.Connected) {
            toast(context, "Sign in to Soulseek first: press Left for the menu, then Settings.")
        }
    }

    /** True when signed in; otherwise says how to sign in. */
    fun requireSignIn(): Boolean {
        if (client.state.value is ConnectionState.Connected) return true
        toast(context, "Sign in to Soulseek first: press Left for the menu, then Settings.")
        return false
    }

    fun signOut() {
        settings.password.set("")
        client.disconnect()
    }

    fun openPort() {
        val port = client.listenPort ?: return
        ioScope.launch {
            _portStatus.value = "Asking the router to open port $port…"
            _portStatus.value = try {
                val mapping = Upnp.mapPort(port, "SoundHub")
                "Port $port is open on the router (${mapping.router}), so other users can reach you directly"
            } catch (e: Exception) {
                "The router didn't open port $port (${e.message}). Results still come from users who are reachable; " +
                    "forwarding TCP $port to this device on the router brings more."
            }
        }
    }

    /** Built-in storage first, then SD cards and USB drives, then private storage. */
    fun musicFolders(): List<MusicFolder> {
        val external = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MUSIC).filterNotNull()
        val folders = external.mapIndexed { i, dir -> dir to if (i == 0) "Built-in storage" else "Drive ${i + 1} (SD card / USB)" } +
            (File(context.filesDir, "music") to "App-private storage")
        return folders.map { (dir, label) ->
            dir.mkdirs()
            MusicFolder(dir, label, runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L))
        }
    }

    fun musicFolder(): File {
        val chosen = settings.musicFolder.value
        if (chosen.isNotEmpty()) File(chosen).takeIf { it.isDirectory || it.mkdirs() }?.let { return it }
        return musicFolders().first().dir
    }

    /** Plays a search result folder from [start] on; songs not in the library are streamed while they download. */
    fun playFolder(folder: SearchFolder, start: SearchTrack) {
        val playable = folder.tracks.filter { it.info.playable }
        val from = playable.indexOf(start).coerceAtLeast(0)
        val queue = playable.drop(from).map(::queueItemFor)
        playback.play(queue)
        TransferService.start(context)
    }

    /** Downloads songs to the library without playing them. */
    fun download(tracks: List<SearchTrack>) {
        tracks.forEach { track -> if (library.find(track.username, track.file.filename) == null) downloads.fetch(track) }
        TransferService.start(context)
    }

    fun playLibrary(tracks: List<LibraryTrack>, start: LibraryTrack = tracks.first()) {
        val playable = tracks.filter { it.info.playable }
        playback.play(playable.map { it.toQueueItem() }, playable.indexOf(start).coerceAtLeast(0))
    }

    private fun queueItemFor(track: SearchTrack): QueueItem {
        library.find(track.username, track.file.filename)?.takeIf { File(it.path).isFile }?.let { return it.toQueueItem() }
        val transfer = downloads.fetch(track)
        return QueueItem(
            id = LibraryStore.id(track.username, track.file.filename),
            username = track.username,
            remotePath = track.file.filename,
            title = track.name.title,
            album = track.name.album,
            artist = track.name.artist,
            info = track.info,
            uri = SoundHubDataSourceFactory.uriFor(transfer.id, track.file.filename).toString(),
            transferId = transfer.id,
        )
    }
}

val Context.container: AppContainer
    get() = (applicationContext as SoundHubApp).container
