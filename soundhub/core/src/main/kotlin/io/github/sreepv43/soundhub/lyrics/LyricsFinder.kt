package io.github.sreepv43.soundhub.lyrics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Lyrics kept on the device, one file per song, so each song is looked up once. */
class LyricsStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(id: String): Lyrics? = runCatching {
        File(dir, "$id.json").takeIf { it.isFile }?.let { json.decodeFromString<Lyrics>(it.readText()) }
    }.getOrNull()

    fun put(id: String, lyrics: Lyrics) {
        dir.mkdirs()
        File(dir, "$id.json").writeText(json.encodeToString(lyrics))
        File(dir, "$id.none").delete()
    }

    /** Remembers that nothing was found, so a song without lyrics isn't searched for at every play. */
    fun markMissing(id: String, now: Long) {
        dir.mkdirs()
        File(dir, "$id.none").writeText(now.toString())
    }

    fun missingRecently(id: String, now: Long, withinMs: Long = RETRY_AFTER_MS): Boolean {
        val at = File(dir, "$id.none").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return false
        return now - at < withinMs
    }

    fun forget(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.none").delete()
    }

    private companion object {
        const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000
    }
}

/** The LRCLIB lyrics database (lrclib.net): free, no account, time-synced lyrics for many songs. */
class LyricsClient(
    private val fetch: (String) -> String? = ::httpGet,
    private val base: String = "https://lrclib.net/api",
) {
    fun find(artist: String?, title: String, album: String?, durationSec: Int?): Lyrics? {
        val cleaned = cleanTitle(title)
        val exact = listOf(title, cleaned).distinct().firstNotNullOfOrNull { name ->
            if (artist.isNullOrBlank()) return@firstNotNullOfOrNull null
            val url = buildString {
                append(base).append("/get?artist_name=").append(enc(artist)).append("&track_name=").append(enc(name))
                if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
                if (durationSec != null) append("&duration=").append(durationSec)
            }
            fetch(url)?.let(::parseOne)
        }
        if (exact != null) return exact
        val query = listOfNotNull(artist?.takeIf { it.isNotBlank() }, cleaned).joinToString(" ")
        val body = fetch("$base/search?q=${enc(query)}") ?: return null
        val candidates = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull().orEmpty()
            .mapNotNull { (it as? JsonObject) }
            // Never show another song's words: the length must be close when we know it.
            .filter { durationSec == null || (it["duration"]?.jsonPrimitive?.doubleOrNull?.let { d -> abs(d - durationSec) <= 4 } ?: true) }
        return candidates.firstNotNullOfOrNull(::fromObject)
    }

    private fun parseOne(body: String): Lyrics? =
        runCatching { fromObject(Json.parseToJsonElement(body).jsonObject) }.getOrNull()

    private fun fromObject(obj: JsonObject): Lyrics? {
        if (obj["instrumental"]?.jsonPrimitive?.boolean == true) return null
        val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(Lrc::parse).orEmpty()
        val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Lyrics(synced, plain, "LRCLIB").takeUnless { it.isEmpty }
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    companion object {
        /** "Song (feat. X) - Remastered 2011" → "Song": the extras that stop an exact match. */
        fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[(\[](feat\.?|ft\.?|with|from|remaster(ed)?|deluxe|live|version|mono|stereo)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+(remaster(ed)?|live|single version|radio edit|mono|stereo).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { title }

        fun httpGet(url: String): String? = try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "SoundHub (https://github.com/sreepv43-lab/Test)")
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Finds a song's lyrics: kept copy, then an `.lrc`/`.txt` next to the file, then LRCLIB. */
class LyricsFinder(
    private val store: LyricsStore,
    private val client: LyricsClient = LyricsClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun find(
        id: String,
        artist: String?,
        title: String,
        album: String?,
        durationSec: Int?,
        localSong: File?,
        refresh: Boolean = false,
    ): Lyrics? {
        if (refresh) store.forget(id)
        store.get(id)?.let { return it }
        sidecar(localSong)?.let {
            store.put(id, it)
            return it
        }
        if (store.missingRecently(id, now())) return null
        val found = client.find(artist, title, album, durationSec)
        if (found != null) store.put(id, found) else store.markMissing(id, now())
        return found
    }

    private fun sidecar(song: File?): Lyrics? {
        val base = song?.takeIf { it.isFile }?.let { File(it.parentFile, it.nameWithoutExtension) } ?: return null
        File(base.path + ".lrc").takeIf { it.isFile }?.let { file ->
            val text = runCatching { file.readText() }.getOrNull().orEmpty()
            val lines = Lrc.parse(text)
            if (lines.isNotEmpty()) return Lyrics(lines, "", "File")
            if (text.isNotBlank()) return Lyrics(plain = text.trim(), source = "File")
        }
        File(base.path + ".txt").takeIf { it.isFile }?.let { file ->
            val text = runCatching { file.readText() }.getOrNull().orEmpty().trim()
            if (text.isNotEmpty()) return Lyrics(plain = text, source = "File")
        }
        return null
    }
}
