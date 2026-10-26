package io.github.sreepv43.streamhub.data

import android.content.Context
import io.github.sreepv43.streamhub.addon.MdbList
import io.github.sreepv43.streamhub.addon.TitleRatings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Rotten Tomatoes (and other) scores from MDBList, with the user's free key. Posters and title pages
 * ask with [want]; titles are looked up in batches of ten and remembered for a week, so a free
 * account's 1,000 requests a day go a long way. Without a key nothing is fetched.
 */
class RatingsStore(context: Context, private val http: OkHttpClient, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("ratings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), TitleRatings.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ratings = MutableStateFlow(load())
    val ratings: StateFlow<Map<String, TitleRatings>> = _ratings.asStateFlow()

    /** Why scores can't be fetched right now (bad key, daily limit), for Settings. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val pending = LinkedHashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var blockedUntil = 0L
    @Volatile private var rejectedKey: String? = null
    /** The batch lookup isn't accepted (older API, account type): one title per request instead. */
    @Volatile private var singleOnly = false
    private val save = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) {
                delay(GATHER_MS)
                drain()
            }
        }
        scope.launch {
            for (signal in save) {
                delay(SAVE_DELAY_MS)
                persist()
            }
        }
    }

    val enabled: Boolean get() = key().isNotEmpty()

    /** The scores of [metaId] as they arrive (null until known). */
    fun ratingsFor(metaId: String): Flow<TitleRatings?> {
        val id = MdbList.imdbId(metaId)
        return ratings.map { all -> id?.let { all[it] } }.distinctUntilChanged()
    }

    fun cached(metaId: String): TitleRatings? = MdbList.imdbId(metaId)?.let { _ratings.value[it] }

    /** Asks for [metaId]'s scores unless they are known and recent. */
    fun want(metaId: String) {
        if (!enabled) return
        val id = MdbList.imdbId(metaId) ?: return
        _ratings.value[id]?.let { if (fresh(it)) return }
        synchronized(pending) { if (!pending.add(id)) return }
        wake.trySend(Unit)
    }

    /** Settings' "Check key": looks up one well-known film and says what came back. */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        val key = key()
        if (key.isEmpty()) return@withContext "Enter your MDBList key first."
        rejectedKey = null
        blockedUntil = 0
        val url = "${MdbList.BASE}/imdb/movie/$CHECK_ID".toHttpUrl().newBuilder().addQueryParameter("apikey", key).build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use problemFor(response.code, text, key) ?: "MDBList answered ${response.code}."
                _problem.value = null
                val found = MdbList.parseAny(json.parseToJsonElement(text), System.currentTimeMillis())
                remember(found)
                val dune = found[CHECK_ID]
                if (dune?.tomatoLine != null) "The key works. Dune (2021): ${dune.tomatoLine}" else "The key works, but no Rotten Tomatoes score came back for the test film."
            }
        } catch (e: IOException) {
            "Couldn't reach MDBList (${e.message ?: "no connection"})."
        }
    }

    private fun key(): String = settings.ratingsKey.value.trim()

    private fun fresh(ratings: TitleRatings): Boolean {
        val age = System.currentTimeMillis() - ratings.fetchedAt
        return age < if (ratings.isEmpty) EMPTY_TTL_MS else TTL_MS
    }

    private fun drain() {
        while (true) {
            val key = key()
            if (key.isEmpty() || key == rejectedKey || System.currentTimeMillis() < blockedUntil) return
            val batch = synchronized(pending) {
                pending.take(if (singleOnly) 1 else MdbList.BATCH).also { pending.removeAll(it.toSet()) }
            }
            if (batch.isEmpty()) return
            val ok = runCatching { fetch(batch, key) }.getOrElse { false }
            if (!ok) return
        }
    }

    /** Looks up [ids]; false when lookups should stop for now (the ids are kept for later). */
    private fun fetch(ids: List<String>, key: String): Boolean {
        val request = if (ids.size == 1 && singleOnly) {
            Request.Builder().url("${MdbList.BASE}/imdb/any/${ids.single()}".toHttpUrl().newBuilder().addQueryParameter("apikey", key).build()).build()
        } else {
            val body = buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }.toString()
            Request.Builder()
                .url("${MdbList.BASE}/imdb/any/".toHttpUrl().newBuilder().addQueryParameter("apikey", key).build())
                .post(body.toRequestBody(JSON))
                .build()
        }
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        _problem.value = null
                        val now = System.currentTimeMillis()
                        val found = MdbList.parseAny(json.parseToJsonElement(text), now)
                        // Titles MDBList doesn't know are remembered as empty, so they aren't asked again for a while.
                        remember(ids.associateWith { TitleRatings(fetchedAt = now) } + found)
                        true
                    }
                    response.code == 429 -> {
                        val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400) ?: 3_600
                        blockedUntil = System.currentTimeMillis() + wait * 1_000
                        _problem.value = if (text.contains("Daily", ignoreCase = true)) {
                            "Today's free MDBList lookups are used up; scores already fetched still show."
                        } else {
                            "MDBList asked to slow down; scores continue in a few minutes."
                        }
                        requeue(ids)
                        false
                    }
                    response.code in listOf(400, 404, 405) && !singleOnly && ids.size > 1 -> {
                        singleOnly = true
                        requeue(ids)
                        true
                    }
                    else -> {
                        if (response.code == 404) remember(ids.associateWith { TitleRatings(fetchedAt = System.currentTimeMillis()) })
                        problemFor(response.code, text, key)?.let { _problem.value = it }
                        response.code != 401 && response.code != 403
                    }
                }
            }
        } catch (e: IOException) {
            // Offline: these titles are asked again when they are shown next time.
            false
        }
    }

    private fun problemFor(code: Int, text: String, key: String): String? = when (code) {
        401, 403 -> {
            rejectedKey = key
            synchronized(pending) { pending.clear() }
            "MDBList didn't accept this key. Copy it again from mdblist.com → Preferences → API key.".also { _problem.value = it }
        }
        429 -> "Today's free MDBList lookups are used up."
        else -> null
    }

    private fun requeue(ids: List<String>) = synchronized(pending) { pending.addAll(ids) }

    private fun remember(found: Map<String, TitleRatings>) {
        if (found.isEmpty()) return
        val merged = _ratings.value + found
        _ratings.value = if (merged.size > MAX_ENTRIES) {
            merged.entries.sortedByDescending { it.value.fetchedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
        } else {
            merged
        }
        save.trySend(Unit)
    }

    private fun persist() {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(serializer, _ratings.value)).apply() }
    }

    private fun load(): Map<String, TitleRatings> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "scores"
        const val CHECK_ID = "tt1160419"
        const val GATHER_MS = 400L
        const val SAVE_DELAY_MS = 2_000L
        const val TTL_MS = 7L * 24 * 3600 * 1000
        const val EMPTY_TTL_MS = 2L * 24 * 3600 * 1000
        const val MAX_ENTRIES = 5_000
        val JSON = "application/json".toMediaType()
    }
}
