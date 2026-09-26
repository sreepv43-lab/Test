package io.github.sreepv43.streamhub.data

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.sreepv43.streamhub.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Keeps the TV home screen's "Continue watching" (Play next) row in step with StreamHub: titles
 * in progress, and optionally My List. Choosing one there opens it in StreamHub.
 */
class WatchNext(
    private val context: Context,
    private val history: WatchHistory,
    private val library: Library,
    private val settings: Settings,
) {
    private val prefs = context.getSharedPreferences("watch_next", Context.MODE_PRIVATE)

    @OptIn(FlowPreview::class)
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(history.entries, library.items, settings.homeScreenRow.flow) { entries, list, mode ->
                programs(entries, list, mode)
            }
                // Playback saves the position every few seconds; the row needn't follow each one.
                .debounce(UPDATE_DELAY_MS)
                .collect { runCatching { publish(it) }.onFailure { Log.w(TAG, "Couldn't update the home screen row", it) } }
        }
    }

    /** What the row should show, by a key that stays the same for the same title. */
    private fun programs(entries: List<WatchEntry>, list: List<LibraryItem>, mode: String): Map<String, WatchNextProgram> {
        if (mode == "off") return emptyMap()
        val watching = entries.filter { !it.isFinished && it.positionMs > MIN_POSITION_MS }.take(MAX_CONTINUE)
        val programs = LinkedHashMap<String, WatchNextProgram>()
        watching.forEach { entry ->
            val episode = entry.type != "movie" && entry.videoId != entry.metaId
            programs["c:" + entry.metaId] = WatchNextProgram.Builder()
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setType(if (episode) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                .setLastEngagementTimeUtcMillis(entry.updatedAt)
                .setTitle(entry.name)
                .setEpisodeTitle(entry.videoTitle)
                .setPosterArtUri(entry.poster?.let(Uri::parse))
                .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                .setLastPlaybackPositionMillis(entry.positionMs.toInt())
                .setDurationMillis(entry.durationMs.toInt())
                .setIntentUri(openUri(entry.type, entry.metaId, entry.videoId))
                .setInternalProviderId("c:" + entry.metaId)
                .build()
        }
        if (mode == "continue-list") {
            val shown = watching.map { it.metaId }.toSet()
            list.filter { it.id !in shown }.take(MAX_LIST).forEach { item ->
                programs["l:" + item.id] = WatchNextProgram.Builder()
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
                    .setType(if (item.type == "movie") TvContractCompat.PreviewProgramColumns.TYPE_MOVIE else TvContractCompat.PreviewProgramColumns.TYPE_TV_SERIES)
                    .setLastEngagementTimeUtcMillis(item.addedAt)
                    .setTitle(item.name)
                    .setPosterArtUri(item.poster?.let(Uri::parse))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                    .setIntentUri(openUri(item.type, item.id, item.id))
                    .setInternalProviderId("l:" + item.id)
                    .build()
            }
        }
        return programs
    }

    /** Adds, updates and removes our programs so the row shows exactly [programs]. */
    @SuppressLint("RestrictedApi")
    private fun publish(programs: Map<String, WatchNextProgram>) {
        val resolver = context.contentResolver
        val published = prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
        val editor = prefs.edit()
        for ((key, id) in published) {
            if (key !in programs) {
                runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
                editor.remove(key)
            }
        }
        for ((key, program) in programs) {
            val values = program.toContentValues()
            val id = published[key]
            // Updating fails (0 rows) when the TV deleted it (e.g. cleared the row): add it again then.
            val updated = id != null && resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) > 0
            if (!updated) {
                resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                    ?.let { editor.putLong(key, ContentUris.parseId(it)) }
            }
        }
        editor.apply()
    }

    private fun openUri(type: String, metaId: String, videoId: String): Uri = Uri.parse(
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_TITLE)
            .putExtra(MainActivity.EXTRA_TYPE, type)
            .putExtra(MainActivity.EXTRA_META_ID, metaId)
            .putExtra(MainActivity.EXTRA_VIDEO_ID, videoId)
            .toUri(Intent.URI_INTENT_SCHEME),
    )

    private companion object {
        const val TAG = "WatchNext"
        const val UPDATE_DELAY_MS = 5_000L
        const val MIN_POSITION_MS = 60_000L
        const val MAX_CONTINUE = 10
        const val MAX_LIST = 20
    }
}
