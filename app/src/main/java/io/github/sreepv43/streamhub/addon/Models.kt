package io.github.sreepv43.streamhub.addon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Data model of the Stremio addon protocol.
 * See https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api
 */

val StremioJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Manifest(
    val id: String,
    val version: String = "",
    val name: String = id,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    val resources: List<@Serializable(with = ResourceDefSerializer::class) ResourceDef> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: ManifestHints = ManifestHints(),
)

@Serializable
data class ManifestHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

/** A resource is declared either as a bare name ("stream") or as a full object. */
@Serializable
data class ResourceDef(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

object ResourceDefSerializer : JsonTransformingSerializer<ResourceDef>(ResourceDef.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) buildJsonObject { put("name", element.content) } else element
}

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
) {
    fun supportsExtra(name: String): Boolean =
        extra.any { it.name == name } || name in extraSupported

    val requiredExtras: Set<String>
        get() = (extra.filter { it.isRequired }.map { it.name } + extraRequired).toSet()

    fun options(name: String): List<String> = extra.firstOrNull { it.name == name }?.options.orEmpty()
}

@Serializable
data class ExtraDef(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val optionsLimit: Int = 1,
)

@Serializable
data class Meta(
    val id: String,
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val behaviorHints: MetaHints = MetaHints(),
)

@Serializable
data class MetaHints(
    val defaultVideoId: String? = null,
)

@Serializable
data class Trailer(val source: String, val type: String? = null)

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val streams: List<Stream> = emptyList(),
) {
    val displayTitle: String
        get() = title ?: name ?: "Episode ${episode ?: number ?: ""}".trim()

    val episodeNumber: Int?
        get() = episode ?: number
}

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val behaviorHints: StreamHints = StreamHints(),
) {
    /** Newer addons use `description`, older ones `title`. */
    val details: String?
        get() = description ?: title
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

@Serializable
data class Subtitle(
    val id: String? = null,
    val url: String,
    val lang: String = "und",
)

@Serializable
data class CatalogResponse(val metas: List<Meta> = emptyList())

@Serializable
data class MetaResponse(val meta: Meta? = null)

@Serializable
data class StreamsResponse(val streams: List<Stream> = emptyList())

@Serializable
data class SubtitlesResponse(val subtitles: List<Subtitle> = emptyList())

/** An addon the user has installed: where it lives plus its last fetched manifest. */
@Serializable
data class InstalledAddon(
    val transportUrl: String,
    val manifest: Manifest,
) {
    fun supports(resource: String, type: String, id: String): Boolean {
        val def = manifest.resources.firstOrNull { it.name == resource } ?: return false
        val types = def.types ?: manifest.types
        if (type !in types) return false
        val prefixes = def.idPrefixes ?: manifest.idPrefixes
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }
}

internal val installedAddonListSerializer: KSerializer<List<InstalledAddon>> =
    kotlinx.serialization.builtins.ListSerializer(InstalledAddon.serializer())
