package io.github.sreepv43.streamhub.torrent

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * One position some reader of a torrent file needs: an HTTP request from the player (main stream,
 * or a short range for the MKV/MP4 index), or the player's own playback position.
 */
data class ReaderWindow(
    val id: Long,
    /** Piece the reader is at (or waiting for). */
    val piece: Int,
    /** Last piece of what it asked for (the end of its byte range, or of the file). */
    val lastPiece: Int,
    /** When the request started; the newest one is what the player wants now. */
    val startedAt: Long,
    /** When it last received data. */
    val lastActive: Long,
)

/** Priorities (libtorrent levels 0–7) for the pieces of a file, and deadlines (ms) for urgent pieces. */
data class PiecePlan(val firstPiece: Int, val priorities: IntArray, val deadlines: Map<Int, Int>) {
    fun priorityOf(piece: Int): Int = priorities[piece - firstPiece]

    override fun equals(other: Any?) =
        other is PiecePlan && firstPiece == other.firstPiece && priorities.contentEquals(other.priorities) && deadlines == other.deadlines

    override fun hashCode() = firstPiece * 31 + priorities.contentHashCode()
}

/**
 * Playback-aware piece scheduling, as plain arithmetic (no libtorrent), so it can be tested (with
 * `wholeFile = false`, like Stremio, nothing outside the readers' windows is downloaded at all):
 *  - urgent: the next few seconds after each reader get deadlines (libtorrent requests them from
 *    several peers at once, nearest first);
 *  - read-ahead: about 60–120 s of video after each reader get top priority, without deadlines;
 *  - the rest of the file (ahead of the earliest reader) downloads at normal priority, and what is
 *    behind it at low priority, so idle bandwidth still fills the cache without delaying playback.
 * A reader that has received nothing for a while while a newer one exists was abandoned (the player
 * seeked away and its request hasn't been closed yet): it no longer steers anything.
 */
object PiecePlanner {
    const val TOP = 7
    const val NORMAL = 4
    const val LOW = 1
    /** Not downloaded (libtorrent priority 0). */
    const val SKIP = 0

    const val MIN_WINDOW_S = 60
    const val MAX_WINDOW_S = 120
    const val URGENT_S = 8.0
    const val MIN_URGENT_PIECES = 2
    const val STALE_MS = 4_000L
    /** When the length of the video isn't known yet: assume a two-hour film. */
    const val FALLBACK_DURATION_S = 2 * 3600.0
    const val MIN_BITRATE = 256.0 * 1024

    /** Bytes per second of video, from the file size and the duration once the player knows it. */
    fun bitrate(fileSize: Long, durationMs: Long): Double {
        val seconds = if (durationMs > 0) durationMs / 1000.0 else FALLBACK_DURATION_S
        return maxOf(MIN_BITRATE, fileSize / seconds)
    }

    /**
     * How many seconds of video to keep at top priority: 60 s when peers deliver steadily and well
     * above the video's bitrate, up to 120 s when the speed fluctuates or barely keeps up.
     */
    fun windowSeconds(bitrate: Double, recentRates: List<Int>): Int {
        if (recentRates.size < 3) return (MIN_WINDOW_S + MAX_WINDOW_S) / 2
        val mean = recentRates.average()
        if (mean <= 0) return MAX_WINDOW_S
        val deviation = sqrt(recentRates.sumOf { (it - mean) * (it - mean) } / recentRates.size)
        val instability = (deviation / mean).coerceIn(0.0, 1.0)
        val headroom = mean / bitrate
        val seconds = when {
            headroom < 1.5 -> MAX_WINDOW_S.toDouble()
            else -> MIN_WINDOW_S + (MAX_WINDOW_S - MIN_WINDOW_S) * maxOf(instability, if (headroom < 3) 0.5 else 0.0)
        }
        return seconds.toInt().coerceIn(MIN_WINDOW_S, MAX_WINDOW_S)
    }

    /** Readers that steer the download: the newest one, and any other that received data recently. */
    fun active(readers: List<ReaderWindow>, now: Long): List<ReaderWindow> {
        val newest = readers.maxByOrNull { it.startedAt } ?: return emptyList()
        return readers.filter { it === newest || now - it.lastActive < STALE_MS }
            .sortedByDescending { it.startedAt }
    }

    fun plan(
        firstPiece: Int,
        lastPiece: Int,
        pieceLength: Long,
        bitrate: Double,
        windowSeconds: Int,
        readers: List<ReaderWindow>,
        now: Long,
        /** False: like Stremio, only the readers' windows are downloaded; the rest of the file isn't. */
        wholeFile: Boolean = true,
        has: (Int) -> Boolean,
    ): PiecePlan {
        val count = lastPiece - firstPiece + 1
        val active = active(readers, now)
        val earliest = active.minOfOrNull { it.piece.coerceIn(firstPiece, lastPiece) }
        val priorities = IntArray(count) { i ->
            when {
                !wholeFile -> SKIP
                earliest != null && firstPiece + i < earliest -> LOW
                else -> NORMAL
            }
        }
        val windowPieces = maxOf(MIN_URGENT_PIECES, ceil(windowSeconds * bitrate / pieceLength).toInt())
        val urgentPieces = maxOf(MIN_URGENT_PIECES, ceil(URGENT_S * bitrate / pieceLength).toInt()).coerceAtMost(windowPieces)
        // Time to play one piece; deadlines are spaced so the nearest piece is always asked first.
        val stepMs = (pieceLength / bitrate * 1000 / 2).toInt().coerceIn(50, 2_000)
        val deadlines = HashMap<Int, Int>()
        active.forEachIndexed { rank, reader ->
            val start = reader.piece.coerceIn(firstPiece, lastPiece)
            val end = minOf(reader.lastPiece, lastPiece, start + windowPieces - 1)
            for (p in start..end) priorities[p - firstPiece] = TOP
            val urgentEnd = minOf(end, start + urgentPieces - 1)
            // The newest reader is what is on screen next; older ones (e.g. the index at the end) follow.
            val offset = rank * 1_000
            var missing = 0
            for (p in start..urgentEnd) {
                if (has(p)) continue
                val deadline = offset + missing * stepMs
                deadlines[p] = minOf(deadlines[p] ?: Int.MAX_VALUE, deadline)
                missing++
            }
        }
        return PiecePlan(firstPiece, priorities, deadlines)
    }

    /**
     * Bytes that can be played without waiting from [fromByte] of a file starting at [fileOffset]
     * (in the torrent) of [fileSize] bytes, given which pieces are downloaded.
     */
    fun contiguousBytes(fromByte: Long, fileOffset: Long, fileSize: Long, pieceLength: Long, has: (Int) -> Boolean): Long {
        if (fromByte >= fileSize) return 0
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
        var piece = ((fileOffset + fromByte) / pieceLength).toInt()
        while (piece <= lastPiece && has(piece)) piece++
        val end = minOf((piece.toLong() * pieceLength) - fileOffset, fileSize)
        return (end - fromByte).coerceAtLeast(0)
    }
}
