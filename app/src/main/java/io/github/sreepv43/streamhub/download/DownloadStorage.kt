package io.github.sreepv43.streamhub.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** An available place to download to, with free space when known. */
data class StorageOption(val location: DownloadLocation, val freeBytes: Long?, val removable: Boolean)

/**
 * Abstracts the two kinds of download destinations: folders picked through the Storage Access
 * Framework (any connected drive) and app folders on mounted volumes.
 */
class DownloadStorage(private val context: Context) {

    /** Every mounted volume (internal, SD card, USB drives) plus folders the user has granted. */
    fun options(): List<StorageOption> {
        val volumes = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .mapIndexed { index, dir ->
                dir.mkdirs()
                StorageOption(
                    location = DownloadLocation(DownloadLocation.Kind.DIRECTORY, dir.absolutePath, volumeLabel(dir, index)),
                    freeBytes = dir.usableSpace,
                    removable = index > 0,
                )
            }
        val trees = context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { permission ->
                val doc = DocumentFile.fromTreeUri(context, permission.uri)
                StorageOption(
                    location = DownloadLocation(
                        DownloadLocation.Kind.TREE,
                        permission.uri.toString(),
                        treeLabel(permission.uri, doc),
                    ),
                    freeBytes = null,
                    removable = true,
                )
            }
        return trees + volumes
    }

    fun defaultLocation(): DownloadLocation =
        options().firstOrNull { it.location.kind == DownloadLocation.Kind.DIRECTORY }?.location
            ?: DownloadLocation(
                DownloadLocation.Kind.DIRECTORY,
                File(context.filesDir, "downloads").absolutePath,
                "App storage",
            )

    fun isAvailable(location: DownloadLocation): Boolean = when (location.kind) {
        DownloadLocation.Kind.DIRECTORY -> File(location.value).let { (it.exists() || it.mkdirs()) && it.canWrite() }
        DownloadLocation.Kind.TREE -> DocumentFile.fromTreeUri(context, Uri.parse(location.value))?.canWrite() == true
    }

    /** Keeps write access to a folder chosen with ACTION_OPEN_DOCUMENT_TREE across reboots. */
    fun persistTree(uri: Uri): DownloadLocation {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        return DownloadLocation(DownloadLocation.Kind.TREE, uri.toString(), treeLabel(uri, DocumentFile.fromTreeUri(context, uri)))
    }

    fun releaseTree(location: DownloadLocation) {
        if (location.kind != DownloadLocation.Kind.TREE) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(location.value),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Creates an empty file and returns its URI string. */
    fun create(location: DownloadLocation, fileName: String): String = when (location.kind) {
        DownloadLocation.Kind.DIRECTORY -> {
            val dir = File(location.value).apply { mkdirs() }
            val file = uniqueFile(dir, fileName)
            if (!file.createNewFile() && !file.exists()) throw IOException("Cannot create ${file.absolutePath}")
            Uri.fromFile(file).toString()
        }
        DownloadLocation.Kind.TREE -> {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(location.value))
                ?: throw IOException("Folder is no longer available")
            // octet-stream keeps the display name (and extension) exactly as given.
            val doc = tree.createFile("application/octet-stream", fileName)
                ?: throw IOException("Cannot create $fileName in ${location.label}")
            doc.uri.toString()
        }
    }

    fun length(uri: String): Long {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") File(parsed.path!!).length()
        else DocumentFile.fromSingleUri(context, parsed)?.takeIf { it.exists() }?.length() ?: 0L
    }

    fun exists(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") File(parsed.path!!).exists()
        else DocumentFile.fromSingleUri(context, parsed)?.exists() == true
    }

    fun openOutput(uri: String, append: Boolean): OutputStream {
        val parsed = Uri.parse(uri)
        return if (parsed.scheme == "file") FileOutputStream(File(parsed.path!!), append)
        else context.contentResolver.openOutputStream(parsed, if (append) "wa" else "wt")
            ?: throw IOException("Cannot open $uri")
    }

    fun delete(uri: String) {
        val parsed = Uri.parse(uri)
        runCatching {
            if (parsed.scheme == "file") File(parsed.path!!).delete()
            else DocumentFile.fromSingleUri(context, parsed)?.delete()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    private fun volumeLabel(dir: File, index: Int): String {
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getSystemService(StorageManager::class.java)?.getStorageVolume(dir)?.getDescription(context)
        } else null
        return description ?: if (index == 0) "Internal storage" else "External drive ${index}"
    }

    private fun treeLabel(uri: Uri, doc: DocumentFile?): String {
        // Tree document ids look like "1234-ABCD:Movies" (volume:path) for local storage.
        val docId = uri.lastPathSegment.orEmpty()
        val volume = docId.substringBefore(':', "").let { if (it == "primary") "Internal storage" else it }
        val name = doc?.name ?: docId.substringAfter(':')
        return listOf(volume, name).filter { it.isNotBlank() }.joinToString(" › ").ifEmpty { uri.toString() }
    }
}
