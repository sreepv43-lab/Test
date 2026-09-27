package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.Codec
import io.github.sreepv43.soundhub.audio.FormatFilter
import io.github.sreepv43.soundhub.slsk.SearchResponse
import io.github.sreepv43.soundhub.slsk.SharedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun namesFromTypicalSharePaths() {
        assertEquals(
            TrackName("Song Name", 1, "Album (2020) [FLAC]", "Artist"),
            PathNames.describe("@@abcde\\Music\\Artist - Album (2020) [FLAC]\\01 - Song Name.flac"),
        )
        assertEquals(
            TrackName("Intro", 3, "Album (CD1)", "Artist"),
            PathNames.describe("@@x\\Artist\\Album\\CD1\\1-03 Intro.mp3"),
        )
        // Generic folders are not artists.
        assertNull(PathNames.describe("@@x\\Music\\Album\\02. Track.flac").artist)
        assertEquals(TrackName("Loose", null, "Loose", null), PathNames.describe("Loose.mp3"))
    }

    @Test
    fun localPathsKeepArtistAndAlbumAndAreSafe() {
        assertEquals(
            "Artist/Album _2020_/01 Song.flac",
            PathNames.localPath("u", "@@x\\Music\\Artist\\Album :2020?\\01 Song.flac"),
        )
        assertEquals("Album/CD2/01.flac", PathNames.localPath("u", "@@x\\Music\\Album\\CD2\\01.flac"))
        assertEquals("u/song.mp3", PathNames.localPath("u", "song.mp3"))
    }

    @Test
    fun searchResultsGroupByFolderAndRankByAvailability() {
        val slow = SearchResponse(
            "slow", 1,
            listOf(
                SharedFile("@@s\\A - B\\02 Two.flac", 30_000_000, "", mapOf(1 to 200, 4 to 44_100, 5 to 16)),
                SharedFile("@@s\\A - B\\01 One.flac", 30_000_000, "", mapOf(1 to 200, 4 to 44_100, 5 to 16)),
                SharedFile("@@s\\A - B\\cover.jpg", 100_000),
            ),
            slotFree = false, avgSpeed = 50_000, queueLength = 20,
        )
        val fast = SearchResponse(
            "fast", 1,
            listOf(
                SharedFile("@@f\\A - B (Atmos)\\01 One.m4a", 20_000_000, "", mapOf(1 to 200)),
                SharedFile("@@f\\A - B [MP3]\\01 One.mp3", 8_000_000, "", mapOf(0 to 320)),
            ),
            slotFree = true, avgSpeed = 5_000_000, queueLength = 0,
        )
        val folders = SearchResults.group(listOf(slow, fast))
        assertEquals(listOf("fast", "fast", "slow"), folders.map { it.username })
        val flac = folders.last()
        assertEquals(listOf("One", "Two"), flac.tracks.map { it.name.title })
        assertEquals("FLAC 16/44.1", flac.summary(FormatFilter.ALL)?.label)
        assertEquals("B", flac.album)
        assertEquals("A", flac.artist)
        val atmos = folders.filter { it.matches(FormatFilter.ATMOS) }.single()
        assertEquals(Atmos.LIKELY, atmos.tracks.single().info.atmos)
        assertEquals(1, folders.count { it.matches(FormatFilter.MP3) })
    }

    @Test
    fun resultsKeepTheirPlaceWhenMoreAnswersArrive() {
        fun response(user: String, free: Boolean, speed: Int) = SearchResponse(
            user, 1, listOf(SharedFile("@@$user\\A - B\\01 One.mp3", 8_000_000, "", mapOf(0 to 320))),
            slotFree = free, avgSpeed = speed, queueLength = if (free) 0 else 5,
        )
        val first = SearchResults.group(listOf(response("slow", false, 10), response("ok", true, 100)))
        assertEquals(listOf("ok", "slow"), first.map { it.username })
        // A faster user answers later: listed after the rows already shown, not above them.
        val fresh = SearchResults.group(listOf(response("slow", false, 10), response("ok", true, 100), response("fast", true, 9_999)))
        assertEquals(listOf("fast", "ok", "slow"), fresh.map { it.username })
        assertEquals(listOf("ok", "slow", "fast"), SearchResults.merge(first, fresh).map { it.username })
    }

    @Test
    fun libraryPersistsAndGroupsAlbums() {
        val index = temp.root.resolve("library.json")
        val store = LibraryStore(index)
        val music = temp.newFolder("music", "Artist", "Album")
        fun track(n: Int) = LibraryTrack(
            id = LibraryStore.id("u", "@@u\\Album\\0$n.flac"),
            username = "u",
            remotePath = "@@u\\Album\\0$n.flac",
            path = music.resolve("0$n.flac").apply { writeText("x") }.path,
            size = 1,
            title = "Song $n",
            trackNumber = n,
            album = "Album",
            artist = "Artist",
            info = AudioFormats.classify("0$n.flac", 1, sampleRate = 96_000, bitDepth = 24),
            addedAt = n.toLong(),
        )
        store.add(track(2))
        store.add(track(1))
        store.add(track(1)) // replaces, doesn't duplicate

        val reloaded = LibraryStore(index)
        assertEquals(2, reloaded.tracks.value.size)
        val album = LibraryStore.albums(reloaded.tracks.value).single()
        assertEquals(listOf("Song 1", "Song 2"), album.tracks.map { it.title })
        assertTrue(album.matches(FormatFilter.HI_RES))
        assertEquals(Codec.FLAC, album.tracks.first().info.codec)

        reloaded.remove(listOf(track(1).id), deleteFiles = true)
        assertEquals(listOf("Song 2"), reloaded.tracks.value.map { it.title })
        assertTrue(!music.resolve("01.flac").exists())

        music.resolve("02.flac").delete()
        reloaded.pruneMissing()
        assertTrue(reloaded.tracks.value.isEmpty())
    }
}
