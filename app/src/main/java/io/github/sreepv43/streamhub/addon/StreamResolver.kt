package io.github.sreepv43.streamhub.addon

/** What the app can do with a stream returned by an addon. */
sealed interface PlaybackTarget {
    /** A direct HTTP(S) URL the built-in player can open and the downloader can save. */
    data class Direct(val url: String, val headers: Map<String, String>) : PlaybackTarget

    /** Something that must be handed to another app (YouTube, a website, a torrent client). */
    data class External(val url: String) : PlaybackTarget
}

object StreamResolver {
    /**
     * @param streamingServerUrl optional Stremio streaming server (e.g. http://192.168.1.10:11470)
     * used to turn torrent streams into plain HTTP streams.
     */
    fun resolve(stream: Stream, streamingServerUrl: String?): PlaybackTarget? {
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        stream.url?.takeIf { it.isNotBlank() }?.let {
            return if (it.startsWith("magnet:", ignoreCase = true)) PlaybackTarget.External(it)
            else PlaybackTarget.Direct(it, headers)
        }
        stream.infoHash?.takeIf { it.isNotBlank() }?.let { hash ->
            val server = streamingServerUrl?.trim()?.trimEnd('/')
            return if (!server.isNullOrEmpty()) {
                PlaybackTarget.Direct("$server/${hash.lowercase()}/${stream.fileIdx ?: -1}", emptyMap())
            } else {
                PlaybackTarget.External(magnetLink(stream, hash))
            }
        }
        stream.ytId?.takeIf { it.isNotBlank() }?.let {
            return PlaybackTarget.External("https://www.youtube.com/watch?v=$it")
        }
        stream.externalUrl?.takeIf { it.isNotBlank() }?.let {
            return PlaybackTarget.External(it)
        }
        return null
    }

    fun isTorrent(stream: Stream): Boolean =
        !stream.infoHash.isNullOrBlank() || stream.url?.startsWith("magnet:", ignoreCase = true) == true

    fun magnetLink(stream: Stream, hash: String): String {
        val trackers = stream.sources
            .filter { it.startsWith("tracker:") }
            .map { "&tr=" + AddonUrls.encodeComponent(it.removePrefix("tracker:")) }
        val dn = stream.behaviorHints.filename?.let { "&dn=" + AddonUrls.encodeComponent(it) }.orEmpty()
        return "magnet:?xt=urn:btih:$hash$dn" + trackers.joinToString("")
    }
}
