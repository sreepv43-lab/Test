package io.github.sreepv43.streamhub.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbListTest {
    @Test
    fun understandsPastedLinks() {
        assertEquals(ImdbList.Source.List("ls055592025"), ImdbList.source("https://www.imdb.com/list/ls055592025/?ref_=nv_usr_lst_3"))
        assertEquals(ImdbList.Source.Watchlist("ur12345678"), ImdbList.source("imdb.com/user/ur12345678/watchlist"))
        assertEquals(
            ImdbList.Source.Titles(listOf("tt0111161", "tt0068646")),
            ImdbList.source("https://www.imdb.com/title/tt0111161/ and https://m.imdb.com/title/tt0068646/ tt0111161"),
        )
        assertNull(ImdbList.source("my watchlist"))
    }

    @Test
    fun readsTheCsvExport() {
        val csv = "Position,Const,Created,Modified,Description,Title,URL,Title Type\n" +
            "1,tt0111161,2020-01-01,,,The Shawshank Redemption,https://www.imdb.com/title/tt0111161/,Movie\n" +
            "2,tt0903747,2020-01-02,,,Breaking Bad,https://www.imdb.com/title/tt0903747/,TV Series\n"
        assertTrue(ImdbList.isCsv(csv))
        assertFalse(ImdbList.isCsv("<!DOCTYPE html><html>Sign in</html>"))
        assertEquals(listOf("tt0111161", "tt0903747"), ImdbList.ids(csv))
    }

    @Test
    fun readsAPageFromItsDataOrItsTitleLinks() {
        val withData = """<html><a href="/title/tt9999999/">Ad</a><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"items":[{"listItem":{"id":"tt0111161"}},{"listItem":{"id":"tt0068646"}}]}}</script></html>"""
        assertEquals(listOf("tt0111161", "tt0068646"), ImdbList.idsFromPage(withData))

        val linksOnly = """<a href="/title/tt0111161/?ref_=ls">A</a><a href="/title/tt0111161/">A</a><a href="/title/tt0068646/">B</a>"""
        assertEquals(listOf("tt0111161", "tt0068646"), ImdbList.idsFromPage(linksOnly))
    }
}
