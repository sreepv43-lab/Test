package io.github.sreepv43.streamhub.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON settings (also used for the files the server keeps). */
val ServerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** A place downloads are stored: a folder on the server's own disk, an external drive or a NAS volume. */
@Serializable
data class StorageDef(val id: String, val label: String, val path: String)

@Serializable
data class PairedDevice(val name: String, val token: String, val pairedAt: Long)

/** config.json in the server's data folder; storages and devices can also be edited there by hand. */
@Serializable
data class ServerConfig(
    val name: String = "StreamHub Home Server",
    val port: Int = DEFAULT_PORT,
    val storages: List<StorageDef> = emptyList(),
    /** Id of the storage new downloads go to when the app doesn't name one. */
    val defaultStorage: String? = null,
    val devices: List<PairedDevice> = emptyList(),
    /** How other players reach the downloads folder, e.g. "smb://192.168.1.100/Movies" (shown in the app). */
    val smbPath: String? = null,
    /**
     * The control page normally opens only on the server computer itself; true lets every device on
     * the home network open it (for a NAS without a screen).
     */
    val adminFromNetwork: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 8095
    }
}

@Serializable
data class StorageView(
    val id: String,
    val label: String,
    val path: String,
    val available: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

@Serializable
data class Hello(val app: String = "streamhub-server", val name: String, val version: String)

@Serializable
data class ServerStatus(
    val name: String,
    val version: String,
    val storages: List<StorageView>,
    val defaultStorage: String?,
    val smbPath: String?,
)

@Serializable
data class PairRequest(val code: String, val device: String = "StreamHub")

@Serializable
data class PairResponse(val token: String, val name: String)

@Serializable
data class ErrorResponse(val error: String)

/** A download request from the app (the same fields its own downloads keep). */
@Serializable
data class NewDownload(
    /** Magnet link or .torrent URL. */
    val source: String,
    /** File inside the torrent, or -1 for its largest video. */
    val fileIdx: Int = -1,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val storageId: String? = null,
)

/** What ffprobe reports about a video file. */
@Serializable
data class MediaInfo(
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    /** "HDR10", "HLG" or "Dolby Vision". */
    val hdr: String? = null,
    /** e.g. "EAC3 5.1". */
    val audio: String? = null,
    val durationMs: Long? = null,
)

/** One download done by the server, with its live numbers from libtorrent. */
@Serializable
data class Job(
    val id: String,
    val source: String,
    val fileIdx: Int,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val storageId: String,
    val infoHash: String? = null,
    val fileName: String? = null,
    val filePath: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val downloadRate: Long = 0,
    val uploadRate: Long = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val state: String = JobState.QUEUED,
    val error: String? = null,
    val paused: Boolean = false,
    val storageLabel: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val media: MediaInfo? = null,
)

/** Job states, as the app shows them. */
object JobState {
    const val QUEUED = "queued"
    const val METADATA = "metadata"
    const val CONNECTING = "connecting"
    const val DOWNLOADING = "downloading"
    const val STALLED = "stalled"
    const val PAUSED = "paused"
    const val CHECKING = "checking"
    const val COMPLETED = "completed"
    const val ERROR = "error"
    const val STORAGE_UNAVAILABLE = "storage_unavailable"
}

/** A playable file on the server: a finished download or a video found in a storage folder. */
@Serializable
data class LibraryItem(
    val id: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    /** From the file name, for files the server didn't download itself. */
    val parsedTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val media: MediaInfo? = null,
    val storageLabel: String,
    val available: Boolean,
    val fromDownload: Boolean,
)

/** A video file found by scanning a storage (library.json). */
@Serializable
data class ScannedFile(
    val id: String,
    val path: String,
    val storageId: String,
    val sizeBytes: Long,
    val modified: Long,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val media: MediaInfo? = null,
)

/** What the control page shows. */
@Serializable
data class AdminStatus(
    val name: String,
    val version: String,
    val port: Int,
    /** Addresses to type into StreamHub on the TV. */
    val addresses: List<String>,
    val pairingCode: String,
    val storages: List<StorageView>,
    val defaultStorage: String?,
    val devices: List<AdminDevice>,
    val libraryCount: Int,
    val smbPath: String?,
    val dataFolder: String,
    /** Null where starting with the computer isn't something the page can switch (not a Linux desktop). */
    val autostart: Boolean?,
    val ffprobe: Boolean,
    val windows: Boolean = false,
)

@Serializable
data class AdminDevice(val name: String, val pairedAt: Long)

@Serializable
data class FolderEntry(val name: String, val path: String)

@Serializable
data class FolderListing(val path: String, val parent: String?, val folders: List<FolderEntry>, val places: List<FolderEntry>)

@Serializable
data class AdminRequest(
    val id: String? = null,
    val label: String? = null,
    val path: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val deleteFile: Boolean = false,
    val pairedAt: Long? = null,
)
