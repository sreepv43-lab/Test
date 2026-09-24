package io.github.sreepv43.streamhub.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.sreepv43.streamhub.addon.PlaybackTarget
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.player.PlayRequest
import io.github.sreepv43.streamhub.player.PlayerActivity

/** What is being watched, used for resume points, history and download names. */
data class WatchContext(
    val metaId: String,
    val type: String,
    val videoId: String,
    val title: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
)

object StreamActions {

    fun target(context: Context, stream: Stream): PlaybackTarget? =
        StreamResolver.resolve(stream, context.container.settings.streamingServerUrl.value)

    fun play(context: Context, stream: Stream, watch: WatchContext) {
        when (val target = target(context, stream)) {
            is PlaybackTarget.Direct -> PlayerActivity.start(
                context,
                PlayRequest(
                    url = target.url,
                    title = watch.title,
                    subtitle = watch.episodeTitle,
                    headers = target.headers,
                    subtitles = stream.subtitles,
                    metaId = watch.metaId,
                    type = watch.type,
                    videoId = watch.videoId,
                    poster = watch.poster,
                ),
            )
            is PlaybackTarget.External -> openUrl(context, target.url)
            null -> toast(context, "This stream type is not supported")
        }
    }

    fun playFile(context: Context, uri: String, watch: WatchContext) {
        PlayerActivity.start(
            context,
            PlayRequest(
                url = uri,
                title = watch.title,
                subtitle = watch.episodeTitle,
                metaId = watch.metaId,
                type = watch.type,
                videoId = watch.videoId,
                poster = watch.poster,
            ),
        )
    }

    /** Hands the stream to another installed player such as VLC or MX Player. */
    fun openInExternalPlayer(context: Context, stream: Stream, title: String) {
        when (val target = target(context, stream)) {
            is PlaybackTarget.Direct -> {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(target.url), "video/*")
                    .putExtra("title", title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                start(context, Intent.createChooser(intent, "Play with"))
            }
            is PlaybackTarget.External -> openUrl(context, target.url)
            null -> toast(context, "This stream type is not supported")
        }
    }

    fun download(context: Context, stream: Stream, watch: WatchContext, location: DownloadLocation): Boolean {
        val target = target(context, stream) as? PlaybackTarget.Direct ?: run {
            toast(context, "Only direct or streaming-server streams can be downloaded")
            return false
        }
        context.container.downloader.enqueue(
            title = watch.title,
            subtitle = watch.episodeTitle,
            poster = watch.poster,
            metaId = watch.metaId,
            type = watch.type,
            videoId = watch.videoId,
            url = target.url,
            headers = target.headers,
            suggestedFileName = stream.behaviorHints.filename,
            location = location,
        )
        toast(context, "Downloading to ${location.label}")
        return true
    }

    fun openUrl(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
