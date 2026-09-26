package io.github.sreepv43.streamhub.data

import android.content.Context
import android.os.Build
import io.github.sreepv43.streamhub.BuildConfig
import java.io.File
import java.util.Date

/** Saves the error when the app crashes, so it can be shown (and shared) on the next start. */
object CrashReports {
    fun install(context: Context) {
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun dismiss(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    private fun report(thread: Thread, error: Throwable) = buildString {
        appendLine("StreamHub ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Time: ${Date()}  Thread: ${thread.name}")
        appendLine()
        append(error.stackTraceToString())
    }
}
