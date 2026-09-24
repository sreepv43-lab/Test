package io.github.sreepv43.streamhub.data

import android.content.Context
import io.github.sreepv43.streamhub.addon.StremioJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class WatchEntry(
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val videoId: String,
    val videoTitle: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    val progress: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val isFinished: Boolean get() = durationMs > 0 && progress > 0.95f
}

/** Remembers playback positions for resuming and the "Continue watching" row. */
class WatchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(WatchEntry.serializer())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()

    fun positionFor(videoId: String): Long =
        _entries.value.firstOrNull { it.videoId == videoId && !it.isFinished }?.positionMs ?: 0L

    fun record(entry: WatchEntry) {
        _entries.update { list ->
            (listOf(entry.copy(updatedAt = System.currentTimeMillis())) + list.filterNot { it.metaId == entry.metaId })
                .take(MAX_ENTRIES)
        }
        prefs.edit().putString(KEY, StremioJson.encodeToString(serializer, _entries.value)).apply()
    }

    fun remove(metaId: String) {
        _entries.update { list -> list.filterNot { it.metaId == metaId } }
        prefs.edit().putString(KEY, StremioJson.encodeToString(serializer, _entries.value)).apply()
    }

    private fun load(): List<WatchEntry> =
        prefs.getString(KEY, null)?.let { runCatching { StremioJson.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 50
    }
}
