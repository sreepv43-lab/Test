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

    @Test
    fun hindiTamilMalayalamAndTeluguHaveTheirOwnGenres() {
        val byName = FamousArtists.genres.associateBy { it.name }
        listOf("Hindi", "Tamil", "Malayalam", "Telugu").forEach { language ->
            assertTrue("$language genre", (byName[language]?.artists?.size ?: 0) >= 15)
        }
        val names = { genre: String -> byName.getValue(genre).artists.map { it.name } }
        assertTrue("Arijit Singh" in names("Hindi"))
        assertTrue("Ilaiyaraaja" in names("Tamil"))
        assertTrue("A. R. Rahman" in names("Tamil") && "A. R. Rahman" in names("Hindi"))
        assertTrue("K. J. Yesudas" in names("Malayalam"))
        assertEquals("Yesudas", byName.getValue("Malayalam").artists.first { it.name == "K. J. Yesudas" }.query)
        assertEquals("Shankar Ehsaan Loy", byName.getValue("Hindi").artists.first { it.name == "Shankar-Ehsaan-Loy" }.query)
    }

    @Test
    fun myArtistsAreKeptNewestFirstWithoutDuplicates() {
        var text = ""
        text = MyArtists.add(text, "  Alan   Walker ")
        text = MyArtists.add(text, "Nucleya")
        text = MyArtists.add(text, "alan walker")
        text = MyArtists.add(text, "   ")
        assertEquals(listOf("Nucleya", "Alan Walker"), MyArtists.parse(text))
        assertEquals(listOf("Nucleya"), MyArtists.parse(MyArtists.remove(text, "Alan Walker")))
        val genre = MyArtists.genre(text)
        assertEquals(MyArtists.GENRE, genre.name)
        assertEquals(listOf("Nucleya", "Alan Walker"), genre.artists.map { it.name })
        assertEquals("Alan Walker", genre.artists.last().query)
        assertEquals(200, MyArtists.parse((1..300).fold("") { acc, i -> MyArtists.add(acc, "Artist $i") }).size)
    }
}
