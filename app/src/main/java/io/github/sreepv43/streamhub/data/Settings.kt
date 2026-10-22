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

    /** Home rows the user hid ("Customize Home"): catalog keys (CatalogRef.key), one per line. */
    val hiddenHomeRows = string("hidden_home_rows", "")

    // Home Server (StreamHub Server on a computer or NAS), set up in Settings → Downloads.
    /** "192.168.1.100:8095"; empty = not set up. */
    val homeServerAddress = string("home_server_address", "")
    /** Key from pairing (sent with every request). */
    val homeServerToken = string("home_server_token", "")
    val homeServerName = string("home_server_name", "")

    /** "classic" or "cinema" (see ui.Interfaces). */
    val interfaceStyle = string("interface_style", "classic")

    // Playback
    /** Sharpest picture to prefer when picking a stream (720, 1080 or 2160). */
    val maxResolution = int("max_resolution", 1080)
    /** ISO language code, or "" for the video's default audio. */
    val audioLanguage = string("audio_language", "")
    /** ISO language code, or "" for no subtitles. */
    val subtitleLanguage = string("subtitle_language", "")
    val subtitleScale = float("subtitle_scale", 1f)
    /** Subtitles on a dark box instead of outlined text. */
    val subtitleBox = bool("subtitle_box", false)
    val autoplayNext = bool("autoplay_next", true)
    val introSkipSeconds = int("intro_skip_seconds", 85)
    /** Stream lists: one list best first, instead of grouped by addon. */
    val streamsBestFirst = bool("streams_best_first", false)
    /** How much video is buffered before playback starts (less = starts sooner, may pause more). */
    val startBufferMs = int("start_buffer_ms", 1_000)
    /** Start fetching the best torrents' details as soon as a stream list opens. */
    val prepareTorrents = bool("prepare_torrents", true)
    /** "auto" (what Android reports), "arc" or "earc": send surround audio undecoded to the receiver. */
    val surroundPassthrough = string("surround_passthrough", "auto")
    val seekBackSeconds = int("seek_back_seconds", 10)
    val seekForwardSeconds = int("seek_forward_seconds", 10)
    /** "off", "normal" or "fast": repeated or held rewind/forward presses jump further. */
    val seekAcceleration = string("seek_acceleration", "normal")
    /** Left/Right seek while the player controls are hidden (otherwise they show the controls). */
    val dpadSeeks = bool("dpad_seeks", true)
    /** Volume (%) of audio the app decodes itself; Dolby/DTS sent undecoded to a receiver is not affected. */
    val decodedVolumePercent = int("decoded_volume_percent", 100)

    /** TV home screen "Continue watching" row: "continue", "continue-list" (also My List) or "off". */
    val homeScreenRow = string("home_screen_row", "continue")

    // Downloads
    val downloadsWifiOnly = bool("downloads_wifi_only", false)
    /** 0 = no limit. */
    val downloadSpeedLimitKb = int("download_speed_limit_kb", 0)
    val deleteAfterWatching = bool("delete_after_watching", false)
    /** Torrent cache size on internal storage, in GB: 0 = no cache (removed after watching), -1 = unlimited. */
    val torrentCacheLimitGb = int("torrent_cache_limit_gb", 5)
    /**
     * Torrent cache location: "internal" (like Stremio, the default), "auto" or "usb" (only
     * [torrentCacheVolumeId], never elsewhere). A new key, so everyone starts on internal storage.
     */
    val torrentCacheMode = string("torrent_cache_mode_v2", "internal")
    /** While streaming, download the whole file in the background (otherwise only what is watched, like Stremio). */
    val torrentDownloadWholeFile = bool("torrent_download_whole_file", false)
    /** Filesystem UUID of the chosen USB drive (stays the same whichever port it is plugged into). */
    val torrentCacheVolumeId = string("torrent_cache_volume_id", "")
    /** Its name, to explain which drive is missing when it isn't connected. */
    val torrentCacheVolumeLabel = string("torrent_cache_volume_label", "")
    /** Automatically remove cached torrents nobody is using (over the target, or to keep the reserve free). */
    val torrentCacheCleanup = bool("torrent_cache_cleanup", true)
    /** Cleanup target of the torrent cache on a USB drive, in GB. */
    val torrentCacheUsbTargetGb = int("torrent_cache_usb_target_gb", 200)

    private companion object {
        const val KEY_LOCATION = "download_location"
        const val KEY_THEME = "theme"
    }
}
