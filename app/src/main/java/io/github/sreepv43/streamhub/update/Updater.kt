package io.github.sreepv43.streamhub.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.sreepv43.streamhub.BuildConfig
import io.github.sreepv43.streamhub.addon.StremioJson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Checks this app's GitHub releases for a newer build, downloads it and hands it to Android's installer. */
class Updater(private val context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: AvailableUpdate) : State
        data class Downloading(val update: AvailableUpdate, val progress: Float) : State
        data class Ready(val update: AvailableUpdate, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun check(): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val result = runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val release = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
                StremioJson.decodeFromString(GitHubRelease.serializer(), response.body!!.string())
            }
            UpdateCheck.pick(release, Build.SUPPORTED_ABIS.toList(), BuildConfig.BUILD_NUMBER)
        }.fold(
            { update -> if (update == null) State.UpToDate else State.Available(update) },
            { State.Failed("Couldn't check for updates: ${it.message}") },
        )
        _state.value = result
        result
    }

    suspend fun download(update: AvailableUpdate) = withContext(Dispatchers.IO) {
        _state.value = State.Downloading(update, 0f)
        _state.value = runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "streamhub-${update.build}.apk")
            http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: update.size
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) _state.value = State.Downloading(update, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            State.Ready(update, file)
        }.getOrElse { State.Failed("Couldn't download the update: ${it.message}") }
    }

    /** Opens Android's installer for [file]; returns a message for the user when it can't yet. */
    fun install(file: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(allow)
                "Allow StreamHub to install apps, then press Install again"
            } catch (e: ActivityNotFoundException) {
                "Allow \"Install unknown apps\" for StreamHub in the TV's settings (Apps → Special app access), then press Install again"
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(install)
            null
        } catch (e: ActivityNotFoundException) {
            "This device has no app installer"
        }
    }
}
