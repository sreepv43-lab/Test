package io.github.sreepv43.soundhub.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.sreepv43.soundhub.AppContainer
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.library.LibraryTrack
import io.github.sreepv43.soundhub.library.LibraryViews
import io.github.sreepv43.soundhub.library.SavedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * A song in the play queue: a library file, or a download being streamed ([transferId]).
 * [albumKey] is the album's folder in the library (where its cover is), when known.
 */
data class QueueItem(
    val id: String,
    val username: String,
    val remotePath: String,
    val title: String,
    val album: String,
    val artist: String?,
    val info: AudioInfo,
    val uri: String,
    val transferId: Long? = null,
    val albumKey: String? = null,
)

fun LibraryTrack.toQueueItem() = QueueItem(
    id = id,
    username = username,
    remotePath = remotePath,
    title = title,
    album = album,
    artist = artist,
    info = info,
    uri = Uri.fromFile(File(path)).toString(),
    albumKey = LibraryViews.albumKeyOf(path),
)

/** A sleep timer: pause at [endsAt] (epoch ms), or at the end of the current song. */
data class SleepTimer(val endsAt: Long?, val endOfSong: Boolean)

/** The app's one player (shared with the media session), its queue and what it outputs. */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context, private val container: AppContainer) {
    private val items = HashMap<String, QueueItem>()

    private val _current = MutableStateFlow<QueueItem?>(null)
    val current: StateFlow<QueueItem?> = _current.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)

    /** Position of the current song in [queue]. */
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _output = MutableStateFlow<OutputState?>(null)
    val output: StateFlow<OutputState?> = _output.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleep: StateFlow<SleepTimer?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

    private var inputFormat: Format? = null
    private var trackConfig: AudioSink.AudioTrackConfig? = null

    private val _player = MutableStateFlow(build(container.settings.outputMode.value))

    /** Replaced when the audio output mode changes, so the media session follows it. */
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    init {
        // Remember where we are, so Home can offer to resume after a restart.
        container.appScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (player.isPlaying) saveSession()
            }
        }
    }

    private fun build(mode: String): ExoPlayer {
        val exo = ExoPlayer.Builder(context, AudioOutput.renderersFactory(context, mode))
            .setMediaSourceFactory(DefaultMediaSourceFactory(SoundHubDataSourceFactory(context) { container.client.transfer(it)?.data }))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem?.mediaId?.let(items::get)
                _current.value = item
                _currentIndex.value = exo.currentMediaItemIndex
                inputFormat = null
                trackConfig = null
                _output.value = null
                if (item != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    container.appScope.launch(Dispatchers.IO) { container.collection.recordPlay(item.id, item.albumKey) }
                }
                if (_sleep.value?.endOfSong == true && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    exo.pause()
                    cancelSleepTimer()
                }
                saveSession()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying) saveSession()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) _error.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                _error.value = describe(error)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffle.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                syncQueue()
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                inputFormat = format
                if (format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC) markAtmos()
                updateOutput()
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                trackConfig = audioTrackConfig
                updateOutput()
            }
        })
        return exo
    }

    /** Replaces the queue with [queue] and plays it from [start] (at [positionMs]). */
    fun play(queue: List<QueueItem>, start: Int = 0, positionMs: Long = 0L, shuffle: Boolean = false) {
        if (queue.isEmpty()) return
        items.clear()
        queue.forEach { items[it.id] = it }
        _error.value = null
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(queue.map(::mediaItem), start.coerceIn(0, queue.lastIndex), positionMs)
        player.prepare()
        player.play()
        syncQueue()
        PlaybackService.start(context)
    }

    /** Adds songs right after the current one ([next]) or at the end of the queue. */
    fun enqueue(add: List<QueueItem>, next: Boolean) {
        if (add.isEmpty()) return
        if (player.mediaItemCount == 0) {
            play(add)
            return
        }
        add.forEach { items[it.id] = it }
        val index = if (next) player.currentMediaItemIndex + 1 else player.mediaItemCount
        player.addMediaItems(index.coerceIn(0, player.mediaItemCount), add.map(::mediaItem))
        syncQueue()
    }

    fun removeAt(index: Int) {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
        syncQueue()
    }

    /** Moves the song at [index] one place up ([delta] = -1) or down (+1). */
    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) player.moveMediaItem(index, to)
        syncQueue()
    }

    /** Moves the song at [index] to play right after the current one. */
    fun playNext(index: Int) {
        val target = player.currentMediaItemIndex + 1
        if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) {
            player.moveMediaItem(index, if (index < target) target - 1 else target)
        }
        syncQueue()
    }

    fun clearUpcoming() {
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        syncQueue()
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = player.seekToNextMediaItem()

    fun previous() = player.seekToPrevious()

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    val seekable: Boolean get() = player.isCurrentMediaItemSeekable

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    /** How much of the current song has downloaded (0..1), or null for songs already on disk. */
    fun downloadedFraction(): Float? {
        val id = current.value?.transferId ?: return null
        val data = container.client.transfer(id)?.data ?: return null
        if (data.complete || data.totalSize <= 0) return null
        return (data.available.toFloat() / data.totalSize).coerceIn(0f, 1f)
    }

    fun jumpTo(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    /** After a failed download is retried: start the song again. */
    fun retry() {
        current.value?.transferId?.let(container.client::retry)
        _error.value = null
        player.prepare()
        player.play()
    }

    fun setSleepTimer(minutes: Int?, endOfSong: Boolean = false) {
        cancelSleepTimer()
        if (minutes == null && !endOfSong) return
        if (endOfSong) {
            _sleep.value = SleepTimer(null, endOfSong = true)
            return
        }
        val endsAt = System.currentTimeMillis() + minutes!! * 60_000L
        _sleep.value = SleepTimer(endsAt, endOfSong = false)
        sleepJob = container.appScope.launch {
            delay(endsAt - System.currentTimeMillis())
            player.pause()
            _sleep.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleep.value = null
    }

    /** Resumes the saved session with the songs that are still in the library. Returns false if none are. */
    fun resume(session: SavedSession): Boolean {
        val tracks = session.trackIds.mapNotNull(container.library::get).filter { File(it.path).isFile && it.info.playable }
        if (tracks.isEmpty()) return false
        val currentId = session.trackIds.getOrNull(session.index)
        val start = tracks.indexOfFirst { it.id == currentId }
        play(tracks.map { it.toQueueItem() }, start.coerceAtLeast(0), if (start >= 0) session.positionMs else 0L, session.shuffle)
        player.repeatMode = session.repeatMode
        return true
    }

    /** Rebuilds the player with another output mode, keeping the queue and position. */
    fun setOutputMode(mode: String) {
        val old = player
        val media = (0 until old.mediaItemCount).map(old::getMediaItemAt)
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playing = old.playWhenReady
        val fresh = build(mode)
        fresh.shuffleModeEnabled = old.shuffleModeEnabled
        fresh.repeatMode = old.repeatMode
        if (media.isNotEmpty()) {
            fresh.setMediaItems(media, index, position)
            fresh.prepare()
            fresh.playWhenReady = playing
        }
        _player.value = fresh
        old.release()
    }

    /** Current format info: the library's or the download's (both learn from the file header). */
    fun infoFor(item: QueueItem): AudioInfo =
        container.library.get(item.id)?.info ?: item.transferId?.let { container.downloads.infos.value[it] } ?: item.info

    private fun syncQueue() {
        val exo = player
        _queue.value = (0 until exo.mediaItemCount).mapNotNull { items[exo.getMediaItemAt(it).mediaId] }
        _currentIndex.value = exo.currentMediaItemIndex
    }

    private fun saveSession() {
        val exo = player
        val ids = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }
        val inLibrary = ids.filter { container.library.get(it) != null }
        val session = if (inLibrary.isEmpty()) {
            null
        } else {
            val currentId = exo.currentMediaItem?.mediaId
            SavedSession(
                trackIds = inLibrary,
                index = inLibrary.indexOf(currentId).coerceAtLeast(0),
                positionMs = if (currentId in inLibrary) exo.currentPosition else 0L,
                shuffle = exo.shuffleModeEnabled,
                repeatMode = exo.repeatMode,
            )
        }
        container.appScope.launch(Dispatchers.IO) { container.collection.saveSession(session) }
    }

    private fun mediaItem(item: QueueItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.id)
        .setUri(item.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .build(),
        )
        .build()

    private fun markAtmos() {
        val item = current.value ?: return
        container.appScope.launch(Dispatchers.IO) { container.downloads.markAtmos(item.username, item.remotePath) }
    }

    private fun updateOutput() {
        val item = current.value
        val atmos = item?.let { infoFor(it).atmos == Atmos.VERIFIED } ?: false
        _output.value = AudioOutput.describe(inputFormat, trackConfig, atmos)
    }

    private fun describe(error: PlaybackException): String {
        val transfer = current.value?.transferId?.let(container.client::transfer)
        transfer?.error?.let { return "The download stopped: $it" }
        val cause = generateSequence(error.cause) { it.cause }.lastOrNull()?.message
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "This device can't play this format"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            -> "The file isn't a playable audio file"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file isn't there (is its drive connected?)"
            else -> cause ?: error.errorCodeName
        }
    }

    private companion object {
        const val SAVE_EVERY_MS = 15_000L
    }
}
