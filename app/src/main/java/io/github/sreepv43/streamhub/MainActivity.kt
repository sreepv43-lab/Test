package io.github.sreepv43.streamhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
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
import io.github.sreepv43.streamhub.ui.screens.streamForLink
import java.io.File

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

    /**
     * Links from other apps: stremio:// addon links open the addon installer; magnet links,
     * .torrent files, video URLs and shared text open the "Open link" screen.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) {
            navRequest = Routes.DOWNLOADS
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            val link = text?.split(Regex("\\s+"))?.firstOrNull { streamForLink(it) != null }
            if (link != null) navRequest = Routes.link(link)
            return
        }
        val data = intent.data ?: return
        navRequest = when {
            data.scheme == "stremio" -> Routes.addons(install = data.toString())
            data.path?.endsWith("manifest.json") == true -> Routes.addons(install = data.toString())
            data.scheme == "content" && isTorrentFile(intent) -> copyTorrentFile(data)?.let { Routes.link(it) } ?: return
            else -> Routes.link(data.toString())
        }
    }

    private fun isTorrentFile(intent: Intent): Boolean =
        intent.type == "application/x-bittorrent" ||
            contentResolver.getType(intent.data!!) == "application/x-bittorrent" ||
            intent.data!!.lastPathSegment?.endsWith(".torrent", ignoreCase = true) == true

    /** content:// URIs are only readable while we hold the grant, so keep a private copy. */
    private fun copyTorrentFile(uri: Uri): String? = runCatching {
        val dir = File(filesDir, "opened-torrents").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.torrent")
        contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        Uri.fromFile(file).toString()
    }.getOrNull()

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
