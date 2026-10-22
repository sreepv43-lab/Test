package io.github.sreepv43.streamhub.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMatchTest {
    private val dune = TitleRef("tt1160419", "tt1160419", "Dune", 2021)
    private val badEpisode = TitleRef("tt0903747", "tt0903747:1:2", "Breaking Bad", 2008, 1, 2)

    @Test
    fun downloadsMatchByCatalogueIds() {
        assertTrue(CopyMatch.matches(dune, CopyFile("tt1160419", "tt1160419")))
        assertFalse(CopyMatch.matches(dune, CopyFile("tt15239678", "tt15239678")))
        assertTrue(CopyMatch.matches(badEpisode, CopyFile("tt0903747", "tt0903747:1:2")))
        assertFalse(CopyMatch.matches(badEpisode, CopyFile("tt0903747", "tt0903747:1:3")))
        // An id that disagrees wins over a matching name.
        assertFalse(CopyMatch.matches(dune, CopyFile("tt0087182", null, "Dune", 2021)))
    }

    @Test
    fun filesFoundByNameNeedTheYearOrTheEpisode() {
        assertTrue(CopyMatch.matches(dune, CopyFile(parsedTitle = "Dune", year = 2021)))
        assertFalse(CopyMatch.matches(dune, CopyFile(parsedTitle = "Dune", year = 1984)))
        assertFalse("no year: not sure enough", CopyMatch.matches(dune, CopyFile(parsedTitle = "Dune")))
        assertFalse(CopyMatch.matches(dune, CopyFile(parsedTitle = "Dune", season = 1, episode = 1)))
        assertTrue(CopyMatch.matches(badEpisode, CopyFile(parsedTitle = "Breaking Bad", season = 1, episode = 2)))
        assertFalse(CopyMatch.matches(badEpisode, CopyFile(parsedTitle = "Breaking Bad", season = 2, episode = 1)))
        assertTrue(
            CopyMatch.matches(
                TitleRef("tt0167260", "tt0167260", "The Lord of the Rings: The Return of the King", 2003),
                CopyFile(parsedTitle = "Lord of the Rings The Return of the King", year = 2003),
            ),
        )
        assertTrue(CopyMatch.matches(TitleRef("x", "x", "Amélie", 2001), CopyFile(parsedTitle = "Amelie", year = 2001)))
    }

    @Test
    fun postersCountAnyEpisode() {
        assertTrue(CopyMatch.belongsTo("tt0903747", "Breaking Bad", 2008, CopyFile("tt0903747", "tt0903747:3:4")))
        assertTrue(CopyMatch.belongsTo("tt0903747", "Breaking Bad", 2008, CopyFile(parsedTitle = "Breaking Bad", season = 3, episode = 4)))
        assertFalse(CopyMatch.belongsTo("tt1160419", "Dune", 2021, CopyFile(parsedTitle = "Dune")))
    }

    @Test
    fun readsYearsAndEpisodes() {
        assertEquals(2008, CopyMatch.yearOf("2008–2013"))
        assertEquals(null, CopyMatch.yearOf(null))
        assertEquals(1 to 2, CopyMatch.episodeOf("tt0903747:1:2"))
        assertEquals(null, CopyMatch.episodeOf("tt1160419"))
    }
}
