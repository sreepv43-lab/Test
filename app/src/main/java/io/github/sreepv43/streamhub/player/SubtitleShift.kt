package io.github.sreepv43.streamhub.player

/**
 * Moves every timestamp of an SRT or WebVTT subtitle file by [offsetMs] (positive = later), for
 * fixing subtitles that are out of sync. Times can't go below zero.
 */
object SubtitleShift {
    fun shift(text: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        return TIMESTAMP.replace(text) { match ->
            val (hours, minutes, seconds, separator, millis) = match.destructured
            val total = (hours.ifEmpty { "0" }.toLong() * 3_600_000 + minutes.toLong() * 60_000 +
                seconds.toLong() * 1_000 + millis.padEnd(3, '0').take(3).toLong() + offsetMs).coerceAtLeast(0)
            val h = total / 3_600_000
            val m = total / 60_000 % 60
            val s = total / 1_000 % 60
            val ms = total % 1_000
            if (hours.isEmpty() && h == 0L) "%02d:%02d%s%03d".format(m, s, separator, ms)
            else "%02d:%02d:%02d%s%03d".format(h, m, s, separator, ms)
        }
    }

    /** Whether [shift] understands this subtitle file (by its URL). */
    fun supports(url: String): Boolean = url.substringBefore('?').lowercase().let { !it.endsWith(".ass") && !it.endsWith(".ssa") && !it.endsWith(".ttml") && !it.endsWith(".xml") }

    // hh:mm:ss,mmm (SRT) or [hh:]mm:ss.mmm (WebVTT)
    private val TIMESTAMP = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{1,3})""")
}
