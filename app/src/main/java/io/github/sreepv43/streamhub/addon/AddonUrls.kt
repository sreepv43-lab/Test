package io.github.sreepv43.streamhub.addon

import java.net.URLEncoder

object AddonUrls {
    private const val MANIFEST = "/manifest.json"

    /**
     * Turns whatever the user typed or clicked (stremio:// links, URLs without scheme or
     * without /manifest.json) into a canonical https manifest URL.
     */
    fun normalizeManifestUrl(input: String): String {
        var url = input.trim()
        require(url.isNotEmpty()) { "Addon URL is empty" }
        url = when {
            url.startsWith("stremio://", ignoreCase = true) -> "https://" + url.substring("stremio://".length)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            else -> "https://$url"
        }
        val query = url.substringAfter('?', "")
        var path = url.substringBefore('?')
        if (!path.endsWith(MANIFEST)) {
            path = path.trimEnd('/') + MANIFEST
        }
        return if (query.isEmpty()) path else "$path?$query"
    }

    /** Every addon link in what the user typed or pasted (separated by spaces, commas or new lines). */
    fun splitInput(text: String): List<String> =
        text.split(Regex("""[\s,]+"""))
            .map { it.trim().trimEnd('.', ';') }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("stremio://", true) }
            .distinct()

    /** Base URL of an addon (the manifest URL without the trailing /manifest.json). */
    fun baseUrl(transportUrl: String): String =
        transportUrl.substringBefore('?').removeSuffix(MANIFEST).trimEnd('/')

    fun resourceUrl(
        transportUrl: String,
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): String {
        val sb = StringBuilder(baseUrl(transportUrl))
            .append('/').append(resource)
            .append('/').append(encodeComponent(type))
            .append('/').append(encodeComponent(id))
        if (extra.isNotEmpty()) {
            sb.append('/').append(extra.joinToString("&") { (k, v) -> encodeComponent(k) + "=" + encodeComponent(v) })
        }
        sb.append(".json")
        val query = transportUrl.substringAfter('?', "")
        if (query.isNotEmpty()) sb.append('?').append(query)
        return sb.toString()
    }

    fun configureUrl(transportUrl: String): String = baseUrl(transportUrl) + "/configure"

    /** Equivalent of JavaScript's encodeURIComponent, which is what Stremio uses. */
    fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
}
