package io.github.sreepv43.soundhub.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import io.github.sreepv43.soundhub.server.MusicServer
import io.github.sreepv43.soundhub.server.ServerFile
import io.github.sreepv43.soundhub.slsk.GrowingFile
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Plays a song while Soulseek is still downloading it: reads wait until the bytes they need have
 * arrived. URIs look like `slskstream://transfer/<transfer id>/<file name>`.
 */
@OptIn(UnstableApi::class)
class TransferDataSource(private val resolve: (Long) -> GrowingFile?) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var data: GrowingFile? = null
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.pathSegments.firstOrNull()?.toLongOrNull()
            ?: throw FileNotFoundException("Bad stream address ${dataSpec.uri}")
        val growing = resolve(id) ?: throw FileNotFoundException("This download is no longer available")
        val total = growing.totalSize
        position = dataSpec.position
        if (total > 0 && position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total > 0 -> total - position
            else -> C.LENGTH_UNSET.toLong()
        }
        data = growing
        file = RandomAccessFile(growing.file, "r")
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val growing = data ?: return C.RESULT_END_OF_INPUT
        val available = growing.awaitAvailable(position)
        if (available <= position) return C.RESULT_END_OF_INPUT
        var count = minOf(length.toLong(), available - position)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) count = minOf(count, bytesRemaining)
        val source = file ?: return C.RESULT_END_OF_INPUT
        source.seek(position)
        val read = source.read(buffer, offset, count.toInt())
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        data = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Plays a song straight from the home server. URIs look like `smbmusic://server/<path on the server>`. */
@OptIn(UnstableApi::class)
class ServerDataSource(private val server: () -> MusicServer?) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: ServerFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val relative = dataSpec.uri.pathSegments.joinToString("/")
        val remote = server() ?: throw FileNotFoundException("The home server isn't set up")
        val source = try {
            remote.open(relative)
        } catch (e: Exception) {
            throw FileNotFoundException("Can't open the song on the home server: ${e.message}")
        }
        file = source
        position = dataSpec.position
        if (position > source.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else source.size - position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = file ?: return C.RESULT_END_OF_INPUT
        val read = source.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt(), position)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Sends `slskstream:` URIs to [TransferDataSource] and everything else (local files) to Media3's default. */
@OptIn(UnstableApi::class)
class SoundHubDataSourceFactory(
    context: Context,
    private val resolve: (Long) -> GrowingFile?,
    private val server: () -> MusicServer? = { null },
) : DataSource.Factory {
    private val defaults = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = Routing(defaults.createDataSource(), TransferDataSource(resolve), ServerDataSource(server))

    private class Routing(
        private val default: DataSource,
        private val transfer: TransferDataSource,
        private val remote: ServerDataSource,
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            default.addTransferListener(transferListener)
            transfer.addTransferListener(transferListener)
            remote.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = when (dataSpec.uri.scheme) {
                SCHEME -> transfer
                SERVER_SCHEME -> remote
                else -> default
            }
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }

    companion object {
        const val SCHEME = "slskstream"
        const val SERVER_SCHEME = "smbmusic"

        /** A song on the home server, by its path there (the file name keeps its extension for Media3). */
        fun serverUri(relative: String): Uri {
            val builder = Uri.Builder().scheme(SERVER_SCHEME).authority("server")
            relative.split('/').filter { it.isNotEmpty() }.forEach(builder::appendPath)
            return builder.build()
        }

        /** The file name is kept so Media3 can guess the container from the extension. */
        fun uriFor(transferId: Long, remotePath: String): Uri {
            val extension = remotePath.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
            return Uri.Builder()
                .scheme(SCHEME)
                .authority("transfer")
                .appendPath(transferId.toString())
                .appendPath(if (extension.isEmpty()) "track" else "track.$extension")
                .build()
        }
    }
}
