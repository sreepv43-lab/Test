package io.github.sreepv43.soundhub.server

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A home server over SMB (Samba, Windows sharing, NAS boxes). Each operation opens its own connection. */
class SmbMusicServer(private val config: ServerConfig) : MusicServer {
    private class Opened(val client: SMBClient, val connection: Connection, val session: Session, val share: DiskShare) :
        AutoCloseable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private fun connect(): Opened {
        useBouncyCastle()
        val client = SMBClient(
            SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
        try {
            val connection = client.connect(config.host.trim())
            val (domain, user) = config.username.split('\\', limit = 2).let { if (it.size == 2) it[0] to it[1] else null to it[0] }
            val auth = if (user.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(user, config.password.toCharArray(), domain)
            }
            val session = connection.authenticate(auth)
            val share = session.connectShare(config.share.trim()) as? DiskShare
                ?: throw IllegalStateException("\"${config.share}\" isn't a file share")
            return Opened(client, connection, session, share)
        } catch (e: Throwable) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun path(relative: String): String =
        (config.folder.split('/', '\\') + relative.split('/', '\\')).filter { it.isNotBlank() }.joinToString("\\")

    override fun test(): String? = try {
        connect().use { opened ->
            val probe = path(".soundhub-test")
            ensureFolders(opened.share, probe)
            opened.share.openFile(
                probe,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            ).use { it.write(com.hierynomus.smbj.io.ArrayByteChunkProvider(byteArrayOf(1), 0)) }
            opened.share.rm(probe)
        }
        null
    } catch (e: Throwable) {
        describe(e)
    }

    override fun upload(local: File, relative: String) {
        connect().use { opened ->
            val target = path(relative)
            ensureFolders(opened.share, target)
            opened.share.openFile(
                target,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            ).use { remote ->
                remote.outputStream.use { out -> local.inputStream().use { it.copyTo(out, 256 * 1024) } }
            }
        }
    }

    override fun exists(relative: String): Boolean = connect().use { it.share.fileExists(path(relative)) }

    override fun open(relative: String): ServerFile {
        val opened = connect()
        try {
            val file = opened.share.openFile(
                path(relative),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val length = file.fileInformation.standardInformation.endOfFile
            return object : ServerFile {
                override val size = length

                override fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int =
                    file.read(buffer, position, offset, length)

                override fun close() {
                    runCatching { file.close() }
                    opened.close()
                }
            }
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    override fun delete(relative: String) {
        connect().use { opened ->
            val target = path(relative)
            if (opened.share.fileExists(target)) opened.share.rm(target)
            // Tidy up the album folder when it is empty now.
            val folder = target.substringBeforeLast('\\', "")
            if (folder.isNotEmpty() && folder != path("") && opened.share.folderExists(folder) &&
                opened.share.list(folder).none { it.fileName != "." && it.fileName != ".." }
            ) {
                runCatching { opened.share.rmdir(folder, false) }
            }
        }
    }

    private fun ensureFolders(share: DiskShare, file: String) {
        val parts = file.split('\\').dropLast(1)
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    private fun describe(e: Throwable): String {
        val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
        return when {
            "STATUS_LOGON_FAILURE" in text || "STATUS_ACCESS_DENIED" in text -> "The server refused the username or password"
            "STATUS_BAD_NETWORK_NAME" in text -> "The server has no share called \"${config.share}\""
            e is java.net.UnknownHostException -> "Can't find \"${config.host}\" on the network"
            e is java.net.ConnectException || e is java.net.SocketTimeoutException || "timed out" in text.lowercase() ->
                "Can't reach \"${config.host}\" (is it on, and on the same network?)"
            text.isNotEmpty() -> text
            else -> e.javaClass.simpleName
        }
    }

    private companion object {
        /** SMB signing and NTLM need the full Bouncy Castle, not Android's cut-down copy of it. */
        fun useBouncyCastle() {
            val current = Security.getProvider("BC")
            if (current is org.bouncycastle.jce.provider.BouncyCastleProvider) return
            Security.removeProvider("BC")
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }
}
