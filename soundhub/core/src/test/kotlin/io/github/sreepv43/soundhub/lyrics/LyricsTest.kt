package io.github.sreepv43.soundhub.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LyricsTest {
    @Test
    fun parsesTimesOffsetsAndRepeatedLines() {
        val lines = Lrc.parse(
            """
            [ti:Song]
            [offset:500]
            [00:12.50] Hello
            [00:05.5][01:00.00]Chorus <00:05.60>line
            [00:30]
            """.trimIndent(),
        )
        assertEquals(listOf(5_000L, 12_000L, 29_500L, 59_500L), lines.map { it.timeMs })
        assertEquals("Chorus line", lines.first().text)
        assertEquals("Hello", lines[1].text)
        assertEquals("", lines[2].text)
    }

    @Test
    fun findsTheLineShowingAtAPosition() {
        val lines = Lrc.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, Lrc.lineAt(lines, 9_999))
        assertEquals(0, Lrc.lineAt(lines, 10_000))
        assertEquals(1, Lrc.lineAt(lines, 25_000))
        assertEquals(2, Lrc.lineAt(lines, 999_999))
    }

    @Test
    fun cleansTitlesForMatching() {
        assertEquals("Rainbowland", LyricsClient.cleanTitle("Rainbowland (feat. Dolly Parton)"))
        assertEquals("Hello", LyricsClient.cleanTitle("Hello - Remastered 2011"))
        assertEquals("Hello", LyricsClient.cleanTitle("Hello"))
    }

    @Test
    fun looksUpOnlineKeepsTheResultAndDoesNotAskAgainForMissingSongs() {
        val dir = Files.createTempDirectory("lyrics").toFile()
        try {
            var asked = 0
            val client = LyricsClient(fetch = { url ->
                asked++
                if ("get?" in url && "Known" in url) {
                    """{"instrumental":false,"plainLyrics":"la la","syncedLyrics":"[00:01.00]la\n[00:02.00]la"}"""
                } else {
                    null
                }
            })
            var clock = 1_000L
            val finder = LyricsFinder(LyricsStore(dir), client, now = { clock })
            val found = finder.find("a", "Artist", "Known", "Album", 200, null)!!
            assertTrue(found.isSynced)
            assertEquals(2, found.synced.size)
            val before = asked
            assertEquals(found, finder.find("a", "Artist", "Known", "Album", 200, null))
            assertEquals("a kept copy needs no request", before, asked)

            assertNull(finder.find("b", "Artist", "Unknown", null, 200, null))
            val afterMiss = asked
            assertNull(finder.find("b", "Artist", "Unknown", null, 200, null))
            assertEquals("a missing song isn't searched for again at once", afterMiss, asked)
            clock += 8L * 24 * 3600 * 1000
            finder.find("b", "Artist", "Unknown", null, 200, null)
            assertTrue(asked > afterMiss)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readsALrcFileNextToTheSongBeforeAskingOnline() {
        val dir = Files.createTempDirectory("lyrics").toFile()
        try {
            val song = File(dir, "x.flac").apply { writeBytes(byteArrayOf(1)) }
            File(dir, "x.lrc").writeText("[00:03.00]from file")
            val finder = LyricsFinder(LyricsStore(File(dir, "kept")), LyricsClient(fetch = { error("no network needed") }))
            val found = finder.find("s", "A", "T", null, null, song)!!
            assertEquals("from file", found.synced.single().text)
            assertFalse(found.isEmpty)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsSearchResultsOfAnotherLength() {
        val client = LyricsClient(fetch = { url ->
            if ("search" in url) """[{"duration":100.0,"plainLyrics":"wrong song"},{"duration":201.0,"plainLyrics":"right song"}]""" else null
        })
        assertEquals("right song", client.find("A", "T", null, 200)!!.plain)
    }
}
