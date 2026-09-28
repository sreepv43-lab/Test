package io.github.sreepv43.soundhub.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings in private SharedPreferences, each observable as a StateFlow. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    inner class Pref<T>(
        private val key: String,
        default: T,
        private val read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(prefs.read(key, default))
        val flow: StateFlow<T> = state.asStateFlow()
        val value: T get() = state.value

        fun set(value: T) {
            state.value = value
            prefs.edit().apply { write(key, value) }.apply()
        }
    }

    private fun string(key: String, default: String) =
        Pref(key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    private fun int(key: String, default: Int) = Pref(key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun bool(key: String, default: Boolean) =
        Pref(key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun long(key: String, default: Long) = Pref(key, default, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) })

    val username = string("username", "")
    val password = string("password", "")

    /** First port tried for incoming connections from other users (the next few are fallbacks). */
    val listenPort = int("listen_port", 2234)

    /** Ask the router (UPnP) to forward the listening port. */
    val upnp = bool("upnp", true)

    /** Absolute path of the folder downloads go to; empty for the first (internal) one. */
    val musicFolder = string("music_folder", "")

    /** How Dolby/DTS audio reaches the receiver: "auto", "hdmi" or "arc" (see AudioOutput). */
    val outputMode = string("output_mode", "auto")

    /** The last searches, newest first, one per line (typing on a TV is slow). */
    val recentSearches = string("recent_searches", "")

    /** The notification permission was explained and asked for once (on the first play or download). */
    val notificationsAsked = bool("notifications_asked", false)

    /** When the last crash report was mentioned at startup (so it is mentioned once). */
    val crashSeen = long("crash_seen", 0L)

    fun addRecentSearch(query: String) {
        val recent = listOf(query) + recentSearches.value.lines().filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
        recentSearches.set(recent.take(MAX_RECENT).joinToString("\n"))
    }

    private companion object {
        const val MAX_RECENT = 8
    }
}
