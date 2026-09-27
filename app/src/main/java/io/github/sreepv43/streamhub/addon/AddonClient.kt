package io.github.sreepv43.streamhub.addon

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AddonException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What an addon's HTTP error means, in words (the URL's host names the addon's server). */
internal fun errorMessage(code: Int, url: String): String {
    val host = url.toHttpUrlOrNull()?.host ?: url
    return when (code) {
        502, 503, 504 -> "The addon's server ($host) is busy or not responding (HTTP $code). Try again in a little while."
        429 -> "The addon's server ($host) is getting too many requests (HTTP 429). Try again in a little while."
        404 -> "The addon doesn't have this (HTTP 404)."
        in 500..599 -> "The addon's server ($host) had an error (HTTP $code)."
        else -> "The addon answered HTTP $code ($host)."
    }
}

/** Talks the Stremio addon HTTP protocol. */
class AddonClient(private val http: OkHttpClient) {

    suspend fun fetchManifest(input: String): InstalledAddon {
        val url = AddonUrls.normalizeManifestUrl(input)
        val manifest = get(url, Manifest.serializer())
        return InstalledAddon(transportUrl = url, manifest = manifest)
    }

    suspend fun catalog(
        addon: InstalledAddon,
        type: String,
        id: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): List<Meta> =
        get(AddonUrls.resourceUrl(addon.transportUrl, "catalog", type, id, extra), CatalogResponse.serializer()).metas

    suspend fun meta(addon: InstalledAddon, type: String, id: String): Meta? =
        get(AddonUrls.resourceUrl(addon.transportUrl, "meta", type, id), MetaResponse.serializer()).meta

    suspend fun streams(addon: InstalledAddon, type: String, id: String): List<Stream> =
        get(AddonUrls.resourceUrl(addon.transportUrl, "stream", type, id), StreamsResponse.serializer()).streams

    suspend fun subtitles(addon: InstalledAddon, type: String, id: String): List<Subtitle> =
        get(AddonUrls.resourceUrl(addon.transportUrl, "subtitles", type, id), SubtitlesResponse.serializer()).subtitles

    private suspend fun <T> get(url: String, strategy: DeserializationStrategy<T>): T {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        var attempt = 0
        var body: String? = null
        while (body == null) {
            attempt++
            val (code, text) = http.newCall(request).await().use { response ->
                response.code to if (response.isSuccessful) withContext(Dispatchers.IO) { response.body?.string() }.orEmpty() else null
            }
            body = text
            if (body != null) break
            // Community-hosted addons are often briefly overloaded: try once more before giving up.
            if (code in BUSY && attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            else throw AddonException(errorMessage(code, url))
        }
        // Parsing a big catalog or a series with hundreds of episodes takes a while; never on the
        // main thread, where it would stall scrolling.
        return withContext(Dispatchers.Default) {
            try {
                StremioJson.decodeFromString(strategy, body!!)
            } catch (e: Exception) {
                throw AddonException("Invalid response from $url", e)
            }
        }
    }

    private companion object {
        val BUSY = setOf(429, 502, 503, 504)
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_500L
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
