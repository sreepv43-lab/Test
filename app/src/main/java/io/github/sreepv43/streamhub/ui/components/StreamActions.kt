package io.github.sreepv43.streamhub.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.sreepv43.streamhub.addon.PlaybackTarget
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.addon.Subtitle
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.player.PlayRequest
import io.github.sreepv43.streamhub.player.PlayerActivity
import io.github.sreepv43.streamhub.torrent.TorrentLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is being watched, used for resume points, history and download names. */
data class WatchContext(
    val metaId: String,
    val type: String,
    val videoId: String,
    val title: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
)

object StreamActions {
    private val scope: CoroutineScope = MainScope()

    fun play(context: Context, stream: Stream, watch: WatchContext) {
        val target = StreamResolver.resolve(stream) ?: return toast(context, "This stream type is not supported")
        playTarget(context, target, watch, stream.subtitles, stream.behaviorHints.bingeGroup)
    }

    /** Plays a direct URL or a torrent in the built-in player; other links open in their app. */
    fun playTarget(
        context: Context,
        target: PlaybackTarget,
        watch: WatchContext,
        subtitles: List<Subtitle> = emptyList(),
        bingeGroup: String? = null,
    ) {
        val (url, headers) = when (target) {
            is PlaybackTarget.Direct -> target.url to target.headers
            is PlaybackTarget.Torrent -> TorrentLinks.logicalUrl(target.source, target.fileIdx) to emptyMap()
            is PlaybackTarget.External -> return openUrl(context, target.url)
        }
        PlayerActivity.start(
            context,
            PlayRequest(
                url = url,
                title = watch.title,
                subtitle = watch.episodeTitle,
                headers = headers,
                subtitles = subtitles,
                metaId = watch.metaId,
                type = watch.type,
                videoId = watch.videoId,
                poster = watch.poster,
                background = watch.background,
                logo = watch.logo,
                bingeGroup = bingeGroup,
            ),
        )
    }

    fun playFile(context: Context, uri: String, watch: WatchContext) {
        playTarget(context, PlaybackTarget.Direct(uri, emptyMap()), watch)
    }

    /** Hands the stream to another installed player such as VLC or MX Player. */
    fun openInExternalPlayer(context: Context, stream: Stream, title: String) {
        val target = StreamResolver.resolve(stream) ?: return toast(context, "This stream type is not supported")
        openTargetExternally(context, target, title)
    }

    fun openTargetExternally(context: Context, target: PlaybackTarget, title: String) {
        when (target) {
            is PlaybackTarget.Direct -> openInPlayerApp(context, target.url, title)
            // External players read torrents through the built-in engine's local HTTP server.
            is PlaybackTarget.Torrent -> scope.launch {
                val url = withContext(Dispatchers.IO) {
                    context.container.torrentServer.urlFor(target.source, target.fileIdx)
                }
                openInPlayerApp(context, url, title)
            }
            is PlaybackTarget.External -> openUrl(context, target.url)
        }
    }

    fun download(context: Context, stream: Stream, watch: WatchContext, location: DownloadLocation): Boolean {
        val target = StreamResolver.resolve(stream) ?: run {
            toast(context, "This stream type is not supported")
            return false
        }
        return downloadTarget(context, target, watch, location, stream.behaviorHints.filename)
    }

    fun downloadTarget(
        context: Context,
        target: PlaybackTarget,
        watch: WatchContext,
        location: DownloadLocation,
        suggestedFileName: String? = null,
    ): Boolean {
        val (url, headers) = when (target) {
            is PlaybackTarget.Direct -> target.url to target.headers
            is PlaybackTarget.Torrent -> TorrentLinks.logicalUrl(target.source, target.fileIdx) to emptyMap()
            is PlaybackTarget.External -> {
                toast(context, "Links to other apps or websites can't be downloaded")
                return false
            }
        }
        context.container.downloader.enqueue(
            title = watch.title,
            subtitle = watch.episodeTitle,
            poster = watch.poster,
            metaId = watch.metaId,
            type = watch.type,
            videoId = watch.videoId,
            url = url,
            headers = headers,
            suggestedFileName = suggestedFileName,
            location = location,
        )
        toast(context, "Downloading to ${location.label}")
        return true
    }

    fun openUrl(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openInPlayerApp(context: Context, url: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, "Play with"))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(context, "No installed app can open this")
        }
    }

    fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
