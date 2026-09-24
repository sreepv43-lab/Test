package io.github.sreepv43.streamhub

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
import io.github.sreepv43.streamhub.ui.AppRoot
import io.github.sreepv43.streamhub.ui.Routes
import io.github.sreepv43.streamhub.ui.StreamHubTheme

class MainActivity : ComponentActivity() {

    private var navRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        container.downloader.resumeInterrupted()
        requestNotificationPermission()

        setContent {
            StreamHubTheme {
                AppRoot(navRequest = navRequest, onNavRequestHandled = { navRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** stremio:// links (e.g. "Install" buttons on addon websites) open the addon installer. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        navRequest = when {
            intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true -> Routes.DOWNLOADS
            data != null && data.scheme == "stremio" -> Routes.addons(install = data.toString())
            data != null && data.path?.endsWith("manifest.json") == true -> Routes.addons(install = data.toString())
            else -> navRequest
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
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
