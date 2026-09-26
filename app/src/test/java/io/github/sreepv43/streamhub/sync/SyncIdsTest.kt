package io.github.sreepv43.streamhub.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncIdsTest {
    @Test
    fun moviesAndEpisodes() {
        assertEquals(TraktRef("tt0111161"), SyncIds.parse("tt0111161"))
        assertEquals(TraktRef("tt0903747", 5, 14), SyncIds.parse("tt0903747:5:14"))
    }

    @Test
    fun otherCataloguesAreSkipped() {
        assertNull(SyncIds.parse("kitsu:1376:3"))
        assertNull(SyncIds.parse("tt123:x:1"))
    }

    @Test
    fun types() {
        assertEquals("show", SyncIds.traktType("series"))
        assertEquals("movie", SyncIds.traktType("movie"))
        assertNull(SyncIds.traktType("channel"))
    }
}
