package io.github.sreepv43.streamhub.addon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Scores for one title from MDBList (Rotten Tomatoes critics and audience, IMDb, Metacritic). */
@Serializable
data class TitleRatings(
    /** Rotten Tomatoes Tomatometer, 0–100. */
    val tomatoes: Int? = null,
    /** Rotten Tomatoes audience score (Popcornmeter), 0–100. */
    val popcorn: Int? = null,
    /** IMDb, 0–10. */
    val imdb: Double? = null,
    val metacritic: Int? = null,
    val fetchedAt: Long = 0,
) {
    val isEmpty: Boolean get() = tomatoes == null && popcorn == null && imdb == null && metacritic == null

    /** "🍅 92%  🍿 87%", or null when Rotten Tomatoes has neither. */
    val tomatoLine: String?
        get() = listOfNotNull(tomatoes?.let { "🍅 $it%" }, popcorn?.let { "🍿 $it%" }).joinToString("  ").ifEmpty { null }
}

/** Reads MDBList's answers (https://api.mdblist.com, `/imdb/{type}/{id}` and the batch `/imdb/any`). */
object MdbList {
    const val BASE = "https://api.mdblist.com"
    /** Titles per batch request on a free account. */
    const val BATCH = 10

    private val TOMATOES = setOf("tomatoes", "rtomatoes", "rottentomatoes", "tomatometer")
    private val POPCORN = setOf("popcorn", "audience", "tomatoesaudience", "rtaudience", "popcornmeter")

    /** One title's object: `{"imdb_id": "tt…", "ratings": [{"source": "tomatoes", "value": 92}, …]}`. */
    fun parse(item: JsonObject, now: Long): Pair<String, TitleRatings>? {
        val id = (item["imdb_id"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: ((item["ids"] as? JsonObject)?.get("imdb") as? JsonPrimitive)?.contentOrNullSafe()
            ?: return null
        var ratings = TitleRatings(fetchedAt = now)
        (item["ratings"] as? JsonArray)?.forEach { entry ->
            val rating = entry as? JsonObject ?: return@forEach
            val source = (rating["source"] as? JsonPrimitive)?.contentOrNullSafe()?.lowercase() ?: return@forEach
            val value = rating.number("value") ?: return@forEach
            ratings = when (source) {
                in TOMATOES -> ratings.copy(tomatoes = value.toInt().takeIf { it in 0..100 })
                in POPCORN -> ratings.copy(popcorn = value.toInt().takeIf { it in 0..100 })
                "imdb" -> ratings.copy(imdb = value.takeIf { it in 0.0..10.0 })
                "metacritic" -> ratings.copy(metacritic = value.toInt().takeIf { it in 0..100 })
                else -> ratings
            }
        }
        return id to ratings
    }

    /** A batch answer (an array) or a single title (an object). */
    fun parseAny(json: JsonElement, now: Long): Map<String, TitleRatings> = when (json) {
        is JsonArray -> json.mapNotNull { (it as? JsonObject)?.let { item -> parse(item, now) } }.toMap()
        is JsonObject -> listOfNotNull(parse(json, now)).toMap()
        else -> emptyMap()
    }

    /** Only IMDb ids can be looked up ("tt1160419"); episode ids ("tt…:1:2") use their show's id. */
    fun imdbId(metaId: String): String? = metaId.substringBefore(':').takeIf { it.matches(Regex("tt\\d{5,10}")) }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.toDoubleOrNull() else p.doubleOrNull
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
