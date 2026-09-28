package io.github.sreepv43.soundhub.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer SoundHub build published on GitHub. */
data class AvailableUpdate(val build: Int, val name: String, val notes: String, val apkUrl: String, val size: Long)

@Serializable
private data class GithubAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/**
 * SoundHub builds are GitHub releases tagged `soundhub-build-N`, where N is also the app's
 * versionCode; the newest one with the same kind of APK as the installed app is the update.
 */
class Updates(private val repository: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Asks GitHub for the recent releases; null when there is nothing newer than [currentBuild]. */
    fun check(currentBuild: Int, apkName: String): AvailableUpdate? = newest(get("https://api.github.com/repos/$repository/releases?per_page=30"), currentBuild, apkName)

    fun newest(releasesJson: String, currentBuild: Int, apkName: String): AvailableUpdate? =
        json.decodeFromString<List<GithubRelease>>(releasesJson)
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { release ->
                val build = release.tag.removePrefix(TAG_PREFIX).takeIf { release.tag.startsWith(TAG_PREFIX) }?.toIntOrNull()
                val apk = release.assets.firstOrNull { it.name == apkName }
                if (build == null || apk == null) null
                else AvailableUpdate(build, release.name ?: release.tag, notesOf(release.body.orEmpty()), apk.url, apk.size)
            }
            .filter { it.build > currentBuild }
            .maxByOrNull { it.build }

    /** Downloads [update] to [target], reporting progress from 0 to 1. */
    fun download(update: AvailableUpdate, target: File, progress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val connection = open(update.apkUrl)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (update.size > 0 && partial.length() != update.size) {
            partial.delete()
            throw IOException("The download stopped early (${partial.length()} of ${update.size} bytes)")
        }
        if (!partial.renameTo(target)) throw IOException("Couldn't save the update")
    }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SoundHub")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException(if (code == 403) "GitHub is busy (try again in an hour)" else "GitHub answered $code")
        }
        return connection
    }

    companion object {
        const val TAG_PREFIX = "soundhub-build-"

        /** The "What's new" part of a release description (up to the separator line). */
        fun notesOf(body: String): String {
            val start = body.indexOf(WHATS_NEW)
            if (start < 0) return ""
            return body.substring(start + WHATS_NEW.length).substringBefore("\n---")
                .lines()
                .filterNot { TRAILER.matches(it.trim()) }
                .joinToString("\n")
                .trim()
        }

        /** Commit trailers ("Co-Authored-By: …") aren't news. */
        private val TRAILER = Regex("""^(Co-Authored-By|Claude-Session|Signed-off-by):.*""", RegexOption.IGNORE_CASE)

        private const val WHATS_NEW = "## What's new"
    }
}
