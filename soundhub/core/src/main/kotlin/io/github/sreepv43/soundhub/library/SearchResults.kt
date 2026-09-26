package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.slsk.SearchResponse
import io.github.sreepv43.soundhub.slsk.SharedFile
import io.github.sreepv43.soundhub.slsk.SoulseekClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One audio file in a search result, classified. */
data class SearchTrack(
    val username: String,
    val file: SharedFile,
    val info: AudioInfo,
    val name: TrackName,
)

/** One user's folder (usually an album) in the search results. */
data class SearchFolder(
    val username: String,
    val directory: String,
    val tracks: List<SearchTrack>,
    val slotFree: Boolean,
    /** Bytes per second, as the user's client reports it. */
    val avgSpeed: Int,
    val queueLength: Long,
) {
    val key: String get() = "$username\u0000$directory"
    val album: String get() = tracks.first().name.album
    val artist: String? get() = tracks.first().name.artist
    val totalSize: Long get() = tracks.sumOf { it.file.size }

    fun matches(filter: FormatFilter): Boolean = tracks.any { filter.matches(it.info) }

    /** The most common format among the tracks [filter] lets through, for the folder's badge. */
    fun summary(filter: FormatFilter): AudioInfo? =
        tracks.map { it.info }.filter(filter::matches).groupBy { it.label }.maxByOrNull { it.value.size }?.value?.first()
}

object SearchResults {
    /** Groups responses into folders of audio files, most available (free slot, short queue, fast) first. */
    fun group(responses: List<SearchResponse>): List<SearchFolder> =
        responses.flatMap { response ->
            response.files
                .filter { AudioFormats.isAudio(it.filename) }
                .groupBy { PathNames.folderOf(it.filename) }
                .map { (directory, files) ->
                    val tracks = files.map { file ->
                        SearchTrack(
                            response.username,
                            file,
                            AudioFormats.classify(file.filename, file.size, file.bitrate, file.durationSec, file.sampleRate, file.bitDepth, file.vbr),
                            PathNames.describe(file.filename),
                        )
                    }.sortedWith(compareBy({ it.name.trackNumber ?: Int.MAX_VALUE }, { it.file.filename.lowercase() }))
                    SearchFolder(response.username, directory, tracks, response.slotFree, response.avgSpeed, response.queueLength)
                }
        }.sortedWith(
            compareByDescending<SearchFolder> { it.slotFree }
                .thenBy { it.queueLength }
                .thenByDescending { it.avgSpeed },
        )
}

/** A search whose grouped results update as answers come in. */
class SearchSession(private val client: SoulseekClient, private val scope: CoroutineScope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _folders = MutableStateFlow<List<SearchFolder>>(emptyList())
    val folders: StateFlow<List<SearchFolder>> = _folders.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null

    fun start(query: String) {
        job?.cancel()
        _query.value = query
        _folders.value = emptyList()
        val search = client.search(query)
        _searching.value = true
        job = scope.launch {
            try {
                val started = System.currentTimeMillis()
                var seen = -1
                while (isActive && System.currentTimeMillis() - started < RESULTS_WINDOW_MS) {
                    val responses = search.responses.value
                    if (responses.size != seen) {
                        seen = responses.size
                        _folders.value = withContext(Dispatchers.Default) { SearchResults.group(responses) }
                    }
                    delay(REFRESH_MS)
                }
            } finally {
                client.stopSearch(search)
                _searching.value = false
            }
        }
    }

    fun clear() {
        job?.cancel()
        _query.value = ""
        _folders.value = emptyList()
    }

    private companion object {
        const val REFRESH_MS = 500L
        const val RESULTS_WINDOW_MS = 90_000L
    }
}
