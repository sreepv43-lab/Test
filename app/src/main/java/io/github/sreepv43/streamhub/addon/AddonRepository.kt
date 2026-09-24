package io.github.sreepv43.streamhub.addon

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A catalog offered by one installed addon. */
data class CatalogRef(val addon: InstalledAddon, val catalog: CatalogDef) {
    val key: String get() = "${addon.transportUrl}|${catalog.type}|${catalog.id}"
    val title: String get() = "${catalog.name ?: catalog.id} · ${catalog.type.replaceFirstChar { it.uppercase() }}"
}

/** Streams returned by one addon, or the error it produced. */
data class AddonStreams(val addon: InstalledAddon, val streams: List<Stream>, val error: String? = null)

class AddonRepository(context: Context, private val client: AddonClient) {

    private val prefs = context.getSharedPreferences("addons", Context.MODE_PRIVATE)
    private val _addons = MutableStateFlow(load())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    val isFirstRun: Boolean get() = !prefs.getBoolean(KEY_INITIALIZED, false)

    /** Installs the official Stremio addons the first time the app starts. */
    suspend fun installDefaults() {
        if (!isFirstRun) return
        for (url in DEFAULT_ADDONS) {
            runCatching { install(url) }
        }
        if (_addons.value.isNotEmpty()) prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
    }

    suspend fun install(url: String): InstalledAddon {
        val addon = client.fetchManifest(url)
        _addons.update { list ->
            val index = list.indexOfFirst { it.transportUrl == addon.transportUrl || it.manifest.id == addon.manifest.id }
            if (index >= 0) list.toMutableList().also { it[index] = addon } else list + addon
        }
        save()
        return addon
    }

    fun remove(addon: InstalledAddon) {
        _addons.update { list -> list.filterNot { it.transportUrl == addon.transportUrl } }
        save()
    }

    fun move(addon: InstalledAddon, delta: Int) {
        _addons.update { list ->
            val from = list.indexOfFirst { it.transportUrl == addon.transportUrl }
            val to = from + delta
            if (from < 0 || to !in list.indices) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        save()
    }

    /** Re-downloads every manifest so new catalogs / versions are picked up. */
    suspend fun refreshAll() = coroutineScope {
        val refreshed = _addons.value.map { addon ->
            async { runCatching { client.fetchManifest(addon.transportUrl) }.getOrDefault(addon) }
        }.awaitAll()
        _addons.value = refreshed
        save()
    }

    /** Catalogs that can be shown without user input (no required extras). */
    fun boardCatalogs(): List<CatalogRef> = _addons.value.flatMap { addon ->
        addon.manifest.catalogs
            .filter { it.requiredExtras.isEmpty() }
            .map { CatalogRef(addon, it) }
    }

    fun searchableCatalogs(): List<CatalogRef> = _addons.value.flatMap { addon ->
        addon.manifest.catalogs
            .filter { it.supportsExtra("search") && (it.requiredExtras - "search").isEmpty() }
            .map { CatalogRef(addon, it) }
    }

    fun findCatalog(transportUrl: String, type: String, id: String): CatalogRef? =
        _addons.value.firstOrNull { it.transportUrl == transportUrl }?.let { addon ->
            addon.manifest.catalogs.firstOrNull { it.type == type && it.id == id }?.let { CatalogRef(addon, it) }
        }

    suspend fun catalog(ref: CatalogRef, extra: List<Pair<String, String>> = emptyList()): List<Meta> =
        client.catalog(ref.addon, ref.catalog.type, ref.catalog.id, extra)

    /** Asks addons in priority order until one returns full metadata. */
    suspend fun meta(type: String, id: String): Meta? {
        var lastError: Exception? = null
        for (addon in _addons.value.filter { it.supports("meta", type, id) }) {
            try {
                client.meta(addon, type, id)?.let { return it }
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return null
    }

    fun streamAddons(type: String, id: String): List<InstalledAddon> =
        _addons.value.filter { it.supports("stream", type, id) }

    suspend fun streams(addon: InstalledAddon, type: String, id: String): AddonStreams =
        try {
            AddonStreams(addon, client.streams(addon, type, id))
        } catch (e: Exception) {
            AddonStreams(addon, emptyList(), e.message ?: e.javaClass.simpleName)
        }

    suspend fun subtitles(type: String, id: String): List<Subtitle> = coroutineScope {
        _addons.value.filter { it.supports("subtitles", type, id) }
            .map { addon -> async { runCatching { client.subtitles(addon, type, id) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
    }

    private fun load(): List<InstalledAddon> {
        val raw = prefs.getString(KEY_ADDONS, null) ?: return emptyList()
        return runCatching { StremioJson.decodeFromString(installedAddonListSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun save() {
        prefs.edit()
            .putString(KEY_ADDONS, StremioJson.encodeToString(installedAddonListSerializer, _addons.value))
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    companion object {
        private const val KEY_ADDONS = "installed"
        private const val KEY_INITIALIZED = "initialized"

        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://opensubtitles-v3.strem.io/manifest.json",
        )
    }
}
