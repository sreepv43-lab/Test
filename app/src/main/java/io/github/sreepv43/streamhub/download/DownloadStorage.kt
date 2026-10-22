package io.github.sreepv43.streamhub.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.sreepv43.streamhub.torrent.CachePolicy
import io.github.sreepv43.streamhub.torrent.CacheVolume
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        const val TORRENT_CACHE = "torrent-cache"
        const val CACHE_VOLUMES_MS = 30_000L
        // Long enough for a sleeping hard disk to spin up (they can take ~10 s).
        const val CHECK_TIMEOUT_S = 15L
        const val FIRST_SCAN_WAIT_MS = 20_000L
        const val FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1
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

    /**
     * Whether this app may write anywhere on shared storage, including USB drives (Android 11+).
     * Android throws here when the device has no primary shared storage at all.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    @Volatile private var cachedVolumes: Pair<Long, List<CacheVolume>>? = null
    private val scanLock = Any()
    private var scan: Future<List<CacheVolume>>? = null

    /**
     * Internal storage and connected drives as torrent cache places (see [scanVolumes]). Never
     * makes callers wait on a slow or stuck drive: the check runs in the background and the last
     * result is used meanwhile. Only when nothing has been checked yet does it wait, up to [waitMs]
     * (then internal storage only is returned).
     */
    fun cacheVolumes(fresh: Boolean = false, waitMs: Long = FIRST_SCAN_WAIT_MS): List<CacheVolume> {
        val cached = cachedVolumes
        if (cached != null && !fresh && SystemClock.elapsedRealtime() - cached.first < CACHE_VOLUMES_MS) return cached.second
        val running = synchronized(scanLock) {
            scan?.takeIf { !it.isDone } ?: checks.submit<List<CacheVolume>> {
                scanVolumes().also { cachedVolumes = SystemClock.elapsedRealtime() to it }
            }.also { scan = it }
        }
        if (cached != null) return cached.second
        return try {
            if (waitMs <= 0) listOf(internalVolume()) else running.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            listOf(internalVolume())
        }
    }

    /** Internal storage as a torrent cache place; never touches a drive. */
    fun internalVolume(): CacheVolume {
        val internalDir = File(context.getExternalFilesDir(null) ?: context.filesDir, TORRENT_CACHE)
        return CacheVolume(
            id = CachePolicy.INTERNAL_ID,
            label = "Internal storage",
            dir = internalDir.takeIf { it.isDirectory || it.mkdirs() },
            removable = false,
            totalBytes = internalDir.parentFile?.totalSpace ?: 0,
            freeBytes = internalDir.parentFile?.usableSpace ?: 0,
        )
    }

    /**
     * Internal storage and every connected drive as places for the torrent cache, with their space,
     * filesystem and whether StreamHub can really write there. On a drive, the app's own folder
     * (Android/data/…, no special permission) is preferred; a folder at the drive's root is used
     * only when that doesn't work and All files access is on. Checking writes a small file, so
     * results are remembered for [CACHE_VOLUMES_MS]. Slow: call off the UI thread.
     */
    @SuppressLint("NewApi") // removableVolumes() is empty before Android 7
    private fun scanVolumes(): List<CacheVolume> {
        val internal = internalVolume()
        // On some TVs these calls can hang on a USB drive; every check here gives up after a few seconds.
        val appDirs = bounded(CHECK_TIMEOUT_S, emptyList()) { ContextCompat.getExternalFilesDirs(context, null).filterNotNull() }
        val drives = removableVolumes().map { (volume, root, label) ->
            val mount = mountEntry(root)
            val readOnly = volume.state == Environment.MEDIA_MOUNTED_READ_ONLY || mount?.get(3)?.split(',')?.contains("ro") == true
            val appDir = appDirs.firstOrNull { it.path.startsWith(root.path + "/") }?.let { File(it, TORRENT_CACHE) }
            val rootDir = File(File(root, DRIVE_FOLDER), ".torrent-cache")
            // The app's own folder on the drive needs no permission, but on some TVs it isn't set up
            // for USB drives; with All files access the drive's StreamHub folder (as for downloads)
            // is tried first.
            val candidates = if (hasAllFilesAccess()) listOfNotNull(rootDir, appDir) else listOfNotNull(appDir)
            var lastProblem: String? = null
            val dir = if (readOnly) null else candidates.firstOrNull { candidate ->
                probe(candidate).also { lastProblem = it } == null
            }
            CacheVolume(
                id = volume.uuid ?: root.name,
                label = label,
                dir = dir,
                removable = true,
                totalBytes = bounded(CHECK_TIMEOUT_S, 0L) { root.totalSpace },
                freeBytes = bounded(CHECK_TIMEOUT_S, 0L) { (dir ?: root).usableSpace },
                fileSystem = mount?.get(2)?.let(::fileSystemName),
                problem = when {
                    readOnly -> "the TV mounts it read-only" + (mount?.get(2)?.let { " (${fileSystemName(it)})" } ?: "")
                    dir == null && !hasAllFilesAccess() ->
                        "StreamHub can't write to it (turn on All files access: Settings → Download location → select the drive)"
                    dir == null -> lastProblem ?: mountProblem(rootDir) ?: "writing to it failed"
                    else -> null
                },
            )
        }
        return listOf(internal) + drives
    }

    private val checks = Executors.newCachedThreadPool { r -> Thread(r, "storage-check").apply { isDaemon = true } }
    /** Write checks still running (a drive that stopped answering), so they aren't piled up. */
    private val runningProbes = ConcurrentHashMap<String, Future<String?>>()

    /** [block], or [default] if it doesn't finish within [seconds] (it keeps running in the background). */
    fun <T> bounded(seconds: Long, default: T, block: () -> T): T {
        val future = checks.submit<T> { block() }
        return try {
            future.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            default
        } catch (e: Exception) {
            default
        }
    }

    /** Whether [dir] can be written (null) or why not, giving up on a drive that doesn't answer. */
    private fun probe(dir: File): String? {
        val stuck = "the drive didn't answer (it may be asleep or busy); try again in a moment"
        runningProbes[dir.path]?.let { if (!it.isDone) return stuck }
        val future = checks.submit<String?> { writeProblem(dir) }
        runningProbes[dir.path] = future
        return try {
            future.get(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            stuck
        } catch (e: Exception) {
            e.cause?.message ?: "writing to it failed"
        }
    }

    private fun fileSystemName(type: String): String = type.lowercase().let { t ->
        when {
            "ntfs" in t || t == "fuseblk" || t == "tntfs" || t == "ufsd" -> "NTFS"
            "exfat" in t || t == "texfat" -> "exFAT"
            t == "vfat" || t == "msdos" || t == "fat" -> "FAT32"
            else -> t
        }
    }

    /** Free space for a download into [location] (null when it can't be told, e.g. a picked folder). */
    fun freeBytes(location: DownloadLocation): Long? =
        if (location.kind == DownloadLocation.Kind.DIRECTORY) File(location.value).let { dir ->
            generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace
        } else null

    /** Whether [location] is on a removable drive (otherwise internal storage). */
    fun isRemovable(location: DownloadLocation): Boolean =
        location.kind == DownloadLocation.Kind.TREE || isOnRemovablePath(File(location.value))

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
        val mount = mountEntry(dir) ?: return null
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

    /** The /proc/mounts fields (device, mount point, type, options…) of the removable drive holding [dir]. */
    private fun mountEntry(dir: File): List<String>? {
        val root = removableVolumeRoots().map { it.first }.firstOrNull { dir.path.startsWith(it.path) } ?: return null
        return runCatching { File("/proc/self/mounts").readLines() }.getOrDefault(emptyList())
            .map { it.split(' ') }
            .filter { it.size >= 4 }
            // The drive is mounted at /mnt/media_rw/<id> and exposed to apps at /storage/<id>.
            .firstOrNull { it[1] == "/mnt/media_rw/${root.name}" || it[1] == root.path }
    }

    /** The biggest file [location] can hold, when known (FAT32 drives stop at 4 GB). */
    fun maxFileSize(location: DownloadLocation): Long? {
        if (location.kind != DownloadLocation.Kind.DIRECTORY) return null
        val type = mountEntry(File(location.value))?.get(2)?.lowercase() ?: return null
        return if (type == "vfat" || type == "msdos") FAT32_MAX_FILE else null
    }

    /** Pushes what was written to the drive itself, so the file's length can be trusted. */
    fun sync(out: OutputStream) {
        if (out is FileOutputStream) runCatching { out.fd.sync() }
    }

    /** Mounted (also read-only) removable volumes with their root directory and name. */
    private fun removableVolumes(): List<Triple<StorageVolume, File, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes
            .filter { it.isRemovable && (it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY) }
            .mapNotNull { volume ->
                val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory
                } else {
                    runCatching { volume.javaClass.getMethod("getPathFile").invoke(volume) as File }.getOrNull()
                }
                root?.let { Triple(volume, it, volume.getDescription(context) ?: "USB drive") }
            }
    }

    /** Root directories and names of mounted removable drives (USB sticks/disks, SD cards). */
    private fun removableVolumeRoots(): List<Pair<File, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume ->
                val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory
                } else {
                    // StorageVolume.getPathFile() is hidden before Android 11.
                    runCatching { volume.javaClass.getMethod("getPathFile").invoke(volume) as File }.getOrNull()
                }
                root?.let { it to (volume.getDescription(context) ?: "USB drive") }
            }
    }

    fun defaultLocation(): DownloadLocation =
        options().firstOrNull { it.location.kind == DownloadLocation.Kind.DIRECTORY }?.location
            ?: DownloadLocation(
                DownloadLocation.Kind.DIRECTORY,
                File(context.filesDir, "downloads").absolutePath,
                "App storage",
            )

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
        // The Home Server checks its own storage.
        DownloadLocation.Kind.SERVER -> null
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
        DownloadLocation.Kind.SERVER -> throw IOException("Home Server downloads are stored on the server, not on the TV")
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
