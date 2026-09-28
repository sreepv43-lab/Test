package io.github.sreepv43.soundhub

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.data.AppUpdater
import io.github.sreepv43.soundhub.data.CoverCache
import io.github.sreepv43.soundhub.data.CrashLog
import io.github.sreepv43.soundhub.data.LibraryEnricher
import io.github.sreepv43.soundhub.data.Settings
import io.github.sreepv43.soundhub.library.CollectionStore
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.MusicDownloads
import io.github.sreepv43.soundhub.library.PathNames
import io.github.sreepv43.soundhub.library.Release
import io.github.sreepv43.soundhub.library.Releases
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
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.Upnp
import io.github.sreepv43.soundhub.ui.components.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SoundHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog(this).install()
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
    val collection = CollectionStore(File(context.filesDir, "collection.json"))
    val covers = CoverCache()
    val downloads = MusicDownloads(client, library, ::musicFolder, ioScope)
    val search = SearchSession(client, appScope)
    val atmosSearch = SearchSession(client, appScope)
    val playback = PlaybackController(context, this)
    val updater = AppUpdater(context, settings, appScope)

    /** Search results as albums (each with every user's copy), grouped off the main thread. */
    val releases: StateFlow<List<Release>> = search.folders.map(Releases::group).flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())
    val atmosReleases: StateFlow<List<Release>> = atmosSearch.folders
        .map { folders -> Releases.group(folders.filter { it.matches(FormatFilter.ATMOS) }) }
        .flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private val _missing = MutableStateFlow<Set<String>>(emptySet())

    /** Library songs whose file isn't there now (usually: their drive is unplugged). */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** Filters chosen on the Search and Library pages (kept while moving between pages). */
    val searchFilter = MutableStateFlow(MusicFilter())
    val libraryFilter = MutableStateFlow(MusicFilter())

    private val _portStatus = MutableStateFlow<String?>(null)

    /** What the router said about opening the listening port. */
    val portStatus: StateFlow<String?> = _portStatus.asStateFlow()

    /** Set when the listener first plays or downloads something and notifications aren't allowed yet. */
    val notificationRequest = MutableStateFlow(false)

    fun start() {
        // Songs on an unplugged drive stay in the library (shown as unavailable) until the
        // listener removes them; nothing is dropped automatically.
        LibraryEnricher(library, covers, ioScope)
        updater.checkIfDue()
        appScope.launch { library.tracks.collect { refreshMissing() } }
        if (settings.username.value.isNotBlank() && settings.password.value.isNotEmpty()) {
            signIn(settings.username.value, settings.password.value)
        }
        appScope.launch {
            client.state.filterIsInstance<ConnectionState.Connected>().distinctUntilChanged().collect {
                if (settings.upnp.value) openPort()
            }
        }
    }

    /** Looks again for songs on drives that may have been plugged in or out. */
    fun refreshMissing() {
        ioScope.launch { _missing.value = library.missing().mapTo(HashSet()) { it.id } }
    }

    fun signIn(username: String, password: String) {
        settings.username.set(username.trim())
        settings.password.set(password)
        appScope.launch { client.connect(username.trim(), password) }
    }

    fun reconnect() {
        if (settings.username.value.isNotBlank() && settings.password.value.isNotEmpty()) {
            signIn(settings.username.value, settings.password.value)
        }
    }

    val signedIn: Boolean get() = client.state.value is ConnectionState.Connected

    /** Runs a search (remembered for one-press repeats); the Sound page's Atmos search adds "atmos". */
    fun startSearch(text: String, atmos: Boolean = false) {
        val query = text.trim()
        if (query.isEmpty()) return
        if (atmos) {
            atmosSearch.start(if (query.contains("atmos", ignoreCase = true)) query else "$query atmos")
        } else {
            settings.addRecentSearch(query)
            search.start(query)
        }
        if (!signedIn) toast(context, "Not connected to Soulseek: sign in under Settings → Account.")
    }

    /** True when signed in; otherwise says how to sign in. */
    fun requireSignIn(): Boolean {
        if (signedIn) return true
        toast(context, "Not connected to Soulseek: sign in under Settings → Account.")
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

    /**
     * Plays a search result album from [start], keeping the whole album in the queue (so Previous
     * works). Songs not in the library stream while they download: the chosen one is asked for
     * first, then the ones after it, then the ones before.
     */
    fun playFolder(folder: SearchFolder, start: SearchTrack, shuffle: Boolean = false): Boolean {
        if (!start.info.playable) {
            toast(context, "This device can't play ${start.info.label}. Download it instead (More → Download).")
            return false
        }
        if (!requireSignIn()) return false
        val playable = folder.tracks.filter { it.info.playable }
        val from = playable.indexOf(start).coerceAtLeast(0)
        val priority = playable.drop(from) + playable.take(from)
        val items = priority.associateWith(::queueItemFor)
        fetchCover(folder)
        playback.play(playable.map { items.getValue(it) }, from, shuffle = shuffle)
        TransferService.start(context)
        askForNotifications()
        return true
    }

    /** Adds songs from a search result to the queue (right after the current one when [next]). */
    fun enqueue(folder: SearchFolder, tracks: List<SearchTrack>, next: Boolean): Int {
        val playable = tracks.filter { it.info.playable }
        if (playable.isEmpty() || !requireSignIn()) return 0
        fetchCover(folder)
        playback.enqueue(playable.map(::queueItemFor), next)
        TransferService.start(context)
        return playable.size
    }

    /** Downloads songs to the library without playing them. */
    fun download(folder: SearchFolder, tracks: List<SearchTrack>) {
        if (!requireSignIn()) return
        tracks.forEach { track -> if (library.find(track.username, track.file.filename) == null) downloads.fetch(track) }
        fetchCover(folder)
        TransferService.start(context)
        askForNotifications()
    }

    /**
     * Plays library songs from [start]. Returns false, and plays nothing, when [start] itself
     * can't be played (unsupported format, or its drive isn't connected): never another song instead.
     */
    fun playLibrary(tracks: List<LibraryTrack>, start: LibraryTrack? = null, shuffle: Boolean = false): Boolean {
        val playable = tracks.filter { it.info.playable && File(it.path).isFile }
        if (playable.isEmpty()) return false
        val index = when {
            start != null -> playable.indexOf(start)
            shuffle -> playable.indices.random()
            else -> 0
        }
        if (index < 0) return false
        playback.play(playable.map { it.toQueueItem() }, index, shuffle = shuffle)
        askForNotifications()
        return true
    }

    fun enqueueLibrary(tracks: List<LibraryTrack>, next: Boolean): Int {
        val playable = tracks.filter { it.info.playable && File(it.path).isFile }
        playback.enqueue(playable.map { it.toQueueItem() }, next)
        return playable.size
    }

    /** Resumes the last session from the library; false when none of its songs are available. */
    fun resume(): Boolean {
        val session = collection.data.value.session ?: return false
        return playback.resume(session).also { if (it) askForNotifications() }
    }

    /** Searches again for a download that failed, to pick another user's copy. */
    fun searchAgainFor(transfer: TransferInfo) {
        val name = PathNames.describe(transfer.filename)
        val album = Releases.cleanTitle(name.album).takeIf { it.isNotBlank() }
        startSearch(listOfNotNull(name.artist, album ?: name.title).joinToString(" "))
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !settings.notificationsAsked.value &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequest.value = true
        }
    }

    val notificationsAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The album's cover image from the same user's folder, saved next to the songs. */
    private fun fetchCover(folder: SearchFolder) {
        val cover = folder.cover ?: return
        val target = File(musicFolder(), PathNames.localPath(folder.username, cover.filename))
        if (target.parentFile?.let { dir -> dir.listFiles()?.any { it.extension.lowercase() in setOf("jpg", "jpeg", "png") } } == true) return
        client.download(folder.username, cover.filename, cover.size, target)
        target.parentFile?.path?.let(covers::invalidate)
    }

    /** Where a search result song lands in the library (its album folder there). */
    fun albumKeyFor(track: SearchTrack): String =
        File(musicFolder(), PathNames.localPath(track.username, track.file.filename)).parent.orEmpty()

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
            albumKey = albumKeyFor(track),
        )
    }
}

val Context.container: AppContainer
    get() = (applicationContext as SoundHubApp).container
