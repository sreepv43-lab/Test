package io.github.sreepv43.soundhub.library

/** Title, track number, album and artist as far as a shared file's path tells. */
data class TrackName(
    val title: String,
    val trackNumber: Int?,
    val album: String,
    val artist: String?,
)

/** Soulseek shares have no tags in search results, so names come from the folder layout. */
object PathNames {
    private val TRACK_PREFIX = Regex("""^(?:\d{1,2}-)?(\d{1,3})(?:\s*[-._)]\s*|\s+)(.+)$""")
    private val DISC_FOLDER = Regex("""^(cd|disc|disk)\s*\d+.*""", RegexOption.IGNORE_CASE)
    private val GENERIC_FOLDERS = setOf(
        "music", "mp3", "flac", "downloads", "download", "albums", "album", "share", "shared", "audio", "complete",
        "completed", "soulseek", "my music", "musique", "musik", "incoming", "lossless", "hi-res", "atmos",
    )
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun describe(remotePath: String): TrackName {
        val parts = remotePath.split('\\', '/').filter { it.isNotBlank() }
        val file = parts.lastOrNull() ?: remotePath
        var folders = parts.dropLast(1).filterNot { it.startsWith("@@") }
        var disc: String? = null
        if (folders.lastOrNull()?.matches(DISC_FOLDER) == true) {
            disc = folders.last()
            folders = folders.dropLast(1)
        }
        val albumFolder = folders.lastOrNull().orEmpty()
        val parent = folders.getOrNull(folders.size - 2)?.takeUnless { it.lowercase() in GENERIC_FOLDERS || it.length < 2 }
        val (folderArtist, album) = splitArtist(albumFolder)

        val base = file.substringBeforeLast('.')
        val match = TRACK_PREFIX.find(base)
        val trackNumber = match?.groupValues?.get(1)?.toIntOrNull()
        val title = (match?.groupValues?.get(2) ?: base).trim().ifEmpty { base }
        return TrackName(
            title = title,
            trackNumber = trackNumber,
            album = (album.ifEmpty { title }) + (disc?.let { " ($it)" } ?: ""),
            artist = folderArtist ?: parent,
        )
    }

    /** The remote folder a file is in (search results are grouped by it). */
    fun folderOf(remotePath: String): String = remotePath.substringBeforeLast('\\', "").ifEmpty { remotePath.substringBeforeLast('/', "") }

    /** Where a downloaded file goes under the music folder: "Artist/Album/01 Song.flac". */
    fun localPath(username: String, remotePath: String): String {
        val parts = remotePath.split('\\', '/').filter { it.isNotBlank() && !it.startsWith("@@") }
        val file = parts.lastOrNull() ?: "track"
        val folders = parts.dropLast(1)
        val hasDisc = folders.lastOrNull()?.matches(DISC_FOLDER) == true
        val albumIndex = folders.size - if (hasDisc) 2 else 1
        val tail = buildList {
            // The folder above the album is kept when it looks like an artist rather than "Music".
            folders.getOrNull(albumIndex - 1)?.takeUnless { it.lowercase() in GENERIC_FOLDERS }?.let(::add)
            folders.getOrNull(albumIndex)?.let(::add)
            if (hasDisc) add(folders.last())
        }.ifEmpty { listOf(username) }
        return (tail + file).joinToString("/") { safe(it) }
    }

    private fun splitArtist(folder: String): Pair<String?, String> {
        val i = folder.indexOf(" - ")
        if (i <= 0) return null to folder
        return folder.substring(0, i).trim() to folder.substring(i + 3).trim()
    }

    private fun safe(name: String): String =
        name.replace(UNSAFE, "_").trim().trim('.').take(120).ifEmpty { "_" }
}
