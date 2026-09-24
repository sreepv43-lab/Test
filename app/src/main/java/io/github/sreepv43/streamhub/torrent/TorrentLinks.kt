package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.addon.AddonUrls
import java.net.URLDecoder

/**
 * Helpers for torrent sources. A torrent "source" is a magnet link, an http(s) URL of a
 * .torrent file, or a content:// / file:// URI of a local .torrent file.
 *
 * Inside the app a torrent file is addressed with a logical URL, `torrent:?src=…&file=…`, which
 * is stable across restarts (unlike the local streaming server's port) and is what downloads store.
 */
object TorrentLinks {
    private const val LOGICAL_PREFIX = "torrent:?"
    private val hexHash = Regex("^[0-9a-fA-F]{40}$")
    private val base32Hash = Regex("^[A-Za-z2-7]{32}$")

    fun isMagnet(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

    fun isTorrentFileUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true)

    fun isTorrentSource(url: String): Boolean = isMagnet(url) || isTorrentFileUrl(url)

    /** Accepts a bare info hash (hex or base32) and turns it into a magnet link. */
    fun normalizeSource(input: String): String {
        val text = input.trim()
        return if (hexHash.matches(text) || base32Hash.matches(text)) magnet(text) else text
    }

    fun magnet(hash: String, name: String? = null, trackers: List<String> = emptyList()): String =
        "magnet:?xt=urn:btih:$hash" +
            (name?.let { "&dn=" + AddonUrls.encodeComponent(it) } ?: "") +
            trackers.joinToString("") { "&tr=" + AddonUrls.encodeComponent(it) }

    /** Lower-case hex info hash of a magnet link (hex or base32 btih). */
    fun infoHashFromMagnet(magnet: String): String? {
        val xt = queryParams(magnet.substringAfter('?', ""))
            .filter { it.first == "xt" }
            .map { it.second }
            .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substring("urn:btih:".length)
            ?: return null
        return when {
            hexHash.matches(xt) -> xt.lowercase()
            base32Hash.matches(xt) -> base32ToHex(xt)
            else -> null
        }
    }

    fun displayName(source: String): String? = if (isMagnet(source)) {
        queryParams(source.substringAfter('?', "")).firstOrNull { it.first == "dn" }?.second
    } else {
        source.substringBefore('?').substringAfterLast('/').removeSuffix(".torrent").takeIf { it.isNotBlank() }
    }

    fun logicalUrl(source: String, fileIdx: Int): String =
        LOGICAL_PREFIX + "src=" + AddonUrls.encodeComponent(source) + "&file=" + fileIdx

    fun isLogicalUrl(url: String): Boolean = url.startsWith(LOGICAL_PREFIX)

    /** Parses `torrent:?src=…&file=…` (or the query of the local server's /stream path). */
    fun parseQuery(query: String): Pair<String, Int>? {
        val params = queryParams(query).toMap()
        val src = params["src"]?.takeIf { it.isNotEmpty() } ?: return null
        return src to (params["file"]?.toIntOrNull() ?: -1)
    }

    fun parseLogicalUrl(url: String): Pair<String, Int>? =
        if (isLogicalUrl(url)) parseQuery(url.removePrefix(LOGICAL_PREFIX)) else null

    fun streamPath(source: String, fileIdx: Int): String =
        "/stream?src=" + AddonUrls.encodeComponent(source) + "&file=" + fileIdx

    private fun queryParams(query: String): List<Pair<String, String>> =
        query.split('&').filter { it.isNotEmpty() }.map { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(key) to decode(value)
        }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun base32ToHex(input: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in input.uppercase()) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
            }
        }
        return out.toString()
    }
}

/** A parsed HTTP `Range: bytes=…` header, resolved against the resource length. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    companion object {
        /** Returns null when there is no (usable) range: serve the whole file. */
        fun parse(header: String?, totalLength: Long): ByteRange? {
            if (header == null || !header.startsWith("bytes=")) return null
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val startText = spec.substringBefore('-').trim()
            val endText = spec.substringAfter('-', "").trim()
            return if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                ByteRange((totalLength - suffix).coerceAtLeast(0), totalLength - 1)
            } else {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
                ByteRange(start, end)
            }
        }
    }
}
