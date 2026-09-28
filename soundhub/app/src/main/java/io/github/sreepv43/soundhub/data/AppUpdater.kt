package io.github.sreepv43.soundhub.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.update.AvailableUpdate
import io.github.sreepv43.soundhub.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    /** Android has to be told once that SoundHub may install apps (its own updates). */
    data class NeedsPermission(val update: AvailableUpdate) : UpdateState
    data class Installing(val update: AvailableUpdate) : UpdateState
    /** The new build is signed with another key: Android only installs it after SoundHub is removed. */
    data class NeedsReinstall(val update: AvailableUpdate) : UpdateState
    data class Failed(val message: String, val update: AvailableUpdate?) : UpdateState
}

/**
 * Finds newer SoundHub builds on GitHub, downloads one and hands it to Android's package installer
 * (which asks the listener to confirm). The app is replaced in place, keeping its library.
 */
class AppUpdater(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    private val updates = Updates(REPOSITORY)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentBuild: Int = runCatching {
        val info = packageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** The same kind of APK as the one installed (debug builds update to debug builds). */
    private val apkName: String =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "soundhub-debug.apk" else "soundhub-release.apk"

    /** Checks at most every few hours, quietly (for app start). */
    fun checkIfDue() {
        if (System.currentTimeMillis() - settings.updateCheckedAt.value > CHECK_EVERY_MS) check()
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val update = withContext(Dispatchers.IO) { updates.check(currentBuild, apkName) }
                settings.updateCheckedAt.set(System.currentTimeMillis())
                if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
            } catch (e: Exception) {
                Log.w("SoundHub", "Update check failed", e)
                UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}", null)
            }
        }
    }

    /** Downloads [update] (if not yet downloaded) and asks Android to install it. */
    fun install(update: AvailableUpdate) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        scope.launch {
            try {
                val apk = File(context.cacheDir, "updates/soundhub-${update.build}.apk")
                if (!apk.isFile) {
                    _state.value = UpdateState.Downloading(update, 0f)
                    withContext(Dispatchers.IO) {
                        apk.parentFile?.listFiles()?.forEach { it.delete() }
                        updates.download(update, apk) { _state.value = UpdateState.Downloading(update, it) }
                    }
                }
                if (!sameSigner(apk)) {
                    _state.value = UpdateState.NeedsReinstall(update)
                    return@launch
                }
                _state.value = UpdateState.Installing(update)
                withContext(Dispatchers.IO) { commit(apk, update) }
            } catch (e: Exception) {
                Log.w("SoundHub", "Update failed", e)
                _state.value = UpdateState.Failed("The update didn't install: ${e.message ?: e.javaClass.simpleName}", update)
            }
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for SoundHub. */
    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    internal fun installFinished(status: Int, message: String?) {
        val update = (_state.value as? UpdateState.Installing)?.update
        if (status == PackageInstaller.STATUS_SUCCESS) return
        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            update?.let { UpdateState.Available(it) } ?: UpdateState.Idle
        } else {
            UpdateState.Failed("Android didn't install the update${message?.let { ": $it" } ?: ""}", update)
        }
    }

    private fun commit(apk: File, update: AvailableUpdate) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("soundhub-${update.build}.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    /** True when the downloaded APK is signed like the installed app (otherwise Android refuses it). */
    private fun sameSigner(apk: File): Boolean {
        // When either can't be read, Android's installer makes the call (and says why if it refuses).
        val downloaded = signers(context.packageManager.packageArchiveInfo(apk.path)) ?: return true
        val installed = signers(packageInfo(context.packageName, signingFlag())) ?: return true
        return downloaded == installed
    }

    private fun signers(info: PackageInfo?): Set<String>? {
        info ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return signatures?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }
    }

    private fun PackageManager.packageArchiveInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
        else @Suppress("DEPRECATION") getPackageArchiveInfo(path, signingFlag())

    private fun packageInfo(name: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(name, flags)

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    companion object {
        const val REPOSITORY = "sreepv43-lab/Test"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}

/** Android's answer to an install: shows its confirmation screen, or reports how it ended. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        context.container.updater.installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
