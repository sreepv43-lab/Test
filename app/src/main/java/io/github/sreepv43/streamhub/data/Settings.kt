package io.github.sreepv43.streamhub.data

import android.content.Context
import android.content.SharedPreferences
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.addon.StremioJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _downloadLocation = MutableStateFlow(
        prefs.getString(KEY_LOCATION, null)?.let {
            runCatching { StremioJson.decodeFromString(DownloadLocation.serializer(), it) }.getOrNull()
        }
    )
    val downloadLocation: StateFlow<DownloadLocation?> = _downloadLocation.asStateFlow()

    private val _theme = MutableStateFlow(prefs.getString(KEY_THEME, null) ?: "dark")
    /** Id of the colour theme (see ui.Palettes). */
    val theme: StateFlow<String> = _theme.asStateFlow()

    fun setTheme(id: String) {
        prefs.edit().putString(KEY_THEME, id).apply()
        _theme.value = id
    }

    fun setDownloadLocation(location: DownloadLocation) {
        prefs.edit().putString(KEY_LOCATION, StremioJson.encodeToString(DownloadLocation.serializer(), location)).apply()
        _downloadLocation.value = location
    }

    /** One stored preference, readable as a flow (for the UI) or directly. */
    inner class Setting<T>(
        private val key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(prefs.read(key, default))
        val flow: StateFlow<T> = state.asStateFlow()
        val value: T get() = state.value

        fun set(value: T) {
            prefs.edit().apply { write(key, value) }.apply()
            state.value = value
        }
    }

    private fun int(key: String, default: Int) =
        Setting(key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun bool(key: String, default: Boolean) =
        Setting(key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun string(key: String, default: String) =
        Setting(key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    private fun float(key: String, default: Float) =
        Setting(key, default, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })

    // Playback
    /** Sharpest picture to prefer when picking a stream (720, 1080 or 2160). */
    val maxResolution = int("max_resolution", 1080)
    /** ISO language code, or "" for the video's default audio. */
    val audioLanguage = string("audio_language", "")
    /** ISO language code, or "" for no subtitles. */
    val subtitleLanguage = string("subtitle_language", "")
    val subtitleScale = float("subtitle_scale", 1f)
    val autoplayNext = bool("autoplay_next", true)
    val introSkipSeconds = int("intro_skip_seconds", 85)
    /** Stream lists: one list best first, instead of grouped by addon. */
    val streamsBestFirst = bool("streams_best_first", false)

    // Downloads
    val downloadsWifiOnly = bool("downloads_wifi_only", false)
    /** 0 = no limit. */
    val downloadSpeedLimitKb = int("download_speed_limit_kb", 0)
    val deleteAfterWatching = bool("delete_after_watching", false)
    /** Torrent streaming cache limit in GB; 0 = no limit. */
    val torrentCacheLimitGb = int("torrent_cache_limit_gb", 5)

    private companion object {
        const val KEY_LOCATION = "download_location"
        const val KEY_THEME = "theme"
    }
}
