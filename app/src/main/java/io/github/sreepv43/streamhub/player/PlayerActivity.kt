package io.github.sreepv43.streamhub.player

import io.github.sreepv43.streamhub.torrent.TorrentEngine
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.graphics.Rect
import android.animation.ValueAnimator
import io.github.sreepv43.streamhub.download.DownloadItem
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.ImageButton
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.sreepv43.streamhub.R
import io.github.sreepv43.streamhub.StreamHubApp
import io.github.sreepv43.streamhub.addon.Episodes
import io.github.sreepv43.streamhub.addon.PlaybackTarget
import io.github.sreepv43.streamhub.addon.StreamRanking
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.addon.Video
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamFacts
import io.github.sreepv43.streamhub.torrent.CachePolicy
import io.github.sreepv43.streamhub.ui.Interfaces
import io.github.sreepv43.streamhub.ui.Palettes
import io.github.sreepv43.streamhub.ui.StreamHubTheme
import androidx.compose.ui.platform.ComposeView
import android.content.ClipData
import android.content.ClipboardManager
import java.util.Locale
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import io.github.sreepv43.streamhub.addon.Subtitle
import io.github.sreepv43.streamhub.data.WatchEntry
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.Dispatchers
import io.github.sreepv43.streamhub.torrent.TorrentStats
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
    private lateinit var titleBox: FrameLayout
    private lateinit var titleBase: View
    private lateinit var titleFill: View
    private var titlePulse: ValueAnimator? = null
    private var fillAnimation: ValueAnimator? = null
    private var titleFillShown = 0f
    private var firstFrameShown = false
    private var player: ExoPlayer? = null
    private lateinit var request: PlayRequest
    private val container get() = (application as StreamHubApp).container

    // Subtitles: size/timing options, and remembering the languages chosen in the player.
    private var mediaUrl: String? = null
    /** Latest torrent numbers and gapless ready time, for the Statistics window. */
    private var latestStats: TorrentStats? = null
    private var latestReadyMs: Long? = null
    private var subtitleSources: List<Subtitle> = emptyList()
    private var subtitleOffsetMs = 0L
    private val shiftedSubtitles = mutableMapOf<String, File>()
    private val subtitleTexts = mutableMapOf<String, String>()
    private var pendingSubtitleId: String? = null
    private var applyingOwnTrackSelection = false
    private var rememberTrackChoice = false

    // Up next / skip intro
    private lateinit var upNextView: TextView
    private lateinit var skipIntroView: TextView
    private lateinit var audioInfoView: TextView
    private lateinit var seekView: TextView
    private val seekSteps = SeekSteps { SystemClock.elapsedRealtime() }
    private var seekTarget: Long? = null
    private var seekFrom = 0L
    private var seekCommit: Job? = null
    private var lastSeekRepeatAt = 0L
    private var audioInfo: String? = null
    private var hideAudioInfo: Job? = null
    private var nextEpisode: Video? = null
    private var upNextShown = false
    private var upNextCancelled = false
    private var startingNext = false
    private var nextRequest: PlayRequest? = null
    private var nextResolve: Job? = null
    private var nextCountdown: Job? = null
    private var skipIntroDismissed = false
    private var markedWatched = false
    private var deletedAfterWatching = false
    private var restartedFromStart = false

    // Cinema interface: StreamHub's own controls (PlayerHud) instead of Media3's.
    private var hud: PlayerHudState? = null
    private var hudHide: Job? = null
    private var lastStatusLine = ""
    private var bufferingSince = 0L
    private var bufferingDismissed = false
    private var hudStreams: List<Stream> = emptyList()
    private var sourcesJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepAtEnd = false

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
            // Our controls layout (res/layout/exo_player_control_view.xml) fades as a whole; Media3's
            // bar-sliding animations assume its own layout.
            setControllerAnimationEnabled(false)
            // The audio line is shown with the player controls (and briefly when the audio starts).
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (visibility == View.VISIBLE) showAudioInfo(null) else if (hideAudioInfo == null) audioInfoView.visibility = View.GONE
                },
            )
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
        }
        loadingView = buildLoadingView()
        upNextView = overlayLabel()
        skipIntroView = overlayLabel().apply { text = "Skip intro   ▸ OK" }
        audioInfoView = overlayLabel().apply { textSize = 15f }
        seekView = overlayLabel().apply { textSize = 22f }
        val root = FrameLayout(this).apply {
            addView(playerView)
            val corner = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(64, 64, 64, 160) }
            addView(upNextView, corner)
            addView(
                audioInfoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply { setMargins(48, 48, 48, 48) },
            )
            addView(skipIntroView, FrameLayout.LayoutParams(corner))
            addView(
                seekView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { setMargins(48, 48, 48, 120) },
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { setMargins(48, 48, 48, 48) },
            )
            addView(loadingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        playerView.findViewById<TextView>(R.id.player_title)?.text = request.title
        playerView.findViewById<TextView>(R.id.player_subtitle)?.apply {
            text = request.subtitle.orEmpty()
            visibility = if (request.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        if (container.settings.interfaceStyle.value == Interfaces.CINEMA) {
            setUpHud(root)
        } else {
            addSubtitleOptionsButton()
            addStatisticsButton()
        }
        // Left/Right on the progress bar move by the forward step.
        resources.getIdentifier("exo_progress", "id", packageName).takeIf { it != 0 }
            ?.let { playerView.findViewById<View>(it) as? DefaultTimeBar }
            ?.setKeyTimeIncrement(container.settings.seekForwardSeconds.value * 1_000L)
        initPlayer(savedInstanceState?.getLong(STATE_POSITION))
        findNextEpisode()
    }

    /** A rounded, semi-transparent label in the bottom corner (up next, skip intro). */
    private fun overlayLabel() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(40, 24, 40, 24)
        background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(0xCC15171E.toInt())
            setStroke(2, 0x66FFFFFF)
        }
        visibility = View.GONE
    }

    private fun initPlayer(savedPosition: Long?) {
        val torrent = TorrentLinks.parseLogicalUrl(request.url)
        val httpFactory = OkHttpDataSource.Factory(if (torrent != null) container.torrentHttp else container.mediaHttp)
            .setDefaultRequestProperties(request.headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val settings = container.settings
        // Get the torrent going while subtitles are looked up.
        if (torrent != null) container.torrents.prefetch(torrent.first, torrent.second, warmStart = true)
        val passthrough = settings.surroundPassthrough.value
        val renderers = AudioOutput.renderersFactory(this, passthrough)
        val startBuffer = settings.startBufferMs.value
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                startBuffer,
                maxOf(startBuffer * 2, MIN_REBUFFER_MS),
            )
            .build()

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            // The player's own rewind/forward buttons.
            .setSeekBackIncrementMs(settings.seekBackSeconds.value * 1_000L)
            .setSeekForwardIncrementMs(settings.seekForwardSeconds.value * 1_000L)
            .build()
        exo.addAnalyticsListener(object : AnalyticsListener {
            private var input: Format? = null
            private var decoder: String? = null

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                // (The decoder is reported just before this, so it is kept.)
                input = format
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                decoder = decoderName
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                AudioOutput.describe(input, decoder, audioTrackConfig, forced = passthrough != "auto")?.let(::showAudioInfo)
            }
        })
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.value.ifEmpty { null })
            .setPreferredTextLanguage(settings.subtitleLanguage.value.ifEmpty { null })
            .build()
        applySubtitleStyle()
        exo.addListener(object : Player.Listener {
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                // Changes made with the player's audio/subtitle buttons become the new preference.
                if (firstFrameShown && !applyingOwnTrackSelection) rememberTrackChoice = true
            }

            override fun onTracksChanged(tracks: Tracks) {
                pendingSubtitleId?.let { id -> if (selectSubtitle(exo, tracks, id)) pendingSubtitleId = null }
                if (rememberTrackChoice) {
                    rememberTrackChoice = false
                    rememberLanguages(tracks)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // The resume point lies past what this file holds (an incomplete file, or a
                // different release than the one watched before): start from the beginning.
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE && !restartedFromStart) {
                    restartedFromStart = true
                    toast("Couldn't continue where you left off in this file (it may be incomplete); starting from the beginning")
                    exo.seekTo(0)
                    exo.prepare()
                    return
                }
                // A torrent whose storage is missing or full: say so, rather than a code.
                val storageProblem = torrent?.let { container.torrents.problemFor(it.first) }
                hud?.let { h ->
                    h.panel = null
                    h.problem = HudProblem(
                        if (storageProblem != null) "Storage unavailable" else "Unable to play this source",
                        storageProblem ?: "The TV couldn't play this file (${error.errorCodeName}). Another source, often a " +
                            "1080p one with H.264 video, usually works.",
                        buffering = false,
                    )
                    return
                }
                Toast.makeText(
                    this@PlayerActivity,
                    storageProblem ?: "Playback error: ${error.errorCodeName}. Try another stream or an external player.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onRenderedFirstFrame() {
                firstFrameShown = true
                loadingView.visibility = View.GONE
                titlePulse?.cancel()
                fillAnimation?.cancel()
                if (hud != null) showHud()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress()
                    deleteIfWatched()
                    if (sleepAtEnd) {
                        finish()
                        return
                    }
                    if (upNextShown && !upNextCancelled) playNext() else finish()
                }
            }
        })
        player = exo
        playerView.player = exo
        exo.volume = settings.decodedVolumePercent.value / 100f

        val start = savedPosition ?: request.videoId?.let { container.history.positionFor(it) } ?: 0L
        exo.playWhenReady = true
        // Subtitle addons (e.g. OpenSubtitles) are asked in parallel; playback only waits a moment for them.
        lifecycleScope.launch {
            val type = request.type
            val videoId = request.videoId
            val lookup: Deferred<List<Subtitle>>? = if (type != null && videoId != null) {
                async { runCatching { container.addons.subtitles(type, videoId) }.getOrDefault(emptyList<Subtitle>()) }
            } else null
            val early: List<Subtitle>? = if (lookup == null) emptyList() else withTimeoutOrNull(SUBTITLE_WAIT_MS) { lookup.await() }
            if (player !== exo) return@launch
            // Torrents are read through the built-in engine's local HTTP server.
            val url = if (torrent != null) {
                withContext(Dispatchers.IO) { container.torrentServer.urlFor(torrent.first, torrent.second) }
            } else {
                container.playableUrl(request.url)
            }
            mediaUrl = url
            subtitleSources = (request.subtitles + early.orEmpty()).distinctBy { it.url }.take(MAX_SUBTITLES)
            exo.setMediaItem(buildMediaItem(url, subtitleSources), start)
            exo.prepare()
            if (start > RESUME_MIN_MS) {
                Toast.makeText(this@PlayerActivity, "Resuming from ${formatTime(start)}", Toast.LENGTH_SHORT).show()
            }
            // Slow subtitle servers: add their subtitles once they answer, if the film has only just begun.
            if (lookup != null && early == null) {
                val late = lookup.await()
                if (player === exo && late.isNotEmpty() && exo.currentPosition < LATE_SUBTITLES_MAX_MS) {
                    val selectedId = selectedTextFormatId(exo.currentTracks)
                    pendingSubtitleId = subtitleSources.firstOrNull { sub -> selectedId != null && selectedId.endsWith(sub.url) }?.url
                    subtitleSources = (subtitleSources + late).distinctBy { it.url }.take(MAX_SUBTITLES)
                    exo.setMediaItem(buildMediaItem(url, subtitleSources), exo.currentPosition)
                    exo.prepare()
                }
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
        lifecycleScope.launch {
            while (isActive && player === exo) {
                updateUpNextAndSkipIntro(exo)
                hud?.let { updateHud(exo, it) }
                delay(500)
            }
        }
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
        // The title (logo or name) twice: a dim copy, and a full-colour copy revealed from left to
        // right as the video loads (see showLoadingProgress).
        fun titleView(fill: Boolean): View = if (request.logo != null) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                load(request.logo)
            }
        } else {
            TextView(this).apply {
                text = request.title
                setTextColor(if (fill) FILL_COLOR else Color.WHITE)
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        }
        titleBase = titleView(fill = false)
        titleFill = titleView(fill = true).apply { clipBounds = Rect(0, 0, 0, 0) }
        titleBox = FrameLayout(this).apply {
            val size = if (request.logo != null) {
                FrameLayout.LayoutParams(dp(420), dp(130))
            } else {
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(titleBase, size)
            addView(titleFill, FrameLayout.LayoutParams(size))
        }
        column.addView(titleBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showConnecting()
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

    /** Connecting: the title pulses slowly. */
    private fun showConnecting() {
        if (titlePulse != null) return
        titleBase.alpha = 1f
        titlePulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { titleBox.alpha = it.animatedValue as Float }
            start()
        }
    }

    /** Loading: the dim title fills with colour from left to right, up to [fraction] (0..1). */
    private fun showLoadingProgress(fraction: Float) {
        titlePulse?.cancel()
        titlePulse = null
        titleBox.alpha = 1f
        titleBase.alpha = 0.35f
        val target = fraction.coerceIn(0f, 1f).coerceAtLeast(titleFillShown)
        val from = titleFillShown
        titleFillShown = target
        fillAnimation?.cancel()
        fillAnimation = ValueAnimator.ofFloat(from, target).apply {
            duration = FILL_STEP_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val width = titleFill.width
                if (width > 0) titleFill.clipBounds = Rect(0, 0, (width * (it.animatedValue as Float)).toInt(), titleFill.height)
            }
            start()
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
        val engine = container.torrents
        lifecycleScope.launch {
            // At most one question to the engine at a time, and the screen never waits long for it.
            var pending: Deferred<Triple<TorrentStats?, Long?, String?>>? = null
            var lastStats: TorrentStats? = null
            while (isActive && player === exo) {
                val waiting = exo.playbackState != Player.STATE_READY
                val line: CharSequence = if (torrent != null) {
                    // Tells the engine where playback is (it puts that part first) and gets back how
                    // much video from there is downloaded without gaps.
                    val position = exo.currentPosition
                    val duration = exo.duration.takeIf { it > 0 } ?: -1L
                    val ask = pending?.takeIf { !it.isCompleted } ?: lifecycleScope.async(Dispatchers.IO) {
                        val ready = runCatching { engine.reportPlayback(torrent.first, torrent.second, position, duration) }.getOrNull()
                        val stats = runCatching { engine.stats(torrent.first, torrent.second) }.getOrNull()
                        Triple(stats, ready, if (stats == null) runCatching { engine.problemFor(torrent.first) }.getOrNull() else null)
                    }
                    pending = ask
                    val answer = withTimeoutOrNull(ENGINE_WAIT_MS) { ask.await() }
                    val stats = answer?.first
                    val readyMs = answer?.second
                    lastStats = stats
                    if (answer != null) {
                        latestStats = stats
                        latestReadyMs = readyMs
                    }
                    when {
                        answer == null -> "Waiting for the torrent engine…"
                        stats == null -> answer.third ?: "Starting torrent…"
                        !stats.hasMetadata -> statsLine("Peers" to "${stats.peers}", "Status" to "Fetching torrent info…")
                        else -> statsLine(
                            "Peers" to "${stats.peers}",
                            "Speed" to Formatter.formatShortFileSize(this@PlayerActivity, stats.downloadRate.toLong()) + "/s",
                            "Ready" to (readyMs?.let { formatTime(it) } ?: "…"),
                            "Downloaded" to Formatter.formatShortFileSize(this@PlayerActivity, stats.fileDownloadedBytes),
                            "Completed" to "%.2f%%".format(stats.fileProgress * 100),
                        )
                    }
                } else {
                    statsLine("Buffered" to "${exo.bufferedPercentage}%")
                }
                if (!firstFrameShown) {
                    loadingStats.text = line
                    loadingFraction(exo, torrent, lastStats)?.let(::showLoadingProgress) ?: showConnecting()
                } else if (hud != null) {
                    lastStatusLine = line.toString()
                } else {
                    statusView.text = line
                    statusView.visibility = if (waiting) View.VISIBLE else View.GONE
                }
                delay(1_000)
            }
        }
    }

    /**
     * How far loading has got (0..1), or null while still connecting (no torrent peers or data yet,
     * or no answer from the server). Torrents count their first MB, then the player's buffer.
     */
    private fun loadingFraction(exo: ExoPlayer, torrent: Pair<String, Int>?, stats: TorrentStats?): Float? {
        val buffered = exo.bufferedPosition - exo.currentPosition
        val bufferFraction = if (buffered > 0) buffered.toFloat() / container.settings.startBufferMs.value else 0f
        if (torrent != null) {
            if (stats == null || !stats.hasMetadata || (stats.peers == 0 && stats.fileDownloadedBytes == 0L)) return null
            val torrentFraction = stats.fileDownloadedBytes.toFloat() / TORRENT_START_BYTES
            return maxOf(torrentFraction * 0.7f, bufferFraction).coerceAtMost(1f)
        }
        return if (buffered > 0) bufferFraction.coerceAtMost(1f) else null
    }

    private fun buildMediaItem(url: String, subtitles: List<Subtitle>): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setSubtitleConfigurations(subtitles.map { sub ->
                // Re-timed copies are local files; the id stays the original URL.
                val uri = shiftedSubtitles[sub.url]?.let { Uri.fromFile(it) } ?: Uri.parse(sub.url)
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setId(sub.url)
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
        val up = event.action == KeyEvent.ACTION_UP
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val back = event.keyCode == KeyEvent.KEYCODE_BACK
        val h = hud
        val controlsShown = h?.let { it.controlsVisible || it.panel != null || it.problem != null } ?: playerView.isControllerFullyVisible
        // While the controls are hidden, OK answers the up-next / skip-intro label and Back dismisses it.
        if (!controlsShown) {
            if (upNextView.visibility == View.VISIBLE && (ok || back)) {
                if (up) if (ok) playNext() else cancelNext()
                return true
            }
            if (skipIntroView.visibility == View.VISIBLE && (ok || back)) {
                if (up) if (ok) skipIntro() else dismissSkipIntro()
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (up) {
                if (h != null) {
                    h.problem = null
                    h.panel = HudPanel.Languages
                } else {
                    showSubtitleOptions()
                }
            }
            return true
        }
        // Rewind / forward keys, and Left / Right while the controls are hidden: jumps that add up.
        val mediaSeek = event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        val dpadSeek = (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            !controlsShown && firstFrameShown && container.settings.dpadSeeks.value
        if (mediaSeek || dpadSeek) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val forward = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                onSeekKey(forward, repeat = event.repeatCount > 0)
            }
            return true
        }
        if (h != null) return dispatchHudKey(event, h)
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
        if (!isChangingConfigurations) deleteIfWatched()
        player?.release()
        player = null
    }

    // ---- Cinema controls (PlayerHud) ---------------------------------------------------------------

    private fun setUpHud(root: FrameLayout) {
        val settings = container.settings
        val state = PlayerHudState(hudActions).apply {
            title = request.title
            subtitleLine = listOfNotNull(request.subtitle, request.quality).joinToString(" · ")
            subtitleScale = settings.subtitleScale.value
            subtitleBox = settings.subtitleBox.value
            startBufferMs = settings.startBufferMs.value
            seekBackSeconds = settings.seekBackSeconds.value
            seekForwardSeconds = settings.seekForwardSeconds.value
            resizeMode = playerView.resizeMode
            cacheText = cacheLabel()
        }
        hud = state
        playerView.useController = false
        playerView.isFocusable = false
        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Tapping the picture (tablets) shows or hides the controls.
        playerView.setOnClickListener { if (state.controlsVisible) hideHud() else showHud() }
        root.addView(
            ComposeView(this).apply {
                setContent { StreamHubTheme(Palettes.Midnight, cinema = true) { PlayerHud(state) } }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun showHud() {
        val h = hud ?: return
        h.controlsVisible = true
        h.focusPlay++
        scheduleHide()
    }

    private fun hideHud() {
        hudHide?.cancel()
        hud?.controlsVisible = false
    }

    /** The controls hide a few seconds after the last key press while the video plays. */
    private fun scheduleHide() {
        val h = hud ?: return
        hudHide?.cancel()
        hudHide = lifecycleScope.launch {
            delay(HUD_HIDE_MS)
            if (h.panel == null && h.problem == null && player?.isPlaying == true) h.controlsVisible = false
        }
    }

    /**
     * Remote keys with the Cinema controls: while they are hidden, ← / → seek and any other arrow
     * or OK shows them; Back closes a panel, then hides the controls, then leaves the player.
     */
    private fun dispatchHudKey(event: KeyEvent, h: PlayerHudState): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        if (h.controlsVisible) scheduleHide()
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (down && event.repeatCount == 0) hudActions.togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (down) player?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) {
                    player?.pause()
                    showHud()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (h.problem == null && h.panel == null && !h.controlsVisible) return super.dispatchKeyEvent(event)
                if (up) {
                    when {
                        h.problem != null -> hudActions.dismissProblem()
                        h.panel != null -> h.panel = null
                        else -> hideHud()
                    }
                }
                return true
            }
        }
        if (!h.controlsVisible && h.panel == null && h.problem == null && event.keyCode in SHOW_KEYS) {
            if (down) showHud()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateHud(exo: ExoPlayer, h: PlayerHudState) {
        h.positionMs = exo.currentPosition
        h.durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        h.bufferedMs = exo.bufferedPosition
        h.playing = exo.playWhenReady
        h.speed = exo.playbackParameters.speed
        h.subtitleOffsetMs = subtitleOffsetMs
        h.qualityLine = listOfNotNull(
            exo.videoFormat?.let { videoLabel(it) },
            audioInfo?.lines()?.firstOrNull()?.removePrefix("Audio")?.trim()?.ifEmpty { null },
        ).joinToString(" · ")
        val ahead = maxOf(exo.bufferedPosition - exo.currentPosition, latestReadyMs ?: 0L)
        h.aheadText = if (firstFrameShown && ahead >= 1_000) "Buffered ahead " + formatAhead(ahead) else null
        val buffering = firstFrameShown && exo.playbackState == Player.STATE_BUFFERING
        val failed = h.problem?.buffering == false
        h.statusOk = !buffering && !failed
        h.statusText = when {
            failed -> "Playback problem"
            !firstFrameShown -> "Starting…"
            buffering -> "Waiting for data"
            !exo.playWhenReady -> "Paused"
            else -> "Playing smoothly"
        }
        // Waiting a long time for data: say so, with the torrent numbers and a way out.
        if (buffering) {
            val now = SystemClock.elapsedRealtime()
            if (bufferingSince == 0L) bufferingSince = now
            val message = "Playback paused because the next part of the video isn't here yet." +
                lastStatusLine.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
            val current = h.problem
            if (current?.buffering == true) {
                h.problem = current.copy(message = message)
            } else if (current == null && h.panel == null && !bufferingDismissed && now - bufferingSince > BUFFERING_PROBLEM_MS) {
                h.problem = HudProblem("Waiting for stream data", message, buffering = true)
            }
        } else {
            bufferingSince = 0L
            bufferingDismissed = false
            if (h.problem?.buffering == true) h.problem = null
        }
        when (h.panel) {
            HudPanel.Languages -> {
                h.audioTracks = hudTracks(exo, C.TRACK_TYPE_AUDIO)
                h.textTracks = hudTracks(exo, C.TRACK_TYPE_TEXT)
            }
            HudPanel.Info -> h.info = statisticsLines() + listOf(
                "Position" to formatTime(exo.currentPosition) + (h.durationMs.takeIf { it > 0 }?.let { " of " + formatTime(it) } ?: ""),
                "Playback speed" to "${exo.playbackParameters.speed}×",
            )
            else -> {}
        }
    }

    private val hudActions = object : PlayerHudActions {
        override fun togglePlay() {
            val exo = player ?: return
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.playWhenReady = !exo.playWhenReady
            hud?.playing = exo.playWhenReady
            showHud()
        }

        override fun seekStep(forward: Boolean, repeat: Boolean) = onSeekKey(forward, repeat)

        override fun exit() = finish()

        override fun selectAudio(id: String) = selectTrack(id)

        override fun selectSubtitle(id: String?) {
            val exo = player ?: return
            if (id == null) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } else {
                selectTrack(id)
            }
        }

        override fun shiftSubtitles(deltaMs: Long?) = retimeSubtitles(deltaMs?.let { subtitleOffsetMs + it } ?: 0L)

        override fun setSubtitleScale(scale: Float) {
            container.settings.subtitleScale.set(scale)
            hud?.subtitleScale = scale
            applySubtitleStyle()
        }

        override fun setSubtitleBox(box: Boolean) {
            container.settings.subtitleBox.set(box)
            hud?.subtitleBox = box
            applySubtitleStyle()
        }

        override fun loadSources() = loadHudSources()

        override fun switchSource(index: Int) = switchToSource(index)

        override fun setResizeMode(mode: Int) {
            playerView.resizeMode = mode
            hud?.resizeMode = mode
        }

        override fun setSpeed(speed: Float) {
            player?.setPlaybackSpeed(speed)
            hud?.speed = speed
        }

        override fun setStartBuffer(ms: Int) {
            container.settings.startBufferMs.set(ms)
            hud?.startBufferMs = ms
            hud?.message = "Applies the next time a video starts"
        }

        override fun copyReport() {
            val text = "StreamHub playback report\n" + request.title + (request.subtitle?.let { " · $it" } ?: "") + "\n" +
                (request.quality?.let { "Source: $it\n" } ?: "") +
                statisticsLines().joinToString("\n") { (label, value) -> "$label: $value" }
            runCatching {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("StreamHub playback report", text))
            }
            hud?.message = "Playback report copied"
        }

        override fun restart() {
            player?.let {
                it.seekTo(0)
                it.play()
            }
            hud?.panel = null
            hud?.message = "Started from the beginning"
        }

        override fun download() = downloadCurrent()

        override fun setSleep(minutes: Int) = setSleepTimer(minutes)

        override fun retry() {
            hud?.problem = null
            player?.let {
                it.prepare()
                it.play()
            }
        }

        override fun dismissProblem() {
            if (hud?.problem?.buffering == true) bufferingDismissed = true
            hud?.problem = null
            showHud()
        }
    }

    private fun selectTrack(id: String) {
        val exo = player ?: return
        val (g, i) = id.split(':').map { it.toIntOrNull() ?: return }
        val group = exo.currentTracks.groups.getOrNull(g) ?: return
        if (i >= group.length) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
            .build()
    }

    private fun hudTracks(exo: ExoPlayer, type: Int): List<HudTrack> {
        val out = mutableListOf<HudTrack>()
        exo.currentTracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val language = f.language?.takeIf { it != "und" }?.let { code ->
                    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
                }
                val label = language ?: f.label ?: "Track ${out.size + 1}"
                val extra = f.label?.takeIf { !it.equals(label, ignoreCase = true) && !it.equals(f.language, ignoreCase = true) }
                val detail = if (type == C.TRACK_TYPE_AUDIO) {
                    listOfNotNull(extra, audioCodecName(f), channelLabel(f.channelCount)).joinToString(" · ")
                } else {
                    val fromAddon = subtitleSources.any { sub -> f.id?.endsWith(sub.url) == true }
                    listOfNotNull(extra, if (fromAddon) "From an addon" else "In the video").joinToString(" · ")
                }
                out += HudTrack("$g:$i", label, detail, group.isTrackSelected(i))
            }
        }
        return out
    }

    private fun audioCodecName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+)"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        null -> null
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    private fun channelLabel(count: Int): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count channels"
    }

    private fun videoLabel(f: Format): String? {
        val height = f.height.takeIf { it > 0 } ?: return null
        return when {
            height >= 2000 -> "4K"
            height >= 1400 -> "1440p"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            else -> "${height}p"
        }
    }

    /** "2m 10s" */
    private fun formatAhead(ms: Long): String {
        val total = ms / 1000
        return if (total >= 60) "${total / 60}m ${"%02d".format(total % 60)}s" else "${total}s"
    }

    /** Where torrent data goes while streaming, for the info line. */
    private fun cacheLabel(): String? {
        if (TorrentLinks.parseLogicalUrl(request.url) == null) return null
        val settings = container.settings
        return "Cache: " + when (settings.torrentCacheMode.value) {
            CachePolicy.INTERNAL -> "TV storage"
            CachePolicy.USB -> settings.torrentCacheVolumeLabel.value.ifEmpty { "USB drive" }
            else -> "automatic"
        }
    }

    /** The URL the player uses for [stream] (logical torrent URL or direct link), or null. */
    private fun playUrl(stream: Stream): Pair<String, Map<String, String>>? = when (val target = StreamResolver.resolve(stream)) {
        is PlaybackTarget.Direct -> target.url to target.headers
        is PlaybackTarget.Torrent -> TorrentLinks.logicalUrl(target.source, target.fileIdx) to emptyMap()
        else -> null
    }

    /** Asks the addons for this video's other sources, best first (like "Play best"). */
    private fun loadHudSources() {
        val h = hud ?: return
        if (h.sources != null || sourcesJob?.isActive == true) return
        val type = request.type
        val videoId = request.videoId
        if (type == null || videoId == null) {
            h.sourcesMessage = "Other sources aren't available for this video (it was opened from a file or a link)."
            h.sources = emptyList()
            return
        }
        h.sourcesMessage = "Asking your addons for sources…"
        sourcesJob = lifecycleScope.launch {
            val found = coroutineScope {
                container.addons.streamAddons(type, videoId).map { addon ->
                    async {
                        withTimeoutOrNull(NEXT_STREAMS_TIMEOUT_MS) { runCatching { container.addons.streams(addon, type, videoId) }.getOrNull() }
                    }
                }.awaitAll().filterNotNull()
            }
            val addonOf = HashMap<Stream, String>()
            found.forEach { result -> result.streams.forEach { addonOf.putIfAbsent(it, result.addon.manifest.name) } }
            val ranked = StreamRanking.rank(
                found.flatMap { it.streams },
                container.settings.maxResolution.value,
                container.settings.surroundPassthrough.value,
            ).filter { playUrl(it) != null }.distinctBy { playUrl(it)?.first }.take(MAX_SOURCES)
            hudStreams = ranked
            h.sourcesMessage = if (ranked.isEmpty()) "Your addons found no other sources for this video." else null
            h.sources = ranked.mapIndexed { i, stream ->
                val facts = StreamFacts.of(stream)
                HudSource(
                    badge = facts.quality ?: "—",
                    headline = facts.headline.ifEmpty { stream.name?.lineSequence()?.firstOrNull() ?: "Stream" },
                    detail = listOfNotNull(
                        facts.languages.joinToString(", ").ifEmpty { null },
                        facts.audio,
                        facts.codec,
                        facts.sizeBytes?.let { Formatter.formatShortFileSize(this@PlayerActivity, it) },
                        addonOf[stream],
                    ).joinToString(" · "),
                    note = when {
                        !StreamResolver.isTorrent(stream) -> "Direct link"
                        facts.seeders == null -> "Seeders not reported"
                        facts.seeders == 0 -> "No seeders reported: may not start"
                        else -> "${facts.seeders} seeders"
                    },
                    playing = playUrl(stream)?.first == request.url,
                    suggested = i == 0,
                )
            }
        }
    }

    /** Plays another source from where the current one is now. */
    private fun switchToSource(index: Int) {
        val h = hud ?: return
        val stream = hudStreams.getOrNull(index) ?: return
        val (url, headers) = playUrl(stream) ?: return
        if (url == request.url) {
            h.panel = null
            h.message = "This source is already playing"
            return
        }
        saveProgress()
        startActivity(
            request.copy(
                url = url,
                headers = headers,
                subtitles = stream.subtitles,
                bingeGroup = stream.behaviorHints.bingeGroup,
                quality = StreamFacts.of(stream).label,
            ).toIntent(this),
        )
        finish()
    }

    /** Downloads the source being played to the usual download place. */
    private fun downloadCurrent() {
        val h = hud ?: return
        if (request.url.startsWith("file:") || request.url.startsWith("content:")) {
            h.message = "This video is already a download"
            return
        }
        lifecycleScope.launch {
            val location = container.settings.downloadLocation.value
                ?: withContext(Dispatchers.IO) { container.storage.defaultLocation() }
            container.downloader.enqueue(
                title = request.title,
                subtitle = request.subtitle,
                poster = request.poster,
                metaId = request.metaId,
                type = request.type,
                videoId = request.videoId,
                url = request.url,
                headers = request.headers,
                suggestedFileName = null,
                location = location,
            )
            h.panel = null
            h.message = "Downloading to ${location.label}"
        }
    }

    private fun setSleepTimer(minutes: Int) {
        val h = hud ?: return
        sleepJob?.cancel()
        sleepAtEnd = minutes == PlayerHudState.SLEEP_END
        h.sleepMinutes = minutes
        if (minutes > 0) {
            sleepJob = lifecycleScope.launch {
                delay(minutes * 60_000L)
                player?.pause()
                saveProgress()
                h.sleepMinutes = 0
                showHud()
                h.message = "Sleep timer: paused"
            }
        }
        h.message = when {
            minutes == 0 -> "Sleep timer off"
            sleepAtEnd -> "Playback stops at the end of this video"
            else -> "Pauses in $minutes minutes"
        }
    }

    // ---- Subtitles -------------------------------------------------------------------------------

    private fun applySubtitleStyle() {
        playerView.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * container.settings.subtitleScale.value)
            setStyle(
                if (container.settings.subtitleBox.value) {
                    CaptionStyleCompat(
                        Color.WHITE, 0xBB000000.toInt(), Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, Typeface.DEFAULT_BOLD,
                    )
                } else {
                    CaptionStyleCompat(
                        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD,
                    )
                },
            )
        }
    }

    /** Adds a "subtitle size and timing" button next to the player's own subtitle/settings buttons. */
    private fun addSubtitleOptionsButton() {
        val barId = resources.getIdentifier("exo_basic_controls", "id", packageName)
        val bar = playerView.findViewById<ViewGroup>(barId) ?: return
        val background = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        val size = (48 * resources.displayMetrics.density).toInt()
        bar.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_subtitle_options)
                setBackgroundResource(background.resourceId)
                contentDescription = "Subtitle size and timing"
                setOnClickListener { showSubtitleOptions() }
            },
            0,
            LinearLayout.LayoutParams(size, size),
        )
    }

    /** Adds a "Statistics" button (like Stremio's) to the player controls. */
    private fun addStatisticsButton() {
        val barId = resources.getIdentifier("exo_basic_controls", "id", packageName)
        val bar = playerView.findViewById<ViewGroup>(barId) ?: return
        val background = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        val size = (48 * resources.displayMetrics.density).toInt()
        bar.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_statistics)
                setBackgroundResource(background.resourceId)
                contentDescription = "Statistics"
                setOnClickListener { showStatistics() }
            },
            0,
            LinearLayout.LayoutParams(size, size),
        )
    }

    /** Live numbers about what is playing (torrent, buffer, video, audio), refreshed every second. */
    private fun showStatistics() {
        val text = TextView(this).apply {
            textSize = 17f
            setLineSpacing(0f, 1.25f)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Statistics")
            .setView(text)
            .setPositiveButton("Close", null)
            .create()
        val updates = lifecycleScope.launch {
            while (isActive) {
                text.text = statisticsText()
                delay(1_000)
            }
        }
        dialog.setOnDismissListener { updates.cancel() }
        dialog.show()
    }

    private fun statisticsText(): CharSequence {
        val lines = statisticsLines()
        return android.text.SpannableStringBuilder().apply {
            lines.forEachIndexed { i, (label, value) ->
                if (i > 0) append('\n')
                val start = length
                append(label)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, 0)
                append("   ").append(value)
            }
        }
    }

    private fun statisticsLines(): List<Pair<String, String>> {
        val exo = player
        val lines = mutableListOf<Pair<String, String>>()
        val torrent = TorrentLinks.parseLogicalUrl(request.url)
        if (torrent != null) {
            val stats = latestStats
            stats?.infoHash?.let { lines += "Info hash" to it }
            if (stats == null) {
                lines += "Torrent" to "starting…"
            } else {
                lines += "Peers" to "${stats.peers} (${stats.seeds} seeds)"
                lines += "Speed" to Formatter.formatShortFileSize(this, stats.downloadRate.toLong()) + "/s"
                lines += "Uploading" to Formatter.formatShortFileSize(this, stats.uploadRate.toLong()) + "/s"
                lines += "Downloaded" to Formatter.formatShortFileSize(this, stats.fileDownloadedBytes)
                lines += "Completed" to "%.2f%%".format(stats.fileProgress * 100)
                latestReadyMs?.let { lines += "Ready ahead" to formatTime(it) }
            }
        } else {
            mediaUrl?.let { url -> Uri.parse(url).host?.let { lines += "Source" to it } }
        }
        if (exo != null) {
            lines += "Buffered" to formatTime((exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0))
            exo.videoFormat?.let { f ->
                val codec = f.sampleMimeType?.substringAfter('/')?.uppercase() ?: f.codecs ?: "?"
                val bitrate = f.bitrate.takeIf { it > 0 }?.let { " · %.1f Mbit/s".format(it / 1_000_000.0) } ?: ""
                lines += "Video" to "${f.width}×${f.height} $codec$bitrate"
            }
        }
        audioInfo?.let { lines += "Audio" to it.removePrefix("Audio").trim().lines().first() }
        return lines
    }

    private fun showSubtitleOptions() {
        val settings = container.settings
        val sizes = listOf("Small" to 0.8f, "Normal" to 1f, "Large" to 1.3f, "Extra large" to 1.6f)
        val labels = sizes.map { (name, scale) -> (if (scale == settings.subtitleScale.value) "✓  " else "     ") + "Size: $name" } +
            listOf("Timing: show 0.5 s earlier", "Timing: show 0.5 s later", "Timing: reset")
        AlertDialog.Builder(this)
            .setTitle("Subtitles  (timing ${formatOffset(subtitleOffsetMs)})")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < sizes.size -> {
                        settings.subtitleScale.set(sizes[which].second)
                        applySubtitleStyle()
                    }
                    which == sizes.size -> retimeSubtitles(subtitleOffsetMs - SUBTITLE_STEP_MS)
                    which == sizes.size + 1 -> retimeSubtitles(subtitleOffsetMs + SUBTITLE_STEP_MS)
                    else -> retimeSubtitles(0)
                }
            }
            .show()
    }

    private fun formatOffset(ms: Long) = if (ms == 0L) "normal" else "%+.1f s".format(ms / 1000.0)

    private fun selectedTextFormatId(tracks: Tracks): String? =
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).id }
        }

    /** Shifts the selected addon subtitle by [offsetMs] (re-timed copy, reloaded at the same position). */
    private fun retimeSubtitles(offsetMs: Long) {
        val exo = player ?: return
        val url = mediaUrl ?: return
        val selectedId = selectedTextFormatId(exo.currentTracks)
        val source = subtitleSources.firstOrNull { sub -> selectedId != null && selectedId.endsWith(sub.url) }
        if (source == null) {
            toast("Pick a subtitle from an addon first (subtitles button); timing can't be changed for subtitles inside the video")
            return
        }
        if (!SubtitleShift.supports(source.url)) {
            toast("This subtitle format can't be re-timed")
            return
        }
        lifecycleScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    val text = subtitleTexts[source.url] ?: container.mediaHttp
                        .newCall(Request.Builder().url(source.url).build()).execute()
                        .use { it.body!!.string() }
                        .also { subtitleTexts[source.url] = it }
                    val dir = File(cacheDir, "subtitles").apply { mkdirs() }
                    val ext = if (subtitleMime(source.url) == MimeTypes.TEXT_VTT) "vtt" else "srt"
                    File(dir, "${source.url.hashCode()}_$offsetMs.$ext").apply { writeText(SubtitleShift.shift(text, offsetMs)) }
                }
            }.getOrElse {
                toast("Couldn't load the subtitle: ${it.message}")
                return@launch
            }
            if (player !== exo) return@launch
            subtitleOffsetMs = offsetMs
            if (offsetMs == 0L) shiftedSubtitles.remove(source.url) else shiftedSubtitles[source.url] = file
            pendingSubtitleId = source.url
            exo.setMediaItem(buildMediaItem(url, subtitleSources), exo.currentPosition)
            exo.prepare()
            toast("Subtitle timing: ${formatOffset(offsetMs)}")
        }
    }

    /** Selects the subtitle track that came from [id] once it is loaded; false if not yet there. */
    private fun selectSubtitle(exo: ExoPlayer, tracks: Tracks, id: String): Boolean {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            val index = (0 until group.length).firstOrNull { group.getTrackFormat(it).id?.endsWith(id) == true } ?: continue
            applyingOwnTrackSelection = true
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
            applyingOwnTrackSelection = false
            return true
        }
        return false
    }

    private fun rememberLanguages(tracks: Tracks) {
        val settings = container.settings
        fun selectedLanguage(type: Int): Pair<Boolean, String?> {
            val group = tracks.groups.firstOrNull { it.type == type && it.isSelected } ?: return false to null
            val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return false to null
            return true to group.getTrackFormat(index).language?.takeIf { it != "und" }
        }
        val (hasText, textLanguage) = selectedLanguage(C.TRACK_TYPE_TEXT)
        if (!hasText) settings.subtitleLanguage.set("") else textLanguage?.let { settings.subtitleLanguage.set(it) }
        selectedLanguage(C.TRACK_TYPE_AUDIO).second?.let { settings.audioLanguage.set(it) }
    }

    // ---- Up next / skip intro -----------------------------------------------------------------

    private fun findNextEpisode() {
        val type = request.type ?: return
        val metaId = request.metaId ?: return
        val videoId = request.videoId ?: return
        if (type != "series") return
        lifecycleScope.launch {
            val meta = runCatching { container.addons.meta(type, metaId) }.getOrNull() ?: return@launch
            nextEpisode = Episodes.after(meta.videos, videoId)
        }
    }

    private fun updateUpNextAndSkipIntro(exo: ExoPlayer) {
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exo.currentPosition
        if (!markedWatched && position >= duration * WATCHED_FRACTION) {
            markedWatched = true
            request.type?.let { type -> (request.videoId ?: request.metaId)?.let { container.trakt.markWatched(type, it) } }
        }
        if (!upNextShown && !upNextCancelled && nextEpisode != null && container.settings.autoplayNext.value &&
            duration > MIN_EPISODE_MS && duration - position <= UP_NEXT_BEFORE_END_MS
        ) {
            showUpNext()
        }
        val metaId = request.metaId
        // "Off" in Settings (0 s) never offers the button.
        val showSkip = request.type == "series" && metaId != null && !skipIntroDismissed && !upNextShown &&
            container.settings.introSkipSeconds.value > 0 &&
            duration > MIN_EPISODE_MS && inIntroWindow(metaId, position)
        skipIntroView.visibility = if (showSkip) View.VISIBLE else View.GONE
    }

    /** Around where this show's intro started last time, else during the first minutes. */
    private fun inIntroWindow(metaId: String, position: Long): Boolean {
        val learned = container.introMemory.startFor(metaId)
        return if (learned != null) position in (learned - 5_000)..(learned + 30_000) else position in 15_000L..180_000L
    }

    private fun skipIntro() {
        val exo = player ?: return
        val metaId = request.metaId ?: return
        container.introMemory.remember(metaId, exo.currentPosition)
        exo.seekTo(exo.currentPosition + container.settings.introSkipSeconds.value * 1_000L)
        dismissSkipIntro()
    }

    private fun dismissSkipIntro() {
        skipIntroDismissed = true
        skipIntroView.visibility = View.GONE
    }

    private fun showUpNext() {
        val next = nextEpisode ?: return
        upNextShown = true
        upNextView.visibility = View.VISIBLE
        val label = Episodes.label(next)
        nextResolve = lifecycleScope.launch {
            nextRequest = runCatching { resolveNext(next) }.getOrNull()
            if (nextRequest == null) {
                nextCountdown?.cancel()
                upNextView.text = "No stream found for $label"
                delay(5_000)
                upNextCancelled = true
                upNextView.visibility = View.GONE
            }
        }
        nextCountdown = lifecycleScope.launch {
            for (seconds in UP_NEXT_COUNTDOWN_S downTo 1) {
                upNextView.text = "Up next: $label\nPlaying in $seconds s   ·   OK: play now   ·   Back: cancel"
                delay(1_000)
            }
            playNext()
        }
    }

    private fun cancelNext() {
        upNextCancelled = true
        nextCountdown?.cancel()
        nextResolve?.cancel()
        upNextView.visibility = View.GONE
    }

    private fun playNext() {
        if (startingNext) return
        startingNext = true
        nextCountdown?.cancel()
        lifecycleScope.launch {
            if (nextRequest == null) {
                upNextView.text = "Finding a stream for the next episode…"
                nextResolve?.join()
            }
            val next = nextRequest
            if (next == null) {
                startingNext = false
                if (player?.playbackState == Player.STATE_ENDED) finish()
                return@launch
            }
            saveProgress()
            startActivity(next.toIntent(this@PlayerActivity))
            finish()
        }
    }

    /** Streams for [next] from every addon (the slow ones are skipped), picked like "Play best". */
    private suspend fun resolveNext(next: Video): PlayRequest? {
        val type = request.type ?: return null
        val streams = coroutineScope {
            container.addons.streamAddons(type, next.id).map { addon ->
                async { withTimeoutOrNull(NEXT_STREAMS_TIMEOUT_MS) { container.addons.streams(addon, type, next.id).streams }.orEmpty() }
            }.awaitAll().flatten()
        }
        val stream = StreamRanking.next(
            streams,
            request.bingeGroup,
            container.settings.maxResolution.value,
            container.settings.surroundPassthrough.value,
        ) ?: return null
        val (url, headers) = when (val target = StreamResolver.resolve(stream)) {
            is PlaybackTarget.Direct -> target.url to target.headers
            is PlaybackTarget.Torrent -> TorrentLinks.logicalUrl(target.source, target.fileIdx) to emptyMap()
            else -> return null
        }
        return request.copy(
            url = url,
            headers = headers,
            subtitle = Episodes.label(next),
            subtitles = stream.subtitles,
            videoId = next.id,
            bingeGroup = stream.behaviorHints.bingeGroup,
            quality = StreamFacts.of(stream).label,
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** "Delete downloads after watching": removes the downloaded file once it has been watched. */
    private fun deleteIfWatched() {
        if (deletedAfterWatching || !container.settings.deleteAfterWatching.value) return
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (exo.playbackState != Player.STATE_ENDED && exo.currentPosition < duration * WATCHED_FRACTION) return
        val item = container.downloads.items.value.firstOrNull {
            it.fileUri == request.url && it.status == DownloadItem.Status.COMPLETED
        } ?: return
        deletedAfterWatching = true
        container.downloader.remove(item.id, deleteFile = true)
        toast("Deleted the download of ${item.title} after watching")
    }

    // ---- Rewind / forward ------------------------------------------------------------------------

    private fun onSeekKey(forward: Boolean, repeat: Boolean) {
        // Holding the key: one jump every SEEK_REPEAT_MS, not one per key repeat.
        val now = SystemClock.elapsedRealtime()
        if (repeat && now - lastSeekRepeatAt < SEEK_REPEAT_MS) return
        lastSeekRepeatAt = now
        val settings = container.settings
        val base = (if (forward) settings.seekForwardSeconds.value else settings.seekBackSeconds.value) * 1_000L
        seekBy(seekSteps.next(forward, base, settings.seekAcceleration.value))
    }

    /** Moves the pending seek target; the player seeks once the presses stop. */
    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val from = seekTarget ?: exo.currentPosition.also { seekFrom = it }
        var target = (from + deltaMs).coerceAtLeast(0)
        if (duration != null) target = target.coerceAtMost((duration - 1_000).coerceAtLeast(0))
        seekTarget = target
        hud?.seekTargetMs = target
        seekView.text = (if (target >= seekFrom) "⏩  " else "⏪  ") + SeekSteps.describe(target - seekFrom) +
            "      " + formatTime(target) + (duration?.let { " / " + formatTime(it) } ?: "")
        // With the Cinema controls showing, their timeline shows where the jump goes.
        seekView.visibility = if (hud?.controlsVisible == true) View.GONE else View.VISIBLE
        seekCommit?.cancel()
        seekCommit = lifecycleScope.launch {
            delay(SEEK_COMMIT_MS)
            seekTarget = null
            hud?.seekTargetMs = null
            if (player === exo) exo.seekTo(target)
            delay(SEEK_LABEL_MS)
            seekView.visibility = View.GONE
        }
    }

    /** Shows [info] (or the last one) in the top corner; a new audio format is shown for a few seconds. */
    private fun showAudioInfo(info: String?) {
        if (info != null) audioInfo = info
        if (hud != null) return
        val text = audioInfo ?: return
        audioInfoView.text = text
        audioInfoView.visibility = View.VISIBLE
        hideAudioInfo?.cancel()
        hideAudioInfo = null
        if (info != null) {
            hideAudioInfo = lifecycleScope.launch {
                delay(AUDIO_INFO_MS)
                hideAudioInfo = null
                if (!playerView.isControllerFullyVisible) audioInfoView.visibility = View.GONE
            }
        }
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
        container.history.record(
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
        private const val SUBTITLE_WAIT_MS = 1_000L
        private const val ENGINE_WAIT_MS = 1_500L
        private const val LATE_SUBTITLES_MAX_MS = 180_000L
        private const val MIN_REBUFFER_MS = 2_000
        private const val AUDIO_INFO_MS = 8_000L
        private const val PULSE_MS = 900L
        private const val FILL_STEP_MS = 950L
        private const val TORRENT_START_BYTES = 8f * 1024 * 1024
        private val FILL_COLOR = 0xFFC9B8FF.toInt()
        private const val SEEK_COMMIT_MS = 700L
        private const val SEEK_LABEL_MS = 1_200L
        private const val SEEK_REPEAT_MS = 250L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val RESUME_MIN_MS = 30_000L
        private const val SUBTITLE_STEP_MS = 500L
        private const val UP_NEXT_BEFORE_END_MS = 40_000L
        private const val UP_NEXT_COUNTDOWN_S = 10
        private const val MIN_EPISODE_MS = 5 * 60_000L
        private const val NEXT_STREAMS_TIMEOUT_MS = 15_000L
        private const val WATCHED_FRACTION = 0.9
        private const val HUD_HIDE_MS = 6_000L
        private const val BUFFERING_PROBLEM_MS = 8_000L
        private const val MAX_SOURCES = 30
        private val SHOW_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

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
    /** Release group of the stream (addons' behaviorHints.bingeGroup), to keep it for the next episode. */
    val bingeGroup: String? = null,
    /** What the source is, e.g. "1080p · WEB-DL · DD+ 5.1" (shown by the Cinema player). */
    val quality: String? = null,
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
        .putExtra("bingeGroup", bingeGroup)
        .putExtra("quality", quality)

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
                bingeGroup = intent.getStringExtra("bingeGroup"),
                quality = intent.getStringExtra("quality"),
            )
        }
    }
}
