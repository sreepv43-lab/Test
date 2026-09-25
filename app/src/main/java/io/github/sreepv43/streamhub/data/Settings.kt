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

    private companion object {
        const val KEY_LOCATION = "download_location"
        const val KEY_THEME = "theme"
    }
}
