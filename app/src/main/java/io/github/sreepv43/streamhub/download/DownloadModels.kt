package io.github.sreepv43.streamhub.download

import kotlinx.serialization.Serializable

/**
 * Where downloads are written.
 * - [Kind.TREE]: a folder the user picked with the system folder picker (any USB drive, SD card or
 *   internal folder). [value] is the persisted tree URI.
 * - [Kind.DIRECTORY]: this app's folder on a mounted volume (works on TVs without a folder picker).
 *   [value] is an absolute path.
 */
@Serializable
data class DownloadLocation(
    val kind: Kind,
    val value: String,
    val label: String,
) {
    enum class Kind { TREE, DIRECTORY }
}

@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val suggestedFileName: String? = null,
    val location: DownloadLocation,
    /** file:// or content:// URI of the file once it has been created. */
    val fileUri: String? = null,
    val fileName: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
