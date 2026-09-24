package io.github.sreepv43.streamhub.data

import android.content.Context
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.addon.StremioJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _streamingServerUrl = MutableStateFlow(prefs.getString(KEY_SERVER, null))
    /** Optional Stremio streaming server (Stremio Service on a PC/NAS) used for torrent streams. */
    val streamingServerUrl: StateFlow<String?> = _streamingServerUrl.asStateFlow()

    private val _downloadLocation = MutableStateFlow(
        prefs.getString(KEY_LOCATION, null)?.let {
            runCatching { StremioJson.decodeFromString(DownloadLocation.serializer(), it) }.getOrNull()
        }
    )
    val downloadLocation: StateFlow<DownloadLocation?> = _downloadLocation.asStateFlow()

    fun setStreamingServerUrl(url: String?) {
        val value = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        prefs.edit().putString(KEY_SERVER, value).apply()
        _streamingServerUrl.value = value
    }

    fun setDownloadLocation(location: DownloadLocation) {
        prefs.edit().putString(KEY_LOCATION, StremioJson.encodeToString(DownloadLocation.serializer(), location)).apply()
        _downloadLocation.value = location
    }

    private companion object {
        const val KEY_SERVER = "streaming_server"
        const val KEY_LOCATION = "download_location"
    }
}
