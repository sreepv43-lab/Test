package io.github.sreepv43.soundhub.library

import io.github.sreepv43.soundhub.audio.AudioFormats
import io.github.sreepv43.soundhub.audio.Channels
import io.github.sreepv43.soundhub.audio.CodecChoice
import io.github.sreepv43.soundhub.audio.MusicFilter
import io.github.sreepv43.soundhub.audio.Quality
import io.github.sreepv43.soundhub.slsk.SharedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun favouritesPlaylistsAndSessionSurviveARestart() {
        var clock = 1_000L
        val file = temp.root.resolve("collection.json")
        val store = CollectionStore(file) { clock++ }
        store.toggleFavouriteTrack("t1")
        store.toggleFavouriteAlbum("/music/A")
        val playlist = store.createPlaylist("  Evening  ", listOf("t1", "t2"))
        assertEquals(1, store.addToPlaylist(playlist.id, listOf("t3", "t1", "t3")))
        assertEquals(0, store.addToPlaylist(playlist.id, listOf("t1", "t2", "t3")))
        store.movePlaylistItem(playlist.id, 2, -1)
        store.removeFromPlaylist(playlist.id, 0)
        store.recordPlay("t1", "/music/A")
        store.recordPlay("t1", "/music/A")
        store.recordPlay("t2", "/music/B")
        store.saveSession(SavedSession(listOf("t1", "t2"), 1, 42_000, shuffle = true, repeatMode = 2))

        val reloaded = CollectionStore(file).data.value
        assertEquals(setOf("t1"), reloaded.favouriteTracks)
        assertEquals(setOf("/music/A"), reloaded.favouriteAlbums)
        assertEquals("Evening", reloaded.playlists.single().name)
        assertEquals(listOf("t3", "t2"), reloaded.playlists.single().trackIds)
        assertEquals("a song played twice in a row counts once", listOf("t2", "t1"), reloaded.history.map { it.trackId })
        assertEquals(42_000L, reloaded.session?.positionMs)
        assertTrue(reloaded.session!!.shuffle)

        store.toggleFavouriteTrack("t1")
        store.deletePlaylist(playlist.id)
        store.saveSession(null)
        val cleared = CollectionStore(file).data.value
        assertTrue(cleared.favouriteTracks.isEmpty())
        assertTrue(cleared.playlists.isEmpty())
        assertNull(cleared.session)
    }

    @Test
    fun releasesGroupTheSameAlbumFromDifferentUsersButKeepEditionsApart() {
        val folders = listOf(
            folder("alice", "Artist - Blue Sky (2020) [FLAC 24-96]", "flac", tracks = 10),
            folder("bob", "Artist - Blue Sky [MP3 320]", "mp3", tracks = 10),
            folder("carol", "Artist - Blue Sky (Deluxe Edition) [FLAC]", "flac", tracks = 14),
            folder("dave", "Artist - Blue Sky - 2020 - FLAC", "flac", tracks = 3, free = false),
            folder("erin", "Music", "mp3", tracks = 2),
            folder("fred", "Artist - Blue Sky (Dolby Atmos)", "m4a", tracks = 10),
        )
        val releases = Releases.group(folders)
        // The Atmos release stays separate (its badge says Atmos); format words leave the titles.
        assertEquals(listOf("Blue Sky", "Blue Sky (Deluxe Edition)", "Music", "Blue Sky"), releases.map { it.album })
        assertTrue(releases[3].key.endsWith("|atmos"))
        assertEquals(listOf("alice", "bob", "dave"), releases[0].sources.map { it.username })
        assertEquals(10, releases[0].trackCount)

        // Best source: a free slot and the whole album, then quality.
        assertEquals("alice", releases[0].bestSource(MusicFilter()).username)
        assertEquals("bob", releases[0].bestSource(MusicFilter(codec = CodecChoice.MP3)).username)
        assertTrue(releases[3].matches(MusicFilter(channels = Channels.ATMOS)))
        assertFalse(releases[0].matches(MusicFilter(channels = Channels.ATMOS)))
    }

    @Test
    fun cleanTitlesDropFormatTagsOnly() {
        assertEquals("Album", Releases.cleanTitle("Album (2020) [FLAC 24-96]"))
        assertEquals("Album (Live)", Releases.cleanTitle("Album (Live) [WEB 320]"))
        assertEquals("Album", Releases.cleanTitle("Album - 2019 - FLAC"))
        assertEquals("[FLAC]", Releases.cleanTitle("[FLAC]"))
    }

    @Test
    fun filtersCombineQualityChannelsAndFormat() {
        val flacHiRes = AudioFormats.classify("a.flac", 1, sampleRate = 96_000, bitDepth = 24)
        val mp3 = AudioFormats.classify("a.mp3", 1, bitrateKbps = 320)
        val atmos = AudioFormats.classify("Album (Atmos)\\a.m4a", 1)
        assertTrue(MusicFilter(quality = Quality.LOSSLESS).matches(flacHiRes))
        assertTrue(MusicFilter(quality = Quality.HI_RES, codec = CodecChoice.FLAC).matches(flacHiRes))
        assertFalse(MusicFilter(quality = Quality.LOSSLESS).matches(mp3))
        assertTrue(MusicFilter(quality = Quality.LOSSY, channels = Channels.STEREO).matches(mp3))
        assertTrue(MusicFilter(channels = Channels.ATMOS).matches(atmos))
        assertFalse(MusicFilter(channels = Channels.STEREO).matches(atmos))
        assertEquals("Lossless · Dolby Atmos", MusicFilter(Quality.LOSSLESS, Channels.ATMOS).label)
        assertEquals("All music", MusicFilter().label)
    }

    @Test
    fun libraryViewsSearchSortAndGroupByArtist() {
        fun track(n: Int, artist: String?, album: String, title: String, added: Long) = LibraryTrack(
            id = "t$n", username = "u", remotePath = "r$n", path = temp.root.resolve("$album/$n.mp3").path, size = 1,
            title = title, trackNumber = n, album = album, artist = artist,
            info = AudioFormats.classify("$n.mp3", 1), addedAt = added,
        )
        val tracks = listOf(
            track(1, "Zed", "Blue", "Morning Song", 3),
            track(2, "Abe", "Red", "Night Song", 1),
            track(3, null, "Grey", "Evening", 2),
        )
        assertEquals(listOf("Morning Song"), LibraryViews.search(tracks, "zed song").map { it.title })
        assertEquals(listOf("Evening", "Morning Song", "Night Song"), LibraryViews.songs(tracks, LibrarySort.TITLE).map { it.title })
        assertEquals(listOf("Morning Song", "Evening", "Night Song"), LibraryViews.songs(tracks, LibrarySort.RECENT).map { it.title })
        assertEquals(listOf("Abe", "Unknown artist", "Zed"), LibraryViews.artists(tracks).map { it.name })
        assertEquals(listOf("Blue", "Grey", "Red"), LibraryViews.albums(tracks, LibrarySort.TITLE).map { it.title })

        val albums = LibraryViews.albums(tracks, LibrarySort.RECENT)
        val history = listOf(
            PlayRecord("t2", LibraryViews.albumKeyOf(tracks[1].path), 5),
            PlayRecord("t1", LibraryViews.albumKeyOf(tracks[0].path), 4),
            PlayRecord("t2", LibraryViews.albumKeyOf(tracks[1].path), 3),
        )
        assertEquals(listOf("Red", "Blue"), LibraryViews.recentlyPlayed(history, albums).map { it.title })
    }

    @Test
    fun coversArePickedByName() {
        val dir = temp.newFolder("Album")
        dir.resolve("booklet.jpg").writeBytes(ByteArray(500))
        assertEquals("booklet.jpg", LibraryViews.coverIn(dir)?.name)
        dir.resolve("Folder.JPG").writeBytes(ByteArray(10))
        assertEquals("Folder.JPG", LibraryViews.coverIn(dir)?.name)
        assertNull(LibraryViews.coverIn(temp.newFolder("Empty")))
    }

    private fun folder(user: String, dir: String, ext: String, tracks: Int, free: Boolean = true): SearchFolder {
        val directory = "@@$user\\Music\\$dir"
        val list = (1..tracks).map { n ->
            val path = "$directory\\0$n - Song.$ext"
            val attributes = if (ext == "flac") mapOf(1 to 200, 4 to 96_000, 5 to 24) else mapOf(0 to 320, 1 to 200)
            val file = SharedFile(path, 10_000_000, "", attributes)
            SearchTrack(user, file, AudioFormats.classify(path, file.size, file.bitrate, file.durationSec, file.sampleRate, file.bitDepth), PathNames.describe(path))
        }
        return SearchFolder(user, directory, list, slotFree = free, avgSpeed = 100_000, queueLength = if (free) 0 else 4)
    }
}
