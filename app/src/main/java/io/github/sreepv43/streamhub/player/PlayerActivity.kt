package io.github.sreepv43.streamhub.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ImageView
import android.widget.LinearLayout
import coil.load
import android.widget.TextView
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
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Full-screen ExoPlayer that works with a TV remote (D-pad / media keys) and touch. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var loadingView: View
    private lateinit var loadingStats: TextView
    private var firstFrameShown = false
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
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
        }
        loadingView = buildLoadingView()
        val root = FrameLayout(this).apply {
            addView(playerView)
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ).apply { setMargins(48, 48, 48, 48) },
            )
            addView(loadingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        initPlayer(savedInstanceState?.getLong(STATE_POSITION))
    }

    private fun initPlayer(savedPosition: Long?) {
        val container = (application as StreamHubApp).container
        val torrent = TorrentLinks.parseLogicalUrl(request.url)
        val httpFactory = OkHttpDataSource.Factory(if (torrent != null) container.torrentHttp else container.mediaHttp)
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

            override fun onRenderedFirstFrame() {
                firstFrameShown = true
                loadingView.visibility = View.GONE
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
            // Torrents are read through the built-in engine's local HTTP server.
            val url = if (torrent != null) {
                withContext(Dispatchers.IO) { container.torrentServer.urlFor(torrent.first, torrent.second) }
            } else {
                container.playableUrl(request.url)
            }
            exo.setMediaItem(buildMediaItem(url, request.subtitles + addonSubtitles), start)
            exo.prepare()
            if (start > RESUME_MIN_MS) {
                Toast.makeText(this@PlayerActivity, "Resuming from ${formatTime(start)}", Toast.LENGTH_SHORT).show()
            }
        }
        // Save the position regularly, not only on exit: turning the TV off kills the app without
        // giving it a chance to save.
        lifecycleScope.launch {
            while (isActive && player === exo) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (exo.isPlaying) saveProgress()
            }
        }
        showLoadingStatus(exo, torrent)
    }

    /**
     * Stremio-style loading screen: backdrop, title logo and a row of live stats. It covers the
     * player until the first frame; later rebuffering shows the same stats in a small label.
     */
    private fun buildLoadingView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            (request.background ?: request.poster)?.let { load(it) }
        }
        val scrim = View(this).apply { setBackgroundColor(0x8C000000.toInt()) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (request.logo != null) {
            column.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    load(request.logo)
                },
                LinearLayout.LayoutParams(dp(420), dp(130)),
            )
        } else {
            column.addView(TextView(this).apply {
                text = request.title
                setTextColor(Color.WHITE)
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }
        request.subtitle?.let { episode ->
            column.addView(TextView(this).apply {
                text = episode
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        loadingStats = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
            text = "Loading…"
        }
        column.addView(loadingStats)
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    /** "Peers 40   Speed 721 kB/s   Downloaded 17.6 MB   Completed 0.11%" with dim labels. */
    private fun statsLine(vararg pairs: Pair<String, String>): CharSequence {
        val out = SpannableStringBuilder()
        pairs.forEachIndexed { i, (label, value) ->
            if (i > 0) out.append("      ")
            val start = out.length
            out.append(label)
            out.setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("  ").append(value)
        }
        return out
    }

    /** Updates the loading screen (and the small rebuffering label) once a second. */
    private fun showLoadingStatus(exo: ExoPlayer, torrent: Pair<String, Int>?) {
        val engine = (application as StreamHubApp).container.torrents
        lifecycleScope.launch {
            while (isActive && player === exo) {
                val waiting = exo.playbackState != Player.STATE_READY
                val line: CharSequence = if (torrent != null) {
                    val stats = withContext(Dispatchers.IO) { engine.stats(torrent.first, torrent.second) }
                    when {
                        stats == null -> "Starting torrent…"
                        !stats.hasMetadata -> statsLine("Peers" to "${stats.peers}", "Status" to "Fetching torrent info…")
                        else -> statsLine(
                            "Peers" to "${stats.peers}",
                            "Speed" to Formatter.formatShortFileSize(this@PlayerActivity, stats.downloadRate.toLong()) + "/s",
                            "Downloaded" to Formatter.formatShortFileSize(this@PlayerActivity, stats.fileDownloadedBytes),
                            "Completed" to "%.2f%%".format(stats.fileProgress * 100),
                        )
                    }
                } else {
                    statsLine("Buffered" to "${exo.bufferedPercentage}%")
                }
                if (!firstFrameShown) {
                    loadingStats.text = line
                } else {
                    statusView.text = line
                    statusView.visibility = if (waiting) View.VISIBLE else View.GONE
                }
                delay(1_000)
            }
        }
    }

    private fun buildMediaItem(url: String, subtitles: List<Subtitle>): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(url))
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

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val RESUME_MIN_MS = 30_000L

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
    val background: String? = null,
    val logo: String? = null,
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
        .putExtra("background", background)
        .putExtra("logo", logo)

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
                background = intent.getStringExtra("background"),
                logo = intent.getStringExtra("logo"),
            )
        }
    }
}
