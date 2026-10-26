package io.github.sreepv43.streamhub.addon

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsTest {
    @Test
    fun readsRottenTomatoesAndOtherScores() {
        val json = Json.parseToJsonElement(
            """{"id": 1, "imdb_id": "tt1160419", "title": "Dune", "year": 2021, "type": "movie", "ratings": [
                {"source": "imdb", "value": 8.0, "score": 80, "votes": 900000},
                {"source": "metacritic", "value": 74, "score": 74},
                {"source": "tomatoes", "value": 83, "score": 83},
                {"source": "popcorn", "value": 90, "score": 90},
                {"source": "letterboxd", "value": 3.9, "score": 78},
                {"source": "rogerebert", "value": null}
            ]}""",
        )
        val ratings = MdbList.parseAny(json, now = 5).getValue("tt1160419")
        assertEquals(83, ratings.tomatoes)
        assertEquals(90, ratings.popcorn)
        assertEquals(8.0, ratings.imdb!!, 0.001)
        assertEquals(74, ratings.metacritic)
        assertEquals(5L, ratings.fetchedAt)
        assertEquals("🍅 83%  🍿 90%", ratings.tomatoLine)
    }

    @Test
    fun readsABatchAndTitlesWithoutTomatoes() {
        val json = Json.parseToJsonElement(
            """[
                {"ids": {"imdb": "tt0903747"}, "ratings": [{"source": "tomatoes", "value": null}, {"source": "imdb", "value": "9.5"}]},
                {"imdb_id": "tt2543164", "ratings": [{"source": "tomatoesaudience", "value": 82}, {"source": "rtomatoes", "value": 94}]},
                {"title": "no id"}
            ]""",
        )
        val all = MdbList.parseAny(json, now = 0)
        assertEquals(setOf("tt0903747", "tt2543164"), all.keys)
        val show = all.getValue("tt0903747")
        assertNull(show.tomatoes)
        assertNull(show.tomatoLine)
        assertEquals(9.5, show.imdb!!, 0.001)
        assertEquals(94, all.getValue("tt2543164").tomatoes)
        assertEquals(82, all.getValue("tt2543164").popcorn)
    }

    @Test
    fun onlyImdbIdsCanBeLookedUp() {
        assertEquals("tt0903747", MdbList.imdbId("tt0903747:1:2"))
        assertNull(MdbList.imdbId("kitsu:1234"))
        assertTrue(TitleRatings().isEmpty)
    }
}
