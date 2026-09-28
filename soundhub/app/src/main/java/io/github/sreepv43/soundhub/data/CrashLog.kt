package io.github.sreepv43.soundhub.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the error that last closed the app, so it can be read on the TV (Settings → About) and
 * reported. Nothing leaves the device.
 */
class CrashLog(context: Context) {
    private val file = File(context.filesDir, "last-crash.txt")
    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
                file.writeText("SoundHub $version · $time · ${thread.name}\n${error.stackTraceToString().take(MAX_CHARS)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report, or null when the app hasn't crashed since it was cleared. */
    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    /** When the saved report was written (0 if there is none). */
    fun writtenAt(): Long = if (file.isFile) file.lastModified() else 0L

    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_CHARS = 12_000
    }
}
