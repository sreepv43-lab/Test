package io.github.sreepv43.streamhub.data

import android.content.Context

/**
 * Where each show's intro starts, learned from where the user pressed "Skip intro" (there is no
 * public database of intro times), so later episodes offer the button at the right moment.
 */
class IntroMemory(context: Context) {
    private val prefs = context.getSharedPreferences("intro_starts", Context.MODE_PRIVATE)

    fun startFor(metaId: String): Long? = prefs.getLong(metaId, -1).takeIf { it >= 0 }

    fun remember(metaId: String, startMs: Long) {
        prefs.edit().putLong(metaId, startMs).apply()
    }
}
