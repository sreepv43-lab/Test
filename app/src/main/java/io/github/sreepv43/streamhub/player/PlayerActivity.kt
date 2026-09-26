package io.github.sreepv43.streamhub.player

import io.github.sreepv43.streamhub.download.DownloadItem
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
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
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
    private val container get() = (application as StreamHubApp).container

    // Subtitles: size/timing options, and remembering the languages chosen in the player.
    private var mediaUrl: String? = null
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
        upNextView = overlayLabel()
        skipIntroView = overlayLabel().apply { text = "Skip intro   ▸ OK" }
        val root = FrameLayout(this).apply {
            addView(playerView)
            val corner = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(64, 64, 64, 160) }
            addView(upNextView, corner)
            addView(skipIntroView, FrameLayout.LayoutParams(corner))
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
        addSubtitleOptionsButton()
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
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        val settings = container.settings
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
                    deleteIfWatched()
                    if (upNextShown && !upNextCancelled) playNext() else finish()
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
            mediaUrl = url
            subtitleSources = (request.subtitles + addonSubtitles).distinctBy { it.url }.take(MAX_SUBTITLES)
            exo.setMediaItem(buildMediaItem(url, subtitleSources), start)
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
        lifecycleScope.launch {
            while (isActive && player === exo) {
                updateUpNextAndSkipIntro(exo)
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
        val engine = container.torrents
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
        // While the controls are hidden, OK answers the up-next / skip-intro label and Back dismisses it.
        if (!playerView.isControllerFullyVisible) {
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
            if (up) showSubtitleOptions()
            return true
        }
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

    // ---- Subtitles -------------------------------------------------------------------------------

    private fun applySubtitleStyle() {
        playerView.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * container.settings.subtitleScale.value)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT_BOLD,
                ),
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
        val showSkip = request.type == "series" && metaId != null && !skipIntroDismissed && !upNextShown &&
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
        val stream = StreamRanking.next(streams, request.bingeGroup, container.settings.maxResolution.value) ?: return null
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
        private const val SUBTITLE_TIMEOUT_MS = 4_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val RESUME_MIN_MS = 30_000L
        private const val SUBTITLE_STEP_MS = 500L
        private const val UP_NEXT_BEFORE_END_MS = 40_000L
        private const val UP_NEXT_COUNTDOWN_S = 10
        private const val MIN_EPISODE_MS = 5 * 60_000L
        private const val NEXT_STREAMS_TIMEOUT_MS = 15_000L
        private const val WATCHED_FRACTION = 0.9

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
            )
        }
    }
}
