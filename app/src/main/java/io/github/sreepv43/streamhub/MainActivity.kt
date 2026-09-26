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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.sreepv43.streamhub.data.CrashReports
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.AppRoot
import io.github.sreepv43.streamhub.ui.components.DialogShape
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.update.Updater
import kotlinx.coroutines.launch
import io.github.sreepv43.streamhub.ui.Routes
import io.github.sreepv43.streamhub.ui.Palettes
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
            val themeId by container.settings.theme.collectAsState()
            StreamHubTheme(Palettes.byId(themeId)) {
                AppRoot(navRequest = navRequest, onNavRequestHandled = { navRequest = null })
                var crash by remember { mutableStateOf(CrashReports.pending(this)) }
                crash?.let { report ->
                    CrashReportDialog(
                        report = report,
                        onShare = { shareText("StreamHub crash report", report) },
                        onClose = {
                            CrashReports.dismiss(this)
                            crash = null
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            val update = container.updater.check()
            if (update is Updater.State.Available) {
                StreamActions.toast(this@MainActivity, "StreamHub build ${update.update.build} is available: Settings → Updates")
            }
        }
    }

    private fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, subject)) }
            .onFailure { StreamActions.toast(this, "No app to share with; take a photo of the screen instead") }
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

/** Shown after the app crashed: the top of the error, which is what's needed to fix it. */
@Composable
private fun CrashReportDialog(report: String, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = DialogShape,
        containerColor = AppColors.panel,
        title = { Text("StreamHub closed unexpectedly") },
        text = {
            Column {
                Text(
                    "Sorry about that. Share this report (or take a photo of it) so the problem can be fixed.",
                    color = AppColors.textDim,
                )
                Text(
                    report.lines().take(CRASH_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { FlatButton(text = "Share", onClick = onShare, prominent = true) },
        dismissButton = { FlatButton(text = "Close", onClick = onClose) },
    )
}

private const val CRASH_LINES = 18
