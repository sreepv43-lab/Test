package io.github.sreepv43.streamhub.sync

import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.data.Library
import io.github.sreepv43.streamhub.data.LibraryItem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Copies an IMDb list or watchlist (public) into My List. The titles' names and posters come from
 * the installed metadata addons (e.g. Cinemeta), which use the same IMDb ids.
 */
class ImdbImport(
    private val http: OkHttpClient,
    private val addons: AddonRepository,
    private val library: Library,
) {
    data class Summary(val added: Int, val notFound: Int)

    suspend fun run(input: String, progress: (String) -> Unit): Summary = withContext(Dispatchers.IO) {
        val source = ImdbList.source(input) ?: throw IOException("Paste the link of an IMDb list or watchlist")
        progress("Reading the list…")
        val ids = when (source) {
            is ImdbList.Source.Titles -> source.ids
            else -> fetchIds(source)
        }
        if (ids.isEmpty()) throw IOException("No titles found. Is the list public? (IMDb → list → Edit → Privacy: Public)")

        var done = 0
        val limit = Semaphore(LOOKUPS_AT_ONCE)
        val items = coroutineScope {
            ids.map { id ->
                async {
                    limit.withPermit {
                        lookUp(id).also {
                            synchronized(this@ImdbImport) { done++ }
                            progress("Looking up titles: $done of ${ids.size}")
                        }
                    }
                }
            }.awaitAll()
        }
        val found = items.filterNotNull()
        library.addAll(found)
        Summary(added = found.size, notFound = ids.size - found.size)
    }

    private fun fetchIds(source: ImdbList.Source): List<String> {
        // The CSV export, where IMDb still offers it without signing in.
        if (source is ImdbList.Source.List) {
            runCatching { get(ImdbList.exportUrl(source.id)) }.getOrNull()
                ?.takeIf(ImdbList::isCsv)
                ?.let { return ImdbList.ids(it) }
        }
        val ids = LinkedHashSet<String>()
        for (page in 1..MAX_PAGES) {
            val url = ImdbList.pageUrl(source, page) ?: break
            val html = runCatching { get(url) }.getOrElse { if (page == 1) throw it else break }
            if (!ids.addAll(ImdbList.idsFromPage(html))) break
        }
        return ids.toList()
    }

    /** The title as a My List entry, or null if no addon knows it. */
    private suspend fun lookUp(id: String): LibraryItem? {
        for (type in listOf("movie", "series")) {
            val meta = runCatching { addons.meta(type, id) }.getOrNull() ?: continue
            if (meta.name.isBlank()) continue
            val kind = meta.type.takeIf { it == "movie" || it == "series" } ?: type
            return LibraryItem(id = id, type = kind, name = meta.name, poster = meta.poster)
        }
        return null
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            // IMDb answers browsers only.
            .header("User-Agent", BROWSER)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("IMDb answered ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val MAX_PAGES = 20
        const val LOOKUPS_AT_ONCE = 6
        const val BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
