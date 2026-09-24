package io.github.sreepv43.streamhub.addon

import io.github.sreepv43.streamhub.torrent.TorrentLinks

/** What the app can do with a stream returned by an addon. */
sealed interface PlaybackTarget {
    /** A direct HTTP(S) URL the built-in player can open and the downloader can save. */
    data class Direct(val url: String, val headers: Map<String, String>) : PlaybackTarget

    /**
     * A file inside a torrent, handled by the built-in torrent engine. [source] is a magnet link or
     * a .torrent URL; [fileIdx] -1 means "the main video file".
     */
    data class Torrent(val source: String, val fileIdx: Int) : PlaybackTarget

    /** Something that must be handed to another app (YouTube, a website). */
    data class External(val url: String) : PlaybackTarget
}

object StreamResolver {
    fun resolve(stream: Stream): PlaybackTarget? {
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        stream.url?.takeIf { it.isNotBlank() }?.let {
            return if (TorrentLinks.isTorrentSource(it)) PlaybackTarget.Torrent(it, stream.fileIdx ?: -1)
            else PlaybackTarget.Direct(it, headers)
        }
        stream.infoHash?.takeIf { it.isNotBlank() }?.let { hash ->
            return PlaybackTarget.Torrent(magnetLink(stream, hash.lowercase()), stream.fileIdx ?: -1)
        }
        stream.ytId?.takeIf { it.isNotBlank() }?.let {
            return PlaybackTarget.External("https://www.youtube.com/watch?v=$it")
        }
        stream.externalUrl?.takeIf { it.isNotBlank() }?.let {
            return if (TorrentLinks.isMagnet(it)) PlaybackTarget.Torrent(it, stream.fileIdx ?: -1)
            else PlaybackTarget.External(it)
        }
        return null
    }

    fun isTorrent(stream: Stream): Boolean = resolve(stream) is PlaybackTarget.Torrent

    fun magnetLink(stream: Stream, hash: String): String =
        TorrentLinks.magnet(
            hash = hash,
            name = stream.behaviorHints.filename,
            trackers = stream.sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
        )
}
