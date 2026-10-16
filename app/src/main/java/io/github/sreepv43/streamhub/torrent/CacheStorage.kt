package io.github.sreepv43.streamhub.torrent

import java.io.File

/**
 * A place torrent data can be kept: internal storage, or one connected drive. [id] is
 * [CachePolicy.INTERNAL_ID] or the drive's filesystem UUID (e.g. "1A2B-3C4D"), which stays the same
 * when the drive is plugged into another port, unlike its mount path.
 */
data class CacheVolume(
    val id: String,
    val label: String,
    /** The torrent cache directory on this volume, or null when there is no writable one. */
    val dir: File?,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    /** "exFAT", "NTFS", "FAT32", "ext4"… when known. */
    val fileSystem: String? = null,
    /** Why the volume can't be written (read-only, no permission…), or null. */
    val problem: String? = null,
) {
    /** The largest single file this volume can hold, when limited (FAT32: 4 GB − 1 byte). */
    val maxFileBytes: Long? get() = if (fileSystem == "FAT32") CachePolicy.FAT32_MAX_FILE else null

    val usable: Boolean get() = dir != null && problem == null
}

/** Where new torrent data should go right now. */
sealed interface CacheChoice {
    data class Ready(val volume: CacheVolume, val dir: File) : CacheChoice

    /** [lowSpace]: the volume is fine but too full; removing unused cached torrents may help. */
    data class Unavailable(val message: String, val lowSpace: Boolean = false) : CacheChoice
}

/** Cleanup rules for one cache directory. 0 = no target. */
data class CacheRules(val targetBytes: Long = 0, val reserveBytes: Long = 0)

/**
 * Chooses the torrent cache location. Modes: [AUTO] (a usable drive with room, else internal
 * storage), [INTERNAL], or [USB] (only the chosen drive: never falls back to internal storage).
 */
object CachePolicy {
    const val AUTO = "auto"
    const val INTERNAL = "internal"
    const val USB = "usb"
    const val INTERNAL_ID = "internal"

    const val GB = 1L shl 30
    /** Space always left free on a drive (at most a tenth of a small drive). */
    const val EXTERNAL_RESERVE = 100 * GB
    /** Space always left free on internal storage, for Android and the apps. */
    const val INTERNAL_RESERVE = 3 * GB
    const val DEFAULT_EXTERNAL_TARGET_GB = 200
    const val FAT32_MAX_FILE = 4L * GB - 1

    fun reserveFor(volume: CacheVolume): Long =
        if (!volume.removable) INTERNAL_RESERVE
        else if (volume.totalBytes > 0) minOf(EXTERNAL_RESERVE, volume.totalBytes / 10) else EXTERNAL_RESERVE

    fun hasRoom(volume: CacheVolume): Boolean = volume.freeBytes >= reserveFor(volume)

    fun choose(mode: String, usbId: String?, usbLabel: String?, volumes: List<CacheVolume>): CacheChoice {
        val internal = volumes.firstOrNull { it.id == INTERNAL_ID }
        return when (mode) {
            INTERNAL -> internal?.let(::readyOrWhy) ?: CacheChoice.Unavailable("Internal storage is not available")
            USB -> {
                val name = usbLabel ?: "The USB drive"
                when {
                    usbId == null -> CacheChoice.Unavailable(
                        "No USB drive is chosen for the torrent cache. Choose one in Settings → Torrents.",
                    )
                    else -> volumes.firstOrNull { it.id == usbId }?.let(::readyOrWhy) ?: CacheChoice.Unavailable(
                        "$name (torrent cache) isn't connected. Connect it, or choose another torrent cache " +
                            "location in Settings → Torrents.",
                    )
                }
            }
            else -> {
                val drive = volumes.filter { it.removable && it.usable && hasRoom(it) }
                    // A drive that can hold large files first, then the one with the most room.
                    .sortedWith(compareBy<CacheVolume> { it.maxFileBytes != null }.thenByDescending { it.freeBytes })
                    .firstOrNull()
                when {
                    drive != null -> CacheChoice.Ready(drive, drive.dir!!)
                    internal != null -> readyOrWhy(internal)
                    else -> CacheChoice.Unavailable("No storage is available for torrents")
                }
            }
        }
    }

    private fun readyOrWhy(volume: CacheVolume): CacheChoice = when {
        volume.problem != null -> CacheChoice.Unavailable("${volume.label} can't be used for torrents: ${volume.problem}")
        volume.dir == null -> CacheChoice.Unavailable("${volume.label} has no folder StreamHub can write to")
        !hasRoom(volume) -> CacheChoice.Unavailable(
            "${volume.label} is almost full (${gb(volume.freeBytes)} free; StreamHub always leaves " +
                "${gb(reserveFor(volume))} free). Free up space or clear the torrent cache.",
            lowSpace = true,
        )
        else -> CacheChoice.Ready(volume, volume.dir)
    }

    /** Why a file of [size] can't be stored on [volume], or null if it can. */
    fun fileTooBig(volume: CacheVolume?, size: Long): String? {
        val max = volume?.maxFileBytes ?: return null
        if (size <= max) return null
        return "${volume.label} is formatted as FAT32, which can't hold files over 4 GB (this one is ${gb(size)}). " +
            "Format the drive as exFAT or NTFS, or choose another torrent cache location."
    }

    fun gb(bytes: Long): String = if (bytes >= 10 * GB) "${bytes / GB} GB" else "%.1f GB".format(bytes.toDouble() / GB)
}
