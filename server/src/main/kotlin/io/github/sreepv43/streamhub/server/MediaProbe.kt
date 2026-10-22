package io.github.sreepv43.streamhub.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/** Video details (resolution, codecs, HDR, audio) from ffprobe, when it is installed on the server. */
object MediaProbe {
    private val ffprobe: String? by lazy {
        System.getenv("FFPROBE")?.takeIf { File(it).canExecute() }
            ?: listOf("ffprobe", "ffprobe.exe").firstOrNull { name ->
                runCatching {
                    val process = ProcessBuilder(name, "-version").redirectErrorStream(true).start()
                    process.inputStream.readAllBytes()
                    process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
                }.getOrDefault(false)
            }
    }

    val available: Boolean get() = ffprobe != null

    fun probe(file: File): MediaInfo? {
        val tool = ffprobe ?: return null
        return runCatching {
            val process = ProcessBuilder(tool, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", file.path)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parse(output)
        }.getOrNull()
    }

    fun parse(json: String): MediaInfo? {
        val root = ServerJson.parseToJsonElement(json).jsonObject
        val streams = root["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val video = streams.firstOrNull { it.str("codec_type") == "video" && !it.isPicture() }
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
        val hdr = when {
            video == null -> null
            video.toString().contains("DOVI", ignoreCase = true) || video.toString().contains("Dolby Vision", ignoreCase = true) -> "Dolby Vision"
            video.str("color_transfer") == "smpte2084" -> "HDR10"
            video.str("color_transfer") == "arib-std-b67" -> "HLG"
            else -> null
        }
        val channels = audio?.get("channels")?.jsonPrimitive?.intOrNull
        val duration = root["format"]?.jsonObject?.str("duration")?.toDoubleOrNull()
        return MediaInfo(
            width = video?.get("width")?.jsonPrimitive?.intOrNull,
            height = video?.get("height")?.jsonPrimitive?.intOrNull,
            videoCodec = video?.str("codec_name")?.uppercase(),
            hdr = hdr,
            audio = audio?.let { a ->
                listOfNotNull(a.str("codec_name")?.uppercase(), channels?.let { c -> if (c == 6) "5.1" else if (c == 8) "7.1" else if (c == 2) "2.0" else "${c}ch" })
                    .joinToString(" ")
            },
            durationMs = duration?.let { (it * 1000).toLong() },
        )
    }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** Cover art inside an MKV/MP4 is reported as a video stream too. */
    private fun JsonObject.isPicture(): Boolean =
        this["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull == 1

    private const val PROBE_TIMEOUT_S = 30L
}
