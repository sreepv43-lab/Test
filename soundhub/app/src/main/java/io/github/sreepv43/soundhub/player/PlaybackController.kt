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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** A song in the play queue: a library file, or a download being streamed ([transferId]). */
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
)

/** The app's one player (shared with the media session), its queue and what it outputs. */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context, private val container: AppContainer) {
    private val items = HashMap<String, QueueItem>()

    private val _current = MutableStateFlow<QueueItem?>(null)
    val current: StateFlow<QueueItem?> = _current.asStateFlow()

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

    private var inputFormat: Format? = null
    private var trackConfig: AudioSink.AudioTrackConfig? = null

    private val _player = MutableStateFlow(build(container.settings.outputMode.value))

    /** Replaced when the audio output mode changes, so the media session follows it. */
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

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
                _current.value = mediaItem?.mediaId?.let(items::get)
                inputFormat = null
                trackConfig = null
                _output.value = null
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) _error.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                _error.value = describe(error)
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

    /** Plays [queue] from [start]. */
    fun play(queue: List<QueueItem>, start: Int = 0) {
        if (queue.isEmpty()) return
        items.clear()
        queue.forEach { items[it.id] = it }
        _queue.value = queue
        _error.value = null
        player.setMediaItems(queue.map(::mediaItem), start.coerceIn(0, queue.lastIndex), 0L)
        player.prepare()
        player.play()
        PlaybackService.start(context)
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = player.seekToNextMediaItem()

    fun previous() = player.seekToPrevious()

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

    /** Rebuilds the player with another output mode, keeping the queue and position. */
    fun setOutputMode(mode: String) {
        val old = player
        val media = (0 until old.mediaItemCount).map(old::getMediaItemAt)
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playing = old.playWhenReady
        val fresh = build(mode)
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
            else -> cause ?: error.errorCodeName
        }
    }
}
