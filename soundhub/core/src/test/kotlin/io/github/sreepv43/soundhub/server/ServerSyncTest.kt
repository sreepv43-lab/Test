package io.github.sreepv43.soundhub.server

import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.audio.Codec
import io.github.sreepv43.soundhub.library.PathNames
import io.github.sreepv43.soundhub.library.LibraryStore
import io.github.sreepv43.soundhub.library.LibraryTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A "server" that is just a folder. */
private class FolderServer(val root: File) : MusicServer {
    var failing = false

    override fun test(): String? = if (failing) "down" else null

    override fun upload(local: File, relative: String) {
        if (failing) throw java.io.IOException("server is down")
        val target = File(root, relative)
        target.parentFile.mkdirs()
        local.copyTo(target, overwrite = true)
    }

    override fun exists(relative: String) = File(root, relative).isFile

    override fun open(relative: String): ServerFile {
        val file = java.io.RandomAccessFile(File(root, relative), "r")
        return object : ServerFile {
            override val size = file.length()

            override fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int {
                file.seek(position)
                return file.read(buffer, offset, length)
            }

            override fun close() = file.close()
        }
    }

    override fun delete(relative: String) {
        File(root, relative).delete()
    }
}

class ServerSyncTest {
    private lateinit var dir: File
    private lateinit var library: LibraryStore
    private lateinit var server: FolderServer
    private var mode = "copy"
    private var clock = 1_000_000L
    private val job = Job()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("serversync").toFile()
        library = LibraryStore(File(dir, "library.json"))
        server = FolderServer(File(dir, "server"))
    }

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    private fun sync() = ServerSync(library, { server }, { mode }, { File(dir, "music") }, CoroutineScope(job), now = { clock }, retryMs = 100, autoStart = false)

    private fun song(name: String, tagged: Boolean = true): LibraryTrack {
        val remote = "@@user\\Music\\Artist\\Album\\$name.flac"
        val username = "user"
        val track = LibraryTrack(
            id = LibraryStore.id(username, remote),
            username = username,
            remotePath = remote,
            path = "",
            size = 3,
            title = name,
            album = "Album",
            info = AudioInfo(Codec.FLAC, "flac"),
            addedAt = clock,
            tagged = tagged,
        )
        val local = File(dir, "music/${PathNames.localPath(username, remote)}")
        local.parentFile.mkdirs()
        local.writeBytes(byteArrayOf(1, 2, 3))
        File(local.parentFile, "cover.jpg").writeBytes(byteArrayOf(9))
        library.add(track.copy(path = local.path))
        return library.get(track.id)!!
    }

    @Test
    fun copyModeSendsTheSongAndItsCoverAndKeepsTheLocalFile() {
        val track = song("One")
        sync().syncNow()
        val saved = library.get(track.id)!!
        assertEquals("Artist/Album/One.flac", saved.serverPath)
        assertTrue(File(server.root, "Artist/Album/One.flac").isFile)
        assertTrue(File(server.root, "Artist/Album/cover.jpg").isFile)
        assertTrue(saved.isLocal)
    }

    @Test
    fun onlyModeDeletesTheLocalFileButTheSongStaysAvailable() {
        mode = "only"
        val track = song("Two")
        sync().syncNow()
        val saved = library.get(track.id)!!
        assertFalse(saved.isLocal)
        assertTrue(saved.available())
        assertTrue(library.missing().isEmpty())
        assertNotNull(server.open(saved.serverPath).also { it.close() })
    }

    @Test
    fun onlyModeWaitsForTagsBeforeSending() {
        mode = "only"
        song("Three", tagged = false)
        val sync = sync()
        sync.syncNow()
        assertEquals(0, sync.status.value.saved)
        clock += 60_000
        sync.syncNow()
        assertEquals(1, sync.status.value.saved)
    }

    @Test
    fun offSendsNothingAndAFailureIsReportedAndRetried() {
        mode = "off"
        val track = song("Four")
        val sync = sync()
        sync.syncNow()
        assertEquals("", library.get(track.id)!!.serverPath)
        mode = "copy"
        server.failing = true
        sync.syncNow()
        assertEquals("server is down", sync.status.value.error)
        assertEquals("", library.get(track.id)!!.serverPath)
        server.failing = false
        clock += 1_000
        sync.syncNow()
        assertNull(sync.status.value.error)
        assertTrue(library.get(track.id)!!.serverPath.isNotEmpty())
    }

    @Test
    fun deletingASongAlsoRemovesItsServerCopy() {
        val track = song("Five")
        val sync = sync()
        library.onDeleted = sync::deleteFromServer
        sync.syncNow()
        val saved = library.get(track.id)!!
        library.remove(listOf(saved.id), deleteFiles = true)
        assertFalse(server.exists(saved.serverPath))
    }
}
