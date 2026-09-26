package io.github.sreepv43.streamhub.ui.components

import io.github.sreepv43.streamhub.ui.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import io.github.sreepv43.streamhub.ui.TvScrolling
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.addon.Meta

val PosterWidth = 132.dp

@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    width: Dp = if (landscape) 236.dp else PosterWidth,
    progress: Float? = null,
    caption: String? = null,
    onFocused: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .then(if (onFocused != null) Modifier.onFocusChanged { if (it.hasFocus) onFocused() } else Modifier)
                .tvFocus(shape, scale = 1.07f)
                .clip(shape)
                .panel(shape)
                .clickable(onClick = onClick),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = AppColors.panelRaised,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .align(Alignment.BottomCenter),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        // Always takes its line, so every card (and every row) has the same height.
        Text(
            caption.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Last tile of a row, the same size as a poster. */
@Composable
fun SeeAllCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    CardFrame {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .tvFocus(shape, scale = 1.07f)
                .clip(shape)
                .panel(shape)
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = AppColors.accent)
            Text("See all", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Grey poster-shaped block shown while a row loads. */
@Composable
private fun PlaceholderCard(visible: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    CardFrame {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (visible) Modifier.panel(shape) else Modifier),
        )
    }
}

/** Poster-sized column with the two text lines a [PosterCard] has. */
@Composable
private fun CardFrame(image: @Composable () -> Unit) {
    Column(Modifier.width(PosterWidth)) {
        image()
        Text(" ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        Text(" ", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MetaCard(meta: Meta, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    PosterCard(
        title = meta.name,
        image = meta.poster,
        onClick = onClick,
        landscape = meta.posterShape == "landscape",
        caption = meta.releaseInfo,
        modifier = modifier,
        onFocused = onFocused,
    )
}

sealed interface RowState {
    data object Loading : RowState
    data class Loaded(val metas: List<Meta>) : RowState
    data class Failed(val message: String) : RowState
}

@Composable
fun MetaRow(
    title: String,
    state: RowState,
    onMetaClick: (Meta) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    onMetaFocused: ((Meta) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        val metas = (state as? RowState.Loaded)?.metas?.let { remember(it) { it.distinctBy { meta -> meta.type + meta.id } } }
        if (metas.isNullOrEmpty()) {
            // Same height as a loaded row, so nothing below moves when the row finishes loading.
            val message = when (state) {
                RowState.Loading -> null
                is RowState.Failed -> state.message
                is RowState.Loaded -> "Nothing here"
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(PLACEHOLDER_CARDS) { PlaceholderCard(visible = message == null) }
                }
                if (message != null) {
                    Text(
                        message,
                        color = if (state is RowState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        } else {
            PosterRow {
                items(metas, key = { it.type + it.id }, contentType = { "poster" }) { meta ->
                    MetaCard(
                        meta,
                        onClick = { onMetaClick(meta) },
                        onFocused = onMetaFocused?.let { callback -> { callback(meta) } },
                    )
                }
                if (onSeeAll != null) {
                    item(key = "see-all", contentType = "see-all") { SeeAllCard(onSeeAll) }
                }
            }
        }
    }
}

private const val PLACEHOLDER_CARDS = 10

/**
 * A horizontal row of posters: Left/Right stay inside it, it scrolls only near its edges, and when
 * the row is about to come on screen its first posters are prepared in advance, so moving to the
 * next row doesn't have to build them in the middle of the scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterRow(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState(
        prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = VISIBLE_POSTERS) },
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScrolling.Edge) {
        LazyRow(
            modifier = modifier.tvRow(),
            state = state,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

private const val VISIBLE_POSTERS = 9

/**
 * For a vertical list of rows (Home, search results; the list itself uses [TvScrolling.None]):
 * the row that gets the selection glides to the top in one short animation, so every Up/Down
 * moves the page by exactly one row and the next row is already on screen. The [first] row
 * scrolls the page back to its start instead, keeping anything above it visible.
 */
fun Modifier.alignRowOnFocus(state: LazyListState, index: Int, first: Boolean = false): Modifier = composed {
    val scope = rememberCoroutineScope()
    val gap = with(LocalDensity.current) { RowTopGap.toPx() }
    onFocusChanged { if (it.hasFocus) scope.launch { state.glideToRow(index, if (first) null else gap) } }
}

private suspend fun LazyListState.glideToRow(index: Int, gap: Float?) {
    if (gap == null) {
        if (firstVisibleItemIndex == 0) {
            animateScrollBy(-firstVisibleItemScrollOffset.toFloat(), RowGlide)
        } else {
            animateScrollToItem(0)
        }
        return
    }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val delta = item.offset - (layoutInfo.viewportStartOffset + gap)
    if (abs(delta) > 1f) animateScrollBy(delta, RowGlide)
}

private val RowTopGap = 8.dp
private val RowGlide = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
