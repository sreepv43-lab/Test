package io.github.sreepv43.streamhub.download

import android.content.Context
import io.github.sreepv43.streamhub.addon.StremioJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Persists the download list as JSON in the app's private storage. */
class DownloadRepository(context: Context) {
    private val file = File(context.filesDir, "downloads.json")
    private val serializer = ListSerializer(DownloadItem.serializer())
    private val lock = Any()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) = synchronized(lock) {
        _items.value = listOf(item) + _items.value
        save()
    }

    fun update(id: String, persist: Boolean = true, transform: (DownloadItem) -> DownloadItem): DownloadItem? =
        synchronized(lock) {
            var updated: DownloadItem? = null
            _items.value = _items.value.map {
                if (it.id == id) transform(it).also { new -> updated = new } else it
            }
            if (persist) save()
            updated
        }

    fun remove(id: String) = synchronized(lock) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    private fun load(): List<DownloadItem> =
        runCatching { StremioJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StremioJson.encodeToString(serializer, _items.value))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
