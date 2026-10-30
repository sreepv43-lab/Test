package io.github.sreepv43.soundhub.lyrics

import kotlinx.serialization.Serializable

/** One line of a song's lyrics, shown from [timeMs] (milliseconds into the song). */
@Serializable
data class LyricLine(val timeMs: Long, val text: String)

/** A song's lyrics: [synced] lines with their times when known, else just [plain] text. */
@Serializable
data class Lyrics(val synced: List<LyricLine> = emptyList(), val plain: String = "", val source: String = "") {
    val isSynced: Boolean get() = synced.isNotEmpty()
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isBlank()
}

/** Reads `.lrc` lyrics: `[mm:ss.xx] words`, several times per line allowed, optional `[offset:ms]`. */
object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*(-?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            var rest = raw.trim()
            val times = ArrayList<Long>()
            while (true) {
                val match = stamp.find(rest)?.takeIf { it.range.first == 0 } ?: break
                val (min, sec, frac) = match.destructured
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                times += min.toLong() * 60_000 + sec.toLong() * 1_000 + ms
                rest = rest.substring(match.range.last + 1).trimStart()
            }
            // A line of words can carry per-word times (`<00:01.00>`): drop them.
            val words = rest.replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "").trim()
            times.forEach { lines += LyricLine((it - offset).coerceAtLeast(0), words) }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** The index of the line showing at [positionMs] (the last one that has started), or -1 before the first. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
