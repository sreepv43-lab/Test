package io.github.sreepv43.streamhub.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.sreepv43.streamhub.StreamHubApp
import io.github.sreepv43.streamhub.addon.Subtitle
import io.github.sreepv43.streamhub.data.WatchEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Full-screen ExoPlayer that works with a TV remote (D-pad / media keys) and touch. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var request: PlayRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PlayRequest.fromIntent(intent) ?: run { finish(); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            keepScreenOn = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerAutoShow = true
        }
        setContentView(playerView)
        initPlayer(savedInstanceState?.getLong(STATE_POSITION))
    }

    private fun initPlayer(savedPosition: Long?) {
        val container = (application as StreamHubApp).container
        val httpFactory = OkHttpDataSource.Factory(container.mediaHttp)
            .setDefaultRequestProperties(request.headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Playback error: ${error.errorCodeName}. Try another stream or an external player.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress()
                    finish()
                }
            }
        })
        player = exo
        playerView.player = exo

        val start = savedPosition ?: request.videoId?.let { container.history.positionFor(it) } ?: 0L
        exo.playWhenReady = true
        // Subtitle addons (e.g. OpenSubtitles) are asked first, but never hold playback up for long.
        lifecycleScope.launch {
            val type = request.type
            val videoId = request.videoId
            val addonSubtitles = if (type != null && videoId != null) {
                withTimeoutOrNull(SUBTITLE_TIMEOUT_MS) {
                    runCatching { container.addons.subtitles(type, videoId) }.getOrNull()
                }.orEmpty()
            } else emptyList()
            if (player !== exo) return@launch
            exo.setMediaItem(buildMediaItem(request.subtitles + addonSubtitles), start)
            exo.prepare()
        }
    }

    private fun buildMediaItem(subtitles: List<Subtitle>): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(request.url))
            .setSubtitleConfigurations(subtitles.distinctBy { it.url }.take(MAX_SUBTITLES).map { sub ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(subtitleMime(sub.url))
                    .setLanguage(sub.lang)
                    .setLabel(sub.lang)
                    .setSelectionFlags(0)
                    .build()
            })
            .build()

    private fun subtitleMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the player view handle D-pad and media keys (shows controls, seeks, play/pause).
        if (event.keyCode == KeyEvent.KEYCODE_BACK && playerView.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong(STATE_POSITION, it.currentPosition) }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun saveProgress() {
        val exo = player ?: return
        val metaId = request.metaId ?: return
        val videoId = request.videoId ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        (application as StreamHubApp).container.history.record(
            WatchEntry(
                metaId = metaId,
                type = request.type ?: "movie",
                name = request.title,
                poster = request.poster,
                videoId = videoId,
                videoTitle = request.subtitle,
                positionMs = exo.currentPosition,
                durationMs = duration,
            )
        )
    }

    companion object {
        private const val STATE_POSITION = "position"
        private const val MAX_SUBTITLES = 40
        private const val SUBTITLE_TIMEOUT_MS = 4_000L

        fun start(context: Context, request: PlayRequest) {
            context.startActivity(request.toIntent(context))
        }
    }
}

/** Everything the player needs, passed through Intent extras. */
data class PlayRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
    val metaId: String? = null,
    val type: String? = null,
    val videoId: String? = null,
    val poster: String? = null,
) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putExtra("url", url)
        .putExtra("title", title)
        .putExtra("subtitle", subtitle)
        .putExtra("headerNames", headers.keys.toTypedArray())
        .putExtra("headerValues", headers.values.toTypedArray())
        .putExtra("subUrls", subtitles.map { it.url }.toTypedArray())
        .putExtra("subLangs", subtitles.map { it.lang }.toTypedArray())
        .putExtra("metaId", metaId)
        .putExtra("type", type)
        .putExtra("videoId", videoId)
        .putExtra("poster", poster)

    companion object {
        fun fromIntent(intent: Intent): PlayRequest? {
            val url = intent.getStringExtra("url") ?: return null
            val names = intent.getStringArrayExtra("headerNames").orEmpty()
            val values = intent.getStringArrayExtra("headerValues").orEmpty()
            val subUrls = intent.getStringArrayExtra("subUrls").orEmpty()
            val subLangs = intent.getStringArrayExtra("subLangs").orEmpty()
            return PlayRequest(
                url = url,
                title = intent.getStringExtra("title").orEmpty(),
                subtitle = intent.getStringExtra("subtitle"),
                headers = names.zip(values).toMap(),
                subtitles = subUrls.zip(subLangs).map { (u, l) -> Subtitle(url = u, lang = l) },
                metaId = intent.getStringExtra("metaId"),
                type = intent.getStringExtra("type"),
                videoId = intent.getStringExtra("videoId"),
                poster = intent.getStringExtra("poster"),
            )
        }
    }
}
