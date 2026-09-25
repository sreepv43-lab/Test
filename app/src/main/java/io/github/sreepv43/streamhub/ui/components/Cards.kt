package io.github.sreepv43.streamhub.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .tvFocus(shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MetaCard(meta: Meta, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PosterCard(
        title = meta.name,
        image = meta.poster,
        onClick = onClick,
        landscape = meta.posterShape == "landscape",
        caption = meta.releaseInfo,
        modifier = modifier,
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
    onSeeAll: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterStart))
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll, modifier = Modifier.align(Alignment.CenterEnd).tvFocus()) {
                    Text("See all")
                }
            }
        }
        when (state) {
            RowState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is RowState.Failed -> Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            is RowState.Loaded -> if (state.metas.isEmpty()) {
                Text(
                    "Nothing here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.metas.distinctBy { it.type + it.id }, key = { it.type + it.id }) { meta ->
                        MetaCard(meta, onClick = { onMetaClick(meta) })
                    }
                }
            }
        }
    }
}

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
