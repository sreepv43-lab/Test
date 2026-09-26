package io.github.sreepv43.streamhub.sync

/**
 * Reads IMDb lists: what a pasted link points to, and the title ids (tt…) in a list's CSV export,
 * one of its pages, or any pasted text (IMDb links or ids). IMDb ids are the ids Stremio addons use.
 */
object ImdbList {
    sealed interface Source {
        /** A list (imdb.com/list/ls…). */
        data class List(val id: String) : Source
        /** A user's watchlist (imdb.com/user/ur…/watchlist). */
        data class Watchlist(val userId: String) : Source
        /** Titles pasted directly (IMDb links or tt ids). */
        data class Titles(val ids: kotlin.collections.List<String>) : Source
    }

    fun source(input: String): Source? {
        LIST.find(input)?.let { return Source.List(it.value) }
        USER.find(input)?.let { return Source.Watchlist(it.value) }
        return ids(input).takeIf { it.isNotEmpty() }?.let { Source.Titles(it) }
    }

    fun exportUrl(listId: String) = "https://www.imdb.com/list/$listId/export"

    fun pageUrl(source: Source, page: Int): String? = when (source) {
        is Source.List -> "https://www.imdb.com/list/${source.id}/?page=$page"
        is Source.Watchlist -> "https://www.imdb.com/user/${source.userId}/watchlist/?page=$page"
        is Source.Titles -> null
    }

    /** Title ids in order of first appearance. */
    fun ids(text: String): kotlin.collections.List<String> = ID.findAll(text).map { it.value }.distinct().toList()

    /** Whether [body] is the CSV an IMDb export returns (and not e.g. a sign-in page). */
    fun isCsv(body: String): Boolean = body.lineSequence().firstOrNull()?.contains("Const") == true

    /**
     * The titles on one page of a list: from the page's embedded data when present, else from
     * its title links (so the ids of unrelated links elsewhere on the page don't count).
     */
    fun idsFromPage(html: String): kotlin.collections.List<String> {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1)
        if (data != null) {
            val fromData = DATA_ID.findAll(data).map { it.groupValues[1] }.distinct().toList()
            if (fromData.isNotEmpty()) return fromData
        }
        return TITLE_LINK.findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    private val ID = Regex("""tt\d{7,9}""")
    private val LIST = Regex("""ls\d{6,12}""")
    private val USER = Regex("""ur\d{5,12}""")
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val DATA_ID = Regex(""""id":"(tt\d{7,9})"""")
    private val TITLE_LINK = Regex("""href="/title/(tt\d{7,9})/""")
}
