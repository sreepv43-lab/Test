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

/**
 * An available place to download to, with free space when known. [needsAccess] marks a drive
 * that is connected but can only be written after the user grants "All files access";
 * [problem] explains why a drive can't be written even with access (e.g. mounted read-only).
 */
data class StorageOption(
    val location: DownloadLocation,
    val freeBytes: Long?,
    val removable: Boolean,
    val needsAccess: Boolean = false,
    val problem: String? = null,
)

/**
 * Abstracts the two kinds of download destinations: folders picked through the Storage Access
 * Framework (any connected drive) and app folders on mounted volumes.
 */
class DownloadStorage(private val context: Context) {

    private companion object {
        const val DRIVE_FOLDER = "StreamHub"
    }

    /** Every mounted volume (internal, SD card, USB drives) plus folders the user has granted. */
    fun options(): List<StorageOption> {
        val appDirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)
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
        // Android TV usually doesn't expose USB drives through app folders or a folder picker, so
        // drives are also offered directly (a "StreamHub" folder at the drive's root).
        val drives = removableVolumeRoots().map { (root, label) ->
            val dir = File(root, DRIVE_FOLDER)
            val access = hasAllFilesAccess()
            StorageOption(
                location = DownloadLocation(DownloadLocation.Kind.DIRECTORY, dir.absolutePath, "$label › $DRIVE_FOLDER"),
                freeBytes = root.usableSpace,
                removable = true,
                needsAccess = !access,
                problem = if (access) writeProblem(dir) else null,
            )
        }
        return trees + drives + appDirs
    }

    /** Whether this app may write anywhere on shared storage, including USB drives (Android 11+). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Folders on connected drives the torrent engine may use as cache (when writable). */
    fun writableDriveFolders(child: String): List<File> =
        if (!hasAllFilesAccess()) emptyList()
        else removableVolumeRoots().map { (root, _) -> File(File(root, DRIVE_FOLDER), child) }
            .filter { canWriteDriveFolder(it) }

    private fun canWriteDriveFolder(dir: File): Boolean = hasAllFilesAccess() && writeProblem(dir) == null

    /**
     * Actually creates a small file, because File.canWrite() is unreliable on USB drives (e.g. NTFS
     * drives that the TV mounts read-only). Returns null when writing works, otherwise the reason.
     */
    private fun writeProblem(dir: File): String? {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return mountProblem(dir) ?: "can't create the ${dir.name} folder on this drive"
        }
        val probe = File(dir, ".streamhub-write-test")
        return try {
            FileOutputStream(probe).use { it.write(0) }
            null
        } catch (e: IOException) {
            val reason = e.message.orEmpty()
            mountProblem(dir) ?: when {
                "Permission denied" in reason || "EACCES" in reason ->
                    "permission denied (turn on All files access for StreamHub)"
                else -> reason.ifEmpty { "writing failed" }
            }
        } finally {
            probe.delete()
        }
    }

    /**
     * Explains a write failure from how the TV actually mounted the drive (/proc/mounts), e.g.
     * "NTFS, read-only". Null when the mount looks writable or can't be inspected.
     */
    private fun mountProblem(dir: File): String? {
        val root = removableVolumeRoots().map { it.first }.firstOrNull { dir.path.startsWith(it.path) } ?: return null
        val mount = runCatching { File("/proc/self/mounts").readLines() }.getOrDefault(emptyList())
            .map { it.split(' ') }
            .filter { it.size >= 4 }
            // The drive is mounted at /mnt/media_rw/<id> and exposed to apps at /storage/<id>.
            .firstOrNull { it[1] == "/mnt/media_rw/${root.name}" || it[1] == root.path }
            ?: return null
        val type = mount[2].lowercase()
        val readOnly = mount[3].split(',').any { it == "ro" }
        val format = when {
            "ntfs" in type || type == "fuseblk" || type == "tntfs" || type == "ufsd" -> "NTFS"
            "exfat" in type || type == "texfat" -> "exFAT"
            "vfat" in type || "fat" in type -> "FAT32"
            else -> type
        }
        return if (readOnly) {
            "this TV mounts the drive read-only ($format). Reformat it as exFAT on a computer to download to it"
        } else null
    }

    /** Null when downloads can be written to [location], otherwise a human-readable reason. */
    fun unavailableReason(location: DownloadLocation): String? = when (location.kind) {
        DownloadLocation.Kind.DIRECTORY -> {
            val dir = File(location.value)
            val onDrive = removableVolumeRoots().any { (root, _) -> dir.path.startsWith(root.path) }
            when {
                onDrive && !hasAllFilesAccess() -> "All files access is off (Settings → Download location → select the drive)"
                !onDrive && isOnRemovablePath(dir) -> "the drive is not connected"
                else -> writeProblem(dir)
            }
        }
        DownloadLocation.Kind.TREE ->
            if (DocumentFile.fromTreeUri(context, Uri.parse(location.value))?.canWrite() == true) null
            else "the folder is gone or access was removed"
    }

    private fun isOnRemovablePath(dir: File): Boolean =
        dir.path.startsWith("/storage/") && !dir.path.startsWith("/storage/emulated/")

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
