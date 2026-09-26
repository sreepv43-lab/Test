package io.github.sreepv43.soundhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.sreepv43.soundhub.ui.AppRoot
import io.github.sreepv43.soundhub.ui.Section
import io.github.sreepv43.soundhub.ui.SoundHubTheme

class MainActivity : ComponentActivity() {
    private var sectionRequest by mutableStateOf<Section?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()
        setContent {
            SoundHubTheme {
                AppRoot(sectionRequest = sectionRequest, onSectionRequestHandled = { sectionRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true -> sectionRequest = Section.NOW_PLAYING
            intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true -> sectionRequest = Section.DOWNLOADS
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
