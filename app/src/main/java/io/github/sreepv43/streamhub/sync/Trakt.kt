package io.github.sreepv43.streamhub.sync

import android.content.Context
import io.github.sreepv43.streamhub.BuildConfig
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.StremioJson
import io.github.sreepv43.streamhub.data.Library
import io.github.sreepv43.streamhub.data.LibraryItem
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Trakt.tv sync: sign in with a code on trakt.tv/activate, then finished movies and episodes are
 * marked as watched on Trakt, My List additions go to the Trakt watchlist, and the Trakt watchlist
 * is copied into My List. Needs a Trakt API app (client id and secret) built into the app.
 */
class Trakt(
    context: Context,
    private val http: OkHttpClient,
    private val library: Library,
    private val addons: AddonRepository,
) {
    sealed interface State {
        data object NotConfigured : State
        data object Disconnected : State
        data class WaitingForCode(val code: String, val url: String) : State
        data class Connected(val message: String? = null) : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences("trakt", Context.MODE_PRIVATE)
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        when {
            clientId.isEmpty() || clientSecret.isEmpty() -> State.NotConfigured
            prefs.getString(KEY_ACCESS, null) != null -> State.Connected()
            else -> State.Disconnected
        },
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val connected get() = _state.value is State.Connected

    /** Device login: shows a code to enter on trakt.tv/activate and waits until it's confirmed. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (_state.value == State.NotConfigured) return@withContext
        runCatching {
            val code = post("/oauth/device/code", buildJsonObject { put("client_id", clientId) }).use { it.json() }.jsonObject
            val deviceCode = code.string("device_code") ?: throw IOException("Trakt didn't send a code")
            _state.value = State.WaitingForCode(code.string("user_code").orEmpty(), code.string("verification_url") ?: "https://trakt.tv/activate")
            var interval = (code["interval"]?.jsonPrimitive?.intOrNull ?: 5) * 1_000L
            val deadline = System.currentTimeMillis() + (code["expires_in"]?.jsonPrimitive?.intOrNull ?: 600) * 1_000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval)
                val body = buildJsonObject {
                    put("code", deviceCode)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                }
                post("/oauth/device/token", body).use { response ->
                    when (response.code) {
                        200 -> {
                            saveTokens(response.json().jsonObject)
                            _state.value = State.Connected("Connected")
                            syncNow()
                            return@runCatching
                        }
                        400 -> Unit // not confirmed yet
                        429 -> interval += 1_000
                        else -> throw IOException("Trakt sign-in was not completed (${response.code})")
                    }
                }
            }
            throw IOException("The code expired; try again")
        }.onFailure { _state.value = State.Failed(it.message ?: "Couldn't connect to Trakt") }
    }

    /** [connect] / [syncNow] in the app's own scope, so they keep going when Settings is closed. */
    fun startConnect() {
        scope.launch { connect() }
    }

    fun startSync() {
        scope.launch { syncNow() }
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        _state.value = if (clientId.isEmpty()) State.NotConfigured else State.Disconnected
    }

    /** Copies the Trakt watchlist into My List. */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        if (!connected) return@withContext
        runCatching {
            val items = listOf("movies" to "movie", "shows" to "show").flatMap { (path, key) ->
                get("/sync/watchlist/$path").jsonArray.mapNotNull { entry ->
                    val media = entry.jsonObject[key]?.jsonObject ?: return@mapNotNull null
                    val imdb = media["ids"]?.jsonObject?.string("imdb") ?: return@mapNotNull null
                    LibraryItem(id = imdb, type = if (key == "show") "series" else "movie", name = media.string("title") ?: imdb)
                }
            }
            library.addAll(items)
            _state.value = State.Connected("Watchlist synced: ${items.size} titles")
            fillPosters(items)
        }.onFailure { _state.value = State.Connected("Sync failed: ${it.message}") }
    }

    /** Marks a finished movie or episode as watched on Trakt. */
    fun markWatched(type: String, videoId: String) = send {
        val ref = SyncIds.parse(videoId) ?: return@send
        post("/sync/history", itemsBody(type, ref)).close()
    }

    fun addToWatchlist(type: String, id: String) = send {
        val ref = SyncIds.parse(id) ?: return@send
        post("/sync/watchlist", itemsBody(type, ref)).close()
    }

    fun removeFromWatchlist(type: String, id: String) = send {
        val ref = SyncIds.parse(id) ?: return@send
        post("/sync/watchlist/remove", itemsBody(type, ref)).close()
    }

    private fun send(block: suspend () -> Unit) {
        if (!connected) return
        scope.launch { runCatching { block() } }
    }

    /** {"movies": [...]} / {"shows": [...]} / {"episodes"-in-a-show: ...} for Trakt's sync endpoints. */
    private fun itemsBody(type: String, ref: TraktRef): JsonObject {
        val ids = buildJsonObject { put("imdb", ref.imdb) }
        return buildJsonObject {
            if (ref.season != null && ref.episode != null) {
                put("shows", buildJsonArray {
                    add(buildJsonObject {
                        put("ids", ids)
                        put("seasons", buildJsonArray {
                            add(buildJsonObject {
                                put("number", ref.season)
                                put("episodes", buildJsonArray { add(buildJsonObject { put("number", ref.episode) }) })
                            })
                        })
                    })
                })
            } else {
                val key = if (SyncIds.traktType(type) == "show") "shows" else "movies"
                put(key, buildJsonArray { add(buildJsonObject { put("ids", ids) }) })
            }
        }
    }

    /** Trakt lists have no artwork; take posters from the installed metadata addons. */
    private suspend fun fillPosters(items: List<LibraryItem>) {
        val missing = items.filter { it.poster == null }.take(MAX_POSTER_LOOKUPS)
        val found = missing.mapNotNull { item ->
            runCatching { addons.meta(item.type, item.id) }.getOrNull()?.poster?.let { item.copy(poster = it) }
        }
        library.addAll(found)
    }

    private suspend fun accessToken(): String {
        val token = prefs.getString(KEY_ACCESS, null) ?: throw IOException("Not connected to Trakt")
        val expires = prefs.getLong(KEY_EXPIRES, 0)
        if (System.currentTimeMillis() < expires - REFRESH_MARGIN_MS) return token
        val body = buildJsonObject {
            put("refresh_token", prefs.getString(KEY_REFRESH, null) ?: throw IOException("Not connected to Trakt"))
            put("client_id", clientId)
            put("client_secret", clientSecret)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            put("grant_type", "refresh_token")
        }
        request("/oauth/token", body, auth = false).use { response ->
            if (!response.isSuccessful) {
                disconnect()
                throw IOException("Trakt sign-in expired; connect again")
            }
            saveTokens(response.json().jsonObject)
        }
        return prefs.getString(KEY_ACCESS, null)!!
    }

    private fun saveTokens(json: JsonObject) {
        val created = json["created_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() / 1000)
        val lifetime = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 0
        prefs.edit()
            .putString(KEY_ACCESS, json.string("access_token"))
            .putString(KEY_REFRESH, json.string("refresh_token"))
            .putLong(KEY_EXPIRES, (created + lifetime) * 1000)
            .apply()
    }

    private suspend fun get(path: String): JsonElement {
        val request = Request.Builder().url(API + path).authorized(accessToken())
        return http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Trakt answered ${response.code}")
            response.json()
        }
    }

    private suspend fun post(path: String, body: JsonObject): Response {
        val auth = path.startsWith("/sync")
        return request(path, body, auth)
    }

    private suspend fun request(path: String, body: JsonObject, auth: Boolean): Response {
        val builder = Request.Builder().url(API + path).post(body.toString().toRequestBody(JSON))
        if (auth) builder.authorized(accessToken()) else builder.header("Content-Type", "application/json")
        return http.newCall(builder.build()).execute()
    }

    private fun Request.Builder.authorized(token: String) = this
        .header("Content-Type", "application/json")
        .header("trakt-api-version", "2")
        .header("trakt-api-key", clientId)
        .header("Authorization", "Bearer $token")

    private fun Response.json(): JsonElement = StremioJson.parseToJsonElement(body?.string().orEmpty().ifEmpty { "{}" })

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val API = "https://api.trakt.tv"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
        const val MAX_POSTER_LOOKUPS = 40
        val JSON = "application/json".toMediaType()
    }
}
