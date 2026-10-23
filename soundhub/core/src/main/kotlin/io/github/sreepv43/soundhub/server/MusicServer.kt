package io.github.sreepv43.soundhub.server

import java.io.Closeable
import java.io.File

/** Where a home server is and how to sign in to it (an SMB share, e.g. `\\nas\music`). */
data class ServerConfig(
    val host: String,
    val share: String,
    /** A folder inside the share to keep the music in ("" for the share's top). */
    val folder: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isSet: Boolean get() = host.isNotBlank() && share.isNotBlank()
}

/** One song on the server, open for reading at any position (what the player does while seeking). */
interface ServerFile : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]; -1 at the end of the file. */
    fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int
}

/**
 * A home server holding the music. Paths are relative to the music folder, with `/` between parts,
 * the same layout the music has on the device.
 */
interface MusicServer {
    /** Null when the server can be reached and written to, else what went wrong in a sentence. */
    fun test(): String?

    fun upload(local: File, relative: String)

    fun exists(relative: String): Boolean

    fun open(relative: String): ServerFile

    fun delete(relative: String)
}
