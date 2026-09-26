package io.github.sreepv43.streamhub.sync

import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.StremioJson
import io.github.sreepv43.streamhub.data.Library
import io.github.sreepv43.streamhub.data.LibraryItem
import io.github.sreepv43.streamhub.data.WatchEntry
import io.github.sreepv43.streamhub.data.WatchHistory
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-time copy of a Stremio account: its addons, its library (into My List) and titles in progress
 * (into Continue watching). Signs in with the Stremio API, then signs out; nothing is stored.
 */
class StremioImport(
    private val http: OkHttpClient,
    private val addons: AddonRepository,
    private val library: Library,
    private val history: WatchHistory,
) {
    data class Summary(val addons: Int, val library: Int, val inProgress: Int)

    suspend fun run(email: String, password: String): Summary = withContext(Dispatchers.IO) {
        val login = call("login", buildJsonObject {
            put("type", "Login")
            put("email", email)
            put("password", password)
            put("facebook", false)
        })
        val authKey = login.jsonObject["authKey"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Stremio didn't accept the login")
        try {
            val collection = call("addonCollectionGet", buildJsonObject {
                put("type", "AddonCollectionGet")
                put("authKey", authKey)
                put("update", true)
            })
            val installed = addons.addons.value.map { it.transportUrl }.toSet()
            var addonCount = 0
            collection.jsonObject["addons"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["transportUrl"]?.jsonPrimitive?.contentOrNull }
                .filter { it !in installed && "127.0.0.1" !in it && "localhost" !in it }
                .forEach { url -> if (runCatching { addons.install(url) }.isSuccess) addonCount++ }

            val items = call("datastoreGet", buildJsonObject {
                put("authKey", authKey)
                put("collection", "libraryItem")
                put("ids", JsonArray(emptyList()))
                put("all", true)
            }).jsonArray.map { it.jsonObject }

            val saved = items.filter { !it.flag("removed") && !it.flag("temp") }.mapNotNull { it.toLibraryItem() }
            library.addAll(saved)

            var inProgress = 0
            items.forEach { item ->
                val state = item["state"]?.jsonObject ?: return@forEach
                val position = state["timeOffset"]?.jsonPrimitive?.longOrNull ?: 0
                val duration = state["duration"]?.jsonPrimitive?.longOrNull ?: 0
                val base = item.toLibraryItem() ?: return@forEach
                if (position <= 0 || duration <= 0 || position > duration * 0.95) return@forEach
                history.record(
                    WatchEntry(
                        metaId = base.id,
                        type = base.type,
                        name = base.name,
                        poster = base.poster,
                        videoId = state["video_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: base.id,
                        positionMs = position,
                        durationMs = duration,
                    ),
                )
                inProgress++
            }
            Summary(addonCount, saved.size, inProgress)
        } finally {
            runCatching { call("logout", buildJsonObject { put("type", "Logout"); put("authKey", authKey) }) }
        }
    }

    private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.toLibraryItem(): LibraryItem? {
        val id = this["_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return LibraryItem(
            id = id,
            type = this["type"]?.jsonPrimitive?.contentOrNull ?: "movie",
            name = this["name"]?.jsonPrimitive?.contentOrNull ?: id,
            poster = this["poster"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null },
        )
    }

    private fun call(method: String, body: JsonObject): JsonElement {
        val request = Request.Builder()
            .url("https://api.strem.io/api/$method")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val json = runCatching { StremioJson.parseToJsonElement(response.body!!.string()).jsonObject }
                .getOrElse { throw IOException("Unexpected answer from Stremio (${response.code})") }
            json["error"]?.let { error ->
                val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                throw IOException(message ?: "Stremio refused the request")
            }
            return json["result"] ?: throw IOException("Unexpected answer from Stremio")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
