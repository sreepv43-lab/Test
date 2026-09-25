package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.FlatChip
import io.github.sreepv43.streamhub.ui.components.panel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.addon.Video
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.rememberHistory
import io.github.sreepv43.streamhub.ui.components.resumeNote
import io.github.sreepv43.streamhub.ui.components.CenteredLoading
import io.github.sreepv43.streamhub.ui.components.CenteredMessage
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.StreamDialogs
import io.github.sreepv43.streamhub.ui.components.StreamsLoader
import io.github.sreepv43.streamhub.ui.components.WatchContext
import io.github.sreepv43.streamhub.ui.components.rememberStreamDialogState
import io.github.sreepv43.streamhub.ui.components.rememberStreamHandlers
import io.github.sreepv43.streamhub.ui.components.streamItems
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val loading: Boolean = true,
    val meta: Meta? = null,
    val error: String? = null,
    val season: Int? = null,
)

val Meta.isSeries: Boolean get() = videos.isNotEmpty()
val Meta.movieVideoId: String get() = behaviorHints.defaultVideoId ?: id
val Meta.seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })

/**
 * Loads metadata for a title and its streams: the movie's streams on the detail screen, or one
 * episode's streams when a `videoId` argument is present (Streams screen).
 */
class MetaViewModel(repository: AddonRepository, handle: SavedStateHandle) : ViewModel() {
    val type: String = checkNotNull(handle["type"])
    val metaId: String = handle.get<String>("id") ?: checkNotNull(handle["metaId"])
    val videoId: String? = handle["videoId"]

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()
    val streams = StreamsLoader(repository, this)

    init {
        viewModelScope.launch {
            val result = runCatching { repository.meta(type, metaId) }
            val meta = result.getOrNull()
                // No addon offers details for this id: still allow looking for streams.
                ?: Meta(id = metaId, type = type, name = metaId).takeIf { result.isSuccess }
            _state.update {
                it.copy(
                    loading = false,
                    meta = meta,
                    error = result.exceptionOrNull()?.message,
                    season = meta?.seasons?.firstOrNull(),
                )
            }
            if (meta != null) onMetaLoaded(meta)
        }
    }

    private fun onMetaLoaded(meta: Meta) {
        when {
            videoId != null -> streams.load(type, videoId)
            !meta.isSeries -> streams.load(type, meta.movieVideoId)
        }
    }

    fun selectSeason(season: Int) = _state.update { it.copy(season = season) }
}

@Composable
fun DetailScreen(onOpenEpisode: (type: String, metaId: String, videoId: String) -> Unit) {
    val vm = appViewModel { c, handle -> MetaViewModel(c.addons, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val streamsState by vm.streams.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dialogs = rememberStreamDialogState()
    val history by rememberHistory()
    val watch = {
        state.meta?.let { WatchContext(it.id, it.type, it.movieVideoId, it.name, poster = it.poster, background = it.background, logo = it.logo) }
    }
    val handlers = rememberStreamHandlers(dialogs, watch)
    StreamDialogs(dialogs, watch())

    val meta = state.meta
    when {
        state.loading -> CenteredLoading()
        meta == null -> CenteredMessage(state.error ?: "Not found")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                MetaHeader(meta, onTrailer = { ytId ->
                    StreamActions.openUrl(context, "https://www.youtube.com/watch?v=$ytId")
                })
            }
            if (meta.isSeries) {
                val seasons = meta.seasons
                if (seasons.size > 1) {
                    item(key = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons) { season ->
                                FlatChip(
                                    text = if (season == 0) "Specials" else "Season $season",
                                    selected = state.season == season,
                                    onClick = { vm.selectSeason(season) },
                                )
                            }
                        }
                    }
                }
                val episodes = meta.videos
                    .filter { seasons.isEmpty() || it.season == state.season }
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episodeNumber ?: 0 }))
                items(episodes, key = { "ep-" + it.id }) { video ->
                    EpisodeRow(video, onClick = { onOpenEpisode(meta.type, meta.id, video.id) })
                }
            } else {
                item(key = "streams-title") {
                    Text("Streams", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                resumeNote(history.firstOrNull { it.videoId == meta.movieVideoId && !it.isFinished && it.positionMs > 30_000 }?.positionMs)
                streamItems(streamsState, handlers.onPlay, handlers.onDownload, handlers.onExternal)
            }
        }
    }
}

/**
 * Header: the backdrop fills the top of the screen and fades into the page, with the title over it.
 */
@Composable
fun MetaHeader(meta: Meta, onTrailer: ((String) -> Unit)? = null, subtitle: String? = null) {
    val pageColor = AppColors.background
    val text = AppColors.text
    Box(Modifier.fillMaxWidth().heightIn(min = 360.dp)) {
        (meta.background ?: meta.poster)?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        val bg = pageColor
                        // Melt the artwork into the ambient background on the left and bottom.
                        drawRect(Brush.horizontalGradient(0f to bg.copy(alpha = 0.92f), 0.6f to bg.copy(alpha = 0.25f), 1f to Color.Transparent))
                        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg))
                    },
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 20.dp),
        ) {
            if (meta.logo != null) {
                AsyncImage(
                    model = meta.logo,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(96.dp).fillMaxWidth(),
                )
            } else {
                Text(meta.name, style = MaterialTheme.typography.displaySmall)
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 10.dp))
            }
            val info = listOfNotNull(
                meta.releaseInfo,
                meta.runtime,
                meta.imdbRating?.let { "★ $it" },
                meta.genres.take(3).joinToString(" · ").ifEmpty { null },
            ).joinToString("   ")
            if (info.isNotEmpty()) {
                Text(info, style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.72f), modifier = Modifier.padding(top = 10.dp))
            }
            meta.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = text.copy(alpha = 0.85f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (meta.cast.isNotEmpty()) {
                Text(
                    "Starring " + meta.cast.take(4).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = text.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val trailer = meta.trailers.firstOrNull { it.type == null || it.type == "Trailer" }?.source
            if (trailer != null && onTrailer != null) {
                FlatButton(
                    text = "Trailer",
                    icon = Icons.Default.Movie,
                    onClick = { onTrailer(trailer) },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(video: Video, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .tvFocus(shape, scale = 1.02f)
            .clip(shape)
            .panel(shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.panel)) {
            video.thumbnail?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            val number = video.episodeNumber?.let { "${video.season ?: ""}x${"%02d".format(it)}  " }.orEmpty()
            Text(number + video.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            video.released?.take(10)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (video.overview ?: video.description)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
