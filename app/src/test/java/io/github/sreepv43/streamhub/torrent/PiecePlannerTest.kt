package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.torrent.PiecePlanner.LOW
import io.github.sreepv43.streamhub.torrent.PiecePlanner.NORMAL
import io.github.sreepv43.streamhub.torrent.PiecePlanner.TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecePlannerTest {
    private val mb = 1024L * 1024
    // A 30 GB, 2 h 46 min film in 16 MB pieces: about 3 MB/s of video, about 5 s per piece.
    private val size = 30_000L * mb
    private val pieceLength = 16 * mb
    private val lastPiece = (size / pieceLength).toInt()
    private val bitrate = PiecePlanner.bitrate(size, 166 * 60_000L)

    private fun reader(id: Long, piece: Int, started: Long = 0, active: Long = 10_000, last: Int = lastPiece) =
        ReaderWindow(id, piece, last, started, active)

    private fun plan(readers: List<ReaderWindow>, window: Int = 60, has: (Int) -> Boolean = { false }) =
        PiecePlanner.plan(0, lastPiece, pieceLength, bitrate, window, readers, now = 10_000, has = has)

    @Test
    fun urgentPiecesGetDeadlinesAndTheReadAheadOnlyTopPriority() {
        val plan = plan(listOf(reader(1, 500)))
        val windowPieces = (60 * bitrate / pieceLength).toInt()
        assertTrue("60 s of this film is about a dozen pieces: $windowPieces", windowPieces in 10..14)

        assertEquals(TOP, plan.priorityOf(500))
        assertEquals(TOP, plan.priorityOf(500 + windowPieces - 1))
        assertEquals("further ahead: normal priority", NORMAL, plan.priorityOf(500 + windowPieces + 1))
        assertEquals(NORMAL, plan.priorityOf(lastPiece))
        assertEquals("already watched: low priority", LOW, plan.priorityOf(499))

        // Deadlines only for the next ~8 s, nearest first.
        assertEquals(setOf(500, 501), plan.deadlines.keys)
        assertTrue(plan.deadlines.getValue(500) < plan.deadlines.getValue(501))
        assertEquals(0, plan.deadlines.getValue(500))
    }

    @Test
    fun piecesAlreadyDownloadedGetNoDeadline() {
        val plan = plan(listOf(reader(1, 500)), has = { it == 500 })
        assertEquals(setOf(501), plan.deadlines.keys)
        assertEquals("the first missing piece is asked first", 0, plan.deadlines.getValue(501))
    }

    @Test
    fun aSeekMovesEverythingToTheNewPositionAndTheAbandonedRequestStopsSteering() {
        // The old request (piece 100) has received nothing for 5 s; the player now reads at 1500.
        val old = reader(1, 100, started = 1_000, active = 5_000)
        val new = reader(2, 1500, started = 9_000, active = 9_000)
        val plan = plan(listOf(old, new))
        assertEquals(TOP, plan.priorityOf(1500))
        assertFalse("the abandoned position is no longer top", plan.priorityOf(100) == TOP)
        assertTrue(plan.deadlines.keys.all { it >= 1500 })
    }

    @Test
    fun theIndexAtTheEndAndTheMainStreamAreBothServed() {
        // ExoPlayer reads the MKV cues near the end while the main request streams from the start.
        val main = reader(1, 0, started = 1_000, active = 9_900)
        val cues = reader(2, lastPiece - 1, started = 9_500, active = 9_900, last = lastPiece)
        val plan = plan(listOf(main, cues))
        assertEquals(TOP, plan.priorityOf(0))
        assertEquals(TOP, plan.priorityOf(lastPiece))
        assertTrue(0 in plan.deadlines && lastPiece - 1 in plan.deadlines)
        // The short range doesn't pull the read-ahead past what it asked for, nor reset the main one's.
        val ranged = PiecePlanner.plan(0, lastPiece, pieceLength, bitrate, 60, listOf(main, reader(3, 700, started = 9_600, active = 9_900, last = 700)), 10_000) { false }
        assertEquals(TOP, ranged.priorityOf(700))
        assertEquals(NORMAL, ranged.priorityOf(701))
        assertEquals(TOP, ranged.priorityOf(5))
    }

    @Test
    fun readAheadAdaptsTo60To120Seconds() {
        // Fast and steady peers (5× the bitrate): one minute is enough.
        assertEquals(60, PiecePlanner.windowSeconds(bitrate, List(10) { (bitrate * 5).toInt() }))
        // Barely keeping up: two minutes.
        assertEquals(120, PiecePlanner.windowSeconds(bitrate, List(10) { (bitrate * 1.2).toInt() }))
        // Fast on average but very bursty: more than one minute.
        val bursty = List(10) { if (it % 2 == 0) (bitrate * 10).toInt() else 0 }
        assertTrue(PiecePlanner.windowSeconds(bitrate, bursty) > 100)
        // Not measured yet: in between.
        assertEquals(90, PiecePlanner.windowSeconds(bitrate, emptyList()))
    }

    @Test
    fun bitrateComesFromSizeAndDuration() {
        assertEquals(30_000.0 * mb / (166 * 60), bitrate, 1.0)
        assertEquals("unknown duration: a two-hour film", size / 7200.0, PiecePlanner.bitrate(size, -1), 1.0)
    }

    @Test
    fun playableBufferIsTheGaplessStretchFromThePosition() {
        // Pieces 10..14 downloaded, 15 missing, 16.. downloaded again.
        val has = { p: Int -> p in 10..14 || p >= 16 }
        val from = 10 * pieceLength + 100
        assertEquals(5 * pieceLength - 100, PiecePlanner.contiguousBytes(from, 0, size, pieceLength, has))
        assertEquals(0L, PiecePlanner.contiguousBytes(15 * pieceLength, 0, size, pieceLength, has))
        // A file that starts inside a piece (after another file of the torrent).
        assertEquals(pieceLength - 1000, PiecePlanner.contiguousBytes(0, 1000, 2 * pieceLength, pieceLength) { it == 0 })
    }
}
