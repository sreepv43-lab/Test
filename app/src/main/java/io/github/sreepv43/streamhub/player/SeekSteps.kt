package io.github.sreepv43.streamhub.player

import kotlin.math.absoluteValue

/**
 * Rewind / fast-forward with the remote. Presses in the same direction that follow each other
 * quickly (or holding the key) jump further each time: with "normal" acceleration the step is
 * ×1, ×3, ×6, ×12 the base step, with "fast" ×1, ×3, ×6, ×12, ×30, and "off" keeps the base step.
 */
class SeekSteps(private val clock: () -> Long) {
    private var direction = 0
    private var count = 0
    private var lastAt = Long.MIN_VALUE / 2

    /** Signed jump in ms for one press (or key repeat) in the given direction. */
    fun next(forward: Boolean, baseMs: Long, acceleration: String): Long {
        val now = clock()
        val dir = if (forward) 1 else -1
        if (dir != direction || now - lastAt > CHAIN_MS) count = 0
        direction = dir
        lastAt = now
        count++
        return dir * step(baseMs, count, acceleration)
    }

    /** Forgets the current run of presses (e.g. after the seek was done). */
    fun reset() {
        direction = 0
        count = 0
    }

    companion object {
        /** Presses further apart than this start again from the base step. */
        const val CHAIN_MS = 1_500L
        const val MAX_STEP_MS = 10 * 60_000L

        fun step(baseMs: Long, count: Int, acceleration: String): Long {
            val factor = when (acceleration) {
                "normal" -> when {
                    count <= 2 -> 1
                    count <= 4 -> 3
                    count <= 7 -> 6
                    else -> 12
                }
                "fast" -> when (count) {
                    1 -> 1
                    2 -> 3
                    3 -> 6
                    4, 5 -> 12
                    else -> 30
                }
                else -> 1
            }
            return (baseMs.absoluteValue * factor).coerceAtMost(MAX_STEP_MS)
        }

        /** "+1:30" / "−45 s" for the seek label. */
        fun describe(deltaMs: Long): String {
            val sign = if (deltaMs < 0) "−" else "+"
            val total = deltaMs.absoluteValue / 1000
            return if (total < 60) "$sign$total s" else "$sign%d:%02d".format(total / 60, total % 60)
        }
    }
}
