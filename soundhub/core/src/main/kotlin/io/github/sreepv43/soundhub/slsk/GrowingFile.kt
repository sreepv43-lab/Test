package io.github.sreepv43.soundhub.slsk

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A file that is being downloaded front to back while it is played: readers block until the bytes
 * they need have arrived, the download finishes, or it fails.
 */
class GrowingFile(val file: File, totalSize: Long) {
    private val lock = ReentrantLock()

    /** The file's size; corrected if the uploader reports a different one. */
    @Volatile var totalSize: Long = totalSize
        internal set

    private val changed = lock.newCondition()

    @Volatile var available: Long = if (file.exists()) minOf(file.length(), totalSize) else 0L
        private set

    @Volatile var complete: Boolean = totalSize in 1..available
        private set

    @Volatile var failure: String? = null
        private set

    internal fun update(bytesOnDisk: Long) = lock.withLock {
        available = bytesOnDisk
        failure = null
        if (totalSize in 1..bytesOnDisk) complete = true
        changed.signalAll()
    }

    internal fun fail(reason: String) = lock.withLock {
        failure = reason
        changed.signalAll()
    }

    /** Clears a failure when the download is retried, so waiting readers keep waiting. */
    internal fun reset() = lock.withLock { failure = null }

    /**
     * Waits until there are bytes at [position] (returns how many bytes are on disk), or the file
     * is complete (returns its size, possibly <= position: end of file). Throws if the download
     * fails or the thread is interrupted.
     */
    fun awaitAvailable(position: Long): Long {
        lock.lock()
        try {
            while (available <= position && !complete) {
                failure?.let { throw IOException(it) }
                if (Thread.interrupted()) throw InterruptedIOException()
                try {
                    changed.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException()
                }
            }
            return available
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val WAIT_SLICE_MS = 250L
    }
}
