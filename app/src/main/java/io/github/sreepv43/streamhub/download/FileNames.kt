package io.github.sreepv43.streamhub.download

import java.net.URLDecoder

object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")

    fun sanitize(name: String): String =
        name.replace(illegal, "_").replace(Regex("\\s+"), " ").trim().trim('.').take(180).ifEmpty { "video" }

    fun fromContentDisposition(header: String?): String? {
        if (header == null) return null
        Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(header)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)?.let { return it.groupValues[1].trim() }
        return null
    }

    fun fromUrl(url: String): String? {
        val segment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        return decoded.takeIf { extensionOf(it) in videoExtensions }
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun extensionForMime(mime: String?): String = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-msvideo" -> "avi"
        "video/quicktime" -> "mov"
        "video/mp2t" -> "ts"
        else -> "mkv"
    }

    /**
     * Picks the file name for a download: the addon-provided name, the server's name, the URL's
     * name, and finally the title with an extension derived from the content type.
     */
    fun choose(suggested: String?, contentDisposition: String?, url: String, title: String, mime: String?): String {
        val candidate = suggested?.takeIf { extensionOf(it) in videoExtensions }
            ?: fromContentDisposition(contentDisposition)
            ?: fromUrl(url)
            ?: "$title.${extensionForMime(mime)}"
        return sanitize(candidate)
    }

    /** Total size from a `Content-Range: bytes 100-199/1000` header. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
}
