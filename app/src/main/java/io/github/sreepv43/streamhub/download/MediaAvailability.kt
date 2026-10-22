package io.github.sreepv43.streamhub.download

import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Every downloaded (or downloading) copy StreamHub knows of, wherever it is: the TV, its drives and
 * the Home Server's library (which also lists films the server found in its folders). Title pages ask
 * [copiesFor]; posters ask [badgeFor]. Nothing is scanned when a page opens: the TV's finished files
 * are checked in the background when the downloads change (each check time-limited, as drives can
 * stall), and the server keeps its own index.
 */
@OptIn(FlowPreview::class)
class MediaAvailability(
    private val downloads: DownloadRepository,
    private val homeServer: HomeServer,
    private val storage: DownloadStorage,
) {
    enum class Place { TV, DRIVE, SERVER }

    data class Copy(
        val key: String,
        val place: Place,
        /** "Elements", "Den PC · Movies"… */
        val where: String,
        /** "4K · HEVC · Dolby Vision · DD+ 5.1" as far as it is known. */
        val details: String?,
        val sizeBytes: Long?,
        /** Null when it can be played now, else why not ("the drive is not connected"). */
        val problem: String?,
        /** Set while it is still downloading (0–1). */
        val downloading: Float? = null,
        val download: DownloadItem? = null,
        /** The Home Server library id to stream. */
        val serverId: String? = null,
        val file: CopyFile,
    ) {
        val playable: Boolean get() = problem == null && downloading == null
    }

    sealed interface Badge {
        data object Downloaded : Badge
        data class Downloading(val percent: Int) : Badge
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Finished TV downloads whose file is missing right now (drive unplugged, file deleted). */
    private val missing = MutableStateFlow<Map<String, String>>(emptyMap())

    val copies: StateFlow<List<Copy>> = combine(downloads.items, homeServer.library, homeServer.online, missing) { items, library, online, gone ->
        build(items, library, online, gone)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Only when the finished downloads change, not on every progress update.
        downloads.items
            .map { items -> items.filter { !it.onServer && it.status == DownloadItem.Status.COMPLETED }.map { it.id to it.fileUri } }
            .distinctUntilChanged()
            .debounce(500)
            .onEach { checkFiles(downloads.items.value) }
            .launchIn(scope)
    }

    /** The copies of a movie or episode: playable ones first, then downloads in progress, then the rest. */
    fun copiesFor(ref: TitleRef, all: List<Copy> = copies.value): List<Copy> =
        all.filter { CopyMatch.matches(ref, it.file) }
            .sortedWith(compareBy<Copy>({ !it.playable }, { it.downloading != null }, { -(it.sizeBytes ?: 0) }))

    /** What a poster shows: Downloaded, or the progress of a download in progress, for any of its episodes. */
    fun badgeFor(metaId: String, title: String, year: Int?, all: List<Copy> = copies.value): Badge? {
        val mine = all.filter { CopyMatch.belongsTo(metaId, title, year, it.file) }
        if (mine.any { it.downloading == null }) return Badge.Downloaded
        return mine.mapNotNull { it.downloading }.maxOrNull()?.let { Badge.Downloading((it * 100).toInt()) }
    }

    /** Checks the TV's finished files again (e.g. when a drive was plugged in). */
    fun recheck() {
        scope.launch { checkFiles(downloads.items.value) }
    }

    private fun checkFiles(items: List<DownloadItem>) {
        val gone = HashMap<String, String>()
        items.filter { !it.onServer && it.status == DownloadItem.Status.COMPLETED }.forEach { item ->
            val uri = item.fileUri
            val ok = uri != null && storage.bounded(CHECK_SECONDS, false) { storage.exists(uri) }
            if (!ok) {
                gone[item.id] = if (storage.isRemovable(item.location)) "the drive is not connected" else "the file is gone"
            }
        }
        missing.value = gone
    }

    private fun build(items: List<DownloadItem>, library: List<ServerLibraryItem>, online: Boolean?, gone: Map<String, String>): List<Copy> {
        val serverName = homeServer.name()
        val inLibrary = library.map { it.id }.toSet()
        val result = ArrayList<Copy>()
        for (item in items) {
            val finished = item.status == DownloadItem.Status.COMPLETED
            // Finished server downloads come from the server's library below (with their media details).
            if (item.onServer && finished && item.remoteId in inLibrary) continue
            if (!finished && item.status == DownloadItem.Status.FAILED) continue
            val place = when {
                item.onServer -> Place.SERVER
                storage.isRemovable(item.location) -> Place.DRIVE
                else -> Place.TV
            }
            val facts = StreamFacts.of(Stream(name = item.fileName ?: item.suggestedFileName ?: item.title))
            result += Copy(
                key = "d-" + item.id,
                place = place,
                where = item.location.label,
                details = listOfNotNull(facts.quality, facts.codec, facts.hdr, facts.audio).joinToString(" · ").ifEmpty { null },
                sizeBytes = item.totalBytes.takeIf { it > 0 },
                problem = when {
                    !finished -> null
                    item.onServer -> if (online == true) null else "the Home Server is not reachable"
                    else -> gone[item.id]
                },
                downloading = if (finished) null else item.progress,
                download = item,
                serverId = item.remoteId.takeIf { item.onServer && finished },
                file = CopyFile(item.metaId, item.videoId),
            )
        }
        for (entry in library) {
            val media = entry.media
            val quality = media?.height?.let { h -> StreamFacts.qualityOf(h) }
            result += Copy(
                key = "s-" + entry.id,
                place = Place.SERVER,
                where = listOf(serverName, entry.storageLabel).filter { it.isNotEmpty() }.joinToString(" · "),
                details = listOfNotNull(quality, media?.videoCodec, media?.hdr, media?.audio).joinToString(" · ").ifEmpty { null },
                sizeBytes = entry.sizeBytes,
                problem = when {
                    online != true -> "the Home Server is not reachable"
                    !entry.available -> "its drive is not connected to the server"
                    else -> null
                },
                serverId = entry.id,
                file = CopyFile(entry.metaId, entry.videoId, entry.parsedTitle, entry.year, entry.season, entry.episode),
            )
        }
        return result
    }

    private companion object {
        const val CHECK_SECONDS = 3L
    }
}
