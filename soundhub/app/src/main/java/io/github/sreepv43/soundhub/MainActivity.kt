package io.github.sreepv43.soundhub

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sreepv43.soundhub.data.CrashLog
import io.github.sreepv43.soundhub.ui.AppRoot
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.SoundHubTheme
import io.github.sreepv43.soundhub.ui.currentPalette
import io.github.sreepv43.soundhub.ui.components.toast

class MainActivity : ComponentActivity() {
    private var sectionRequest by mutableStateOf<Section?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            mentionLastCrash()
        }
        setContent {
            val dark = currentPalette.dark
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            SoundHubTheme {
                AppRoot(sectionRequest = sectionRequest, onSectionRequestHandled = { sectionRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun mentionLastCrash() {
        val writtenAt = CrashLog(this).writtenAt()
        val settings = container.settings
        if (writtenAt > settings.crashSeen.value) {
            settings.crashSeen.set(writtenAt)
            toast(this, "SoundHub closed because of an error last time. The details are in Settings → About.")
        }
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true -> sectionRequest = Section.NOW_PLAYING
            intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true -> sectionRequest = Section.TRANSFERS
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
