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
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val addedAt: Long = 0,
)

/** "My List": titles saved to watch later, newest first (also filled by Stremio and Trakt imports). */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(LibraryItem.serializer())

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    fun contains(id: String): Boolean = _items.value.any { it.id == id }

    /** Adds [items] (keeping entries already in the list, but filling in missing posters). */
    fun addAll(items: List<LibraryItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        _items.update { current ->
            val known = current.associateBy { it.id }
            val added = items.filter { it.id !in known }.distinctBy { it.id }.map { it.copy(addedAt = now) }
            added + current.map { old -> items.firstOrNull { it.id == old.id && old.poster == null }?.let { old.copy(poster = it.poster) } ?: old }
        }
        save()
    }

    fun add(item: LibraryItem) = addAll(listOf(item))

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    private fun save() {
        prefs.edit().putString(KEY, StremioJson.encodeToString(serializer, _items.value)).apply()
    }

    private fun load(): List<LibraryItem> =
        prefs.getString(KEY, null)?.let { runCatching { StremioJson.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val KEY = "items"
    }
}
