package io.github.sreepv43.streamhub.addon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A film or series someone acted in or directed, by its IMDb id (the id Stremio addons use). */
data class Credit(
    val imdbId: String,
    val title: String,
    val year: Int?,
    /** "movie" or "series". */
    val type: String,
    /** "actor" or "director". */
    val role: String,
)

/**
 * A person's films and series from Wikidata, the free, open database behind Wikipedia, which links
 * people and titles to their IMDb ids (cast names from addons carry no id of their own).
 */
object PersonCredits {
    const val ENDPOINT = "https://query.wikidata.org/sparql"

    /** SPARQL for everything a person named [name] (as addons write it) acted in or directed. */
    fun query(name: String): String {
        val label = name.trim().replace("\\", "\\\\").replace("\"", "\\\"")
        // Wikidata's own name search finds the person (fast, and tolerant of spelling); the query
        // hint keeps that search first, otherwise the query can take a minute.
        return """
            SELECT ?person ?imdb ?title ?date ?role ?kind WHERE {
              hint:Query hint:optimizer "None" .
              SERVICE wikibase:mwapi {
                bd:serviceParam wikibase:endpoint "www.wikidata.org" ; wikibase:api "EntitySearch" ;
                  mwapi:search "$label" ; mwapi:language "en" ; mwapi:limit "3" .
                ?person wikibase:apiOutputItem mwapi:item .
              }
              ?person wdt:P31 wd:Q5 .
              { ?work wdt:P161 ?person . BIND("actor" AS ?role) }
              UNION { ?work wdt:P57 ?person . BIND("director" AS ?role) }
              ?work wdt:P345 ?imdb .
              FILTER(STRSTARTS(?imdb, "tt"))
              OPTIONAL { ?work wdt:P577 ?date }
              OPTIONAL { ?work wdt:P31 wd:Q5398426 . BIND("series" AS ?kind) }
              OPTIONAL { ?work rdfs:label ?title FILTER(LANG(?title) = "en") }
            } LIMIT 1500
        """.trimIndent()
    }

    /**
     * The credits in a query's JSON result, newest first. When several people share the name, the
     * one with the most credits is meant (a well-known actor rather than a namesake).
     */
    fun parse(json: String): List<Credit> {
        val bindings = runCatching {
            Json.parseToJsonElement(json).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray.map { it.jsonObject }
        }.getOrElse { return emptyList() }
        val rows = bindings.mapNotNull { b ->
            val person = b.value("person") ?: return@mapNotNull null
            val imdb = b.value("imdb") ?: return@mapNotNull null
            Row(person, imdb, b.value("title"), b.value("date")?.take(4)?.toIntOrNull(), b.value("role") ?: "actor", b.value("kind"))
        }
        val person = rows.groupBy { it.person }.maxByOrNull { (_, credits) -> credits.distinctBy { it.imdb }.size }?.key
            ?: return emptyList()
        return rows.filter { it.person == person }
            .groupBy { it.imdb to it.role }
            .map { (key, same) ->
                Credit(
                    imdbId = key.first,
                    title = same.firstNotNullOfOrNull { it.title } ?: key.first,
                    year = same.mapNotNull { it.year }.minOrNull(),
                    type = if (same.any { it.kind == "series" }) "series" else "movie",
                    role = key.second,
                )
            }
            .sortedWith(compareByDescending<Credit> { it.year ?: Int.MIN_VALUE }.thenBy { it.title })
    }

    /** Poster for an IMDb id from the image service Stremio itself uses. */
    fun posterUrl(imdbId: String) = "https://images.metahub.space/poster/medium/$imdbId/img"

    private class Row(val person: String, val imdb: String, val title: String?, val year: Int?, val role: String, val kind: String?)

    private fun JsonObject.value(name: String): String? = this[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
}
