package io.github.sreepv43.streamhub.server

import java.io.File

/**
 * Whether a storage can be used right now. Each storage folder gets a small marker file the first
 * time it is seen; a folder without it is taken as an external drive that isn't mounted (its empty
 * mount point), so downloads never end up on the server's own system disk by mistake.
 */
object Storages {
    const val MARKER = ".streamhub-storage"

    fun isAvailable(storage: StorageDef): Boolean {
        val dir = File(storage.path)
        if (!dir.isDirectory || !dir.canWrite()) return false
        return File(dir, MARKER).isFile
    }

    /** Marks [storage] as a real, connected storage (called when it is configured while connected). */
    fun mark(storage: StorageDef): Boolean {
        val dir = File(storage.path)
        if (!dir.isDirectory || !dir.canWrite()) return false
        val marker = File(dir, MARKER)
        return marker.isFile || runCatching { marker.writeText("StreamHub storage \"${storage.label}\" (${storage.id})\n") }.isSuccess
    }

    fun unavailableMessage(storage: StorageDef): String =
        "${storage.label} isn't available (${storage.path}). Connect the drive; StreamHub won't store the download anywhere else."

    fun view(storage: StorageDef): StorageView {
        val dir = File(storage.path)
        val available = isAvailable(storage)
        return StorageView(
            id = storage.id,
            label = storage.label,
            path = storage.path,
            available = available,
            freeBytes = if (available) dir.usableSpace else 0,
            totalBytes = if (available) dir.totalSpace else 0,
        )
    }
}
