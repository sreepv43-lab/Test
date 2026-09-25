package io.github.sreepv43.streamhub.data

import android.content.Context
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

    private val _glassBlur = MutableStateFlow(prefs.getBoolean(KEY_GLASS_BLUR, true))
    /** Real background blur behind floating glass panels (Android 12+; costs GPU time). */
    val glassBlur: StateFlow<Boolean> = _glassBlur.asStateFlow()

    fun setGlassBlur(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLASS_BLUR, enabled).apply()
        _glassBlur.value = enabled
    }

    fun setDownloadLocation(location: DownloadLocation) {
        prefs.edit().putString(KEY_LOCATION, StremioJson.encodeToString(DownloadLocation.serializer(), location)).apply()
        _downloadLocation.value = location
    }

    private companion object {
        const val KEY_LOCATION = "download_location"
        const val KEY_GLASS_BLUR = "glass_blur"
    }
}
