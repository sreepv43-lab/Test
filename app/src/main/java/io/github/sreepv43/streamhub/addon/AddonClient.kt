package io.github.sreepv43.streamhub.addon

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
        val body = http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw AddonException("HTTP ${response.code} for $url")
            withContext(Dispatchers.IO) { response.body?.string() }.orEmpty()
        }
        return try {
            StremioJson.decodeFromString(strategy, body)
        } catch (e: Exception) {
            throw AddonException("Invalid response from $url", e)
        }
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
