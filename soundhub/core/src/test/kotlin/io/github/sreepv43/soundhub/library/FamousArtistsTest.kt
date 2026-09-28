package io.github.sreepv43.soundhub.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamousArtistsTest {
    @Test
    fun searchTextIsPlainWordsThatFileNamesContain() {
        assertEquals("Beyonce", FamousArtists.searchText("Beyoncé"))
        assertEquals("AC DC", FamousArtists.searchText("AC/DC"))
        assertEquals("Guns N Roses", FamousArtists.searchText("Guns N' Roses"))
        assertEquals("The Notorious B I G", FamousArtists.searchText("The Notorious B.I.G."))
        assertEquals("Tiesto", FamousArtists.searchText("Tiësto"))
    }

    @Test
    fun everyGenreHasArtistsOnceAndEveryQueryCanFindSomething() {
        assertTrue(FamousArtists.genres.size >= 10)
        FamousArtists.genres.forEach { genre ->
            assertTrue(genre.name, genre.artists.size >= 8)
            assertEquals("duplicates in ${genre.name}", genre.artists.size, genre.artists.map { it.name }.toSet().size)
            genre.artists.forEach { artist ->
                // Every search has a word of two or more letters (single initials alone match nothing useful).
                assertTrue(artist.name, artist.query.split(' ').any { word -> word.count(Char::isLetterOrDigit) >= 2 })
            }
        }
        assertEquals("Rahman", FamousArtists.genres.flatMap { it.artists }.first { it.name == "A. R. Rahman" }.query)
    }
}
