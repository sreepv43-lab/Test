package io.github.sreepv43.soundhub.slsk

import kotlinx.coroutines.Job
import java.net.Socket

enum class TransferStatus { REQUESTING, QUEUED, CONNECTING, TRANSFERRING, COMPLETED, FAILED, CANCELLED }

/** An immutable snapshot of a download for the UI. [speed] is in bytes per second. */
data class TransferInfo(
    val id: Long,
    val username: String,
    val filename: String,
    val size: Long,
    val bytes: Long,
    val status: TransferStatus,
    val queuePosition: Int?,
    val speed: Long,
    val error: String?,
) {
    val active: Boolean get() = status != TransferStatus.COMPLETED && status != TransferStatus.FAILED && status != TransferStatus.CANCELLED
}

/** A download from one user. Its bytes land in [data], which the player can read while it grows. */
class Transfer internal constructor(
    val id: Long,
    val username: String,
    val filename: String,
    val data: GrowingFile,
) {
    @Volatile var status: TransferStatus = TransferStatus.REQUESTING
        internal set

    @Volatile var queuePosition: Int? = null
        internal set

    @Volatile var error: String? = null
        internal set

    @Volatile var speed: Long = 0
        internal set

    /** The token the uploader gave this transfer in its TransferRequest. */
    @Volatile internal var token: Int? = null
    @Volatile internal var socket: Socket? = null
    @Volatile internal var job: Job? = null
    @Volatile internal var attempts: Int = 0

    val size: Long get() = data.totalSize

    val info: TransferInfo
        get() = TransferInfo(id, username, filename, size, data.available, status, queuePosition, speed, error)
}
