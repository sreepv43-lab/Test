package io.github.sreepv43.streamhub.data

import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.addon.StremioJson
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The Home rows shown last time (only what a poster needs), so the next start shows them at once
 * while they reload in the background.
 */
class HomeCache(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(Meta.serializer()))

    fun read(): Map<String, List<Meta>> =
        runCatching { StremioJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    fun write(rows: Map<String, List<Meta>>) {
        runCatching {
            val lean = rows.mapValues { (_, metas) ->
                metas.take(MAX_PER_ROW).map { Meta(it.id, it.type, it.name, it.poster, it.posterShape, releaseInfo = it.releaseInfo) }
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(StremioJson.encodeToString(serializer, lean))
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX_PER_ROW = 40
    }
}
