package io.github.sreepv43.streamhub.player

import io.github.sreepv43.streamhub.addon.Episodes
import io.github.sreepv43.streamhub.addon.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleShiftTest {
    @Test
    fun shiftsSrtLater() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello\n\n2\n00:59:59,800 --> 01:00:00,100\nBye\n"
        assertEquals(
            "1\n00:00:02,000 --> 00:00:03,500\nHello\n\n2\n01:00:00,300 --> 01:00:00,600\nBye\n",
            SubtitleShift.shift(srt, 500),
        )
    }

    @Test
    fun shiftsVttEarlierWithoutGoingNegative() {
        val vtt = "WEBVTT\n\n00:00.400 --> 00:02.000\nHi\n\n00:01:10.000 --> 00:01:12.000\nThere\n"
        assertEquals(
            "WEBVTT\n\n00:00.000 --> 00:01.000\nHi\n\n00:01:09.000 --> 00:01:11.000\nThere\n",
            SubtitleShift.shift(vtt, -1_000),
        )
    }

    @Test
    fun knowsWhichFilesItCanShift() {
        assertTrue(SubtitleShift.supports("https://x/sub.srt?download=1"))
        assertTrue(SubtitleShift.supports("https://x/sub.vtt"))
        assertFalse(SubtitleShift.supports("https://x/sub.ass"))
    }

    @Test
    fun nextEpisodeFollowsSeasonsAndSkipsSpecials() {
        val videos = listOf(
            Video(id = "s2e1", season = 2, episode = 1),
            Video(id = "s1e2", season = 1, episode = 2),
            Video(id = "s0e1", season = 0, episode = 1),
            Video(id = "s1e1", season = 1, episode = 1),
        )
        assertEquals("s1e2", Episodes.after(videos, "s1e1")?.id)
        assertEquals("s2e1", Episodes.after(videos, "s1e2")?.id)
        assertNull("the last regular episode doesn't roll into specials", Episodes.after(videos, "s2e1"))
        assertEquals("S1E2 · Episode 2", Episodes.label(videos[1]))
    }
}
