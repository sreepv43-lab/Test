package io.github.sreepv43.soundhub.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

private val bitmaps = LruCache<String, ImageBitmap>(64)

/**
 * An album's artwork from [file], scaled down while it loads off the main thread; without one (or
 * until it has loaded), a coloured tile with the album's initials, the same colour every time.
 */
@Composable
fun AlbumArt(file: File?, title: String, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val cacheKey = file?.let { "${it.path}@$px" }
    val image by produceState<ImageBitmap?>(cacheKey?.let { bitmaps.get(it) }, cacheKey) {
        // The state outlives a change of file (e.g. the next song): start from the new one's.
        value = cacheKey?.let { bitmaps.get(it) }
        if (file == null || cacheKey == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { decode(file, px) }?.also { bitmaps.put(cacheKey, it) }
    }
    val shape = RoundedCornerShape(size / 12)
    Box(modifier.size(size).clip(shape).background(placeholder(title)), contentAlignment = Alignment.Center) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                initials(title),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value / 3).sp,
                maxLines = 1,
            )
        }
    }
}

/** A library album's artwork: the image in its folder (looked up once per folder, off the main thread). */
@Composable
fun AlbumCover(albumKey: String?, title: String, size: Dp, modifier: Modifier = Modifier) {
    val covers = LocalContext.current.container.covers
    val file by produceState<File?>(null, albumKey) {
        value = null
        value = withContext(Dispatchers.IO) { covers.cover(albumKey) }
    }
    AlbumArt(file, title, size, modifier)
}

private fun decode(file: File, px: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private fun placeholder(title: String): Brush {
    val hue = (title.lowercase().hashCode().absoluteValue % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsl(hue, 0.45f, 0.32f), Color.hsl((hue + 40f) % 360f, 0.5f, 0.18f)))
}

private fun initials(title: String): String =
    title.split(Regex("""[\s\-_]+""")).filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }

/** An album tile for the Home shelves: artwork with the title and artist under it. */
@Composable
fun AlbumCard(title: String, subtitle: String?, albumKey: String, key: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(164.dp)
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape, scale = 1.04f, key = key)
            .clip(shape)
            .background(if (focused) AppColors.rowFocused else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("card-$title"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AlbumCover(albumKey, title, 144.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
