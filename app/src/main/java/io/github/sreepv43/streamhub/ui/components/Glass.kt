package io.github.sreepv43.streamhub.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import io.github.sreepv43.streamhub.ui.Ink

/**
 * "Liquid glass" building blocks.
 *
 * Real backdrop blur (Haze, Android 12+) is only used on a few large floating surfaces; every
 * other glass surface is drawn with translucent gradients and a specular edge, which looks alike
 * but costs almost nothing on low-end TV hardware.
 */

/** Blur source for floating glass surfaces; null disables real blur. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Whether real backdrop blur is allowed (user setting and Android version). */
val LocalGlassBlur = compositionLocalOf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }

val GlassShape = RoundedCornerShape(20.dp)

/** Translucent fill + specular rim: the cheap glass used for cards, rows and buttons. */
fun Modifier.glass(
    shape: Shape = GlassShape,
    tint: Color = Color.White,
    fillAlpha: Float = 0.07f,
    rimAlpha: Float = 0.22f,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline,
        brush = Brush.verticalGradient(
            0f to tint.copy(alpha = fillAlpha * 1.8f),
            0.5f to tint.copy(alpha = fillAlpha),
            1f to tint.copy(alpha = fillAlpha * 0.6f),
        ),
    )
    // Specular edge: bright along the top-left, fading towards the bottom-right.
    drawOutline(
        outline,
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = rimAlpha),
            0.45f to Color.White.copy(alpha = rimAlpha * 0.25f),
            1f to Color.White.copy(alpha = rimAlpha * 0.5f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * Frosted glass for large floating panels: blurs whatever is behind it when [LocalHazeState] is
 * provided and blur is enabled, otherwise falls back to a darker tint so text stays readable.
 * Only for surfaces drawn outside the blur source (e.g. the side menu), never inside screens.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frostedGlass(shape: Shape = GlassShape, blurRadius: Dp = 28.dp): Modifier {
    val haze = LocalHazeState.current
    val blur = LocalGlassBlur.current
    val base = if (haze != null && blur) {
        clip(shape).hazeChild(
            haze,
            HazeDefaults.style(Ink.copy(alpha = 0.35f), Color(0x14FFFFFF), blurRadius, 0.06f),
        ) {
            // Blur a downscaled copy: visually identical at this radius, much cheaper on TV GPUs.
            inputScale = HazeInputScale.Fixed(0.33f)
        }
    } else {
        background(Ink.copy(alpha = 0.72f), shape)
    }
    return base.glass(shape, fillAlpha = 0.06f, rimAlpha = 0.28f)
}

/**
 * The backdrop every screen sits on: deep ink, soft coloured light, and (optionally) the artwork
 * of the focused title, heavily blurred so it reads as ambient colour rather than an image.
 */
@Composable
fun AmbientBackground(imageUrl: String?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.fillMaxSize().background(Ink)) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x552E5BFF), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.1f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x40B04CFF), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.95f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            },
        )
        Crossfade(targetState = imageUrl, animationSpec = tween(600), label = "ambient") { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.55f }
                        .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(48.dp) else Modifier),
                )
            }
        }
        // Keep text legible: darken towards the left (menu/text side) and the bottom.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.85f), 0.55f to Ink.copy(alpha = 0.35f), 1f to Color.Transparent))
                drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ink.copy(alpha = 0.9f)))
            },
        )
        content()
    }
}

/** Lets screens choose the artwork shown (blurred) behind everything. */
class AmbientController {
    var image by mutableStateOf<String?>(null)
}

val LocalAmbient = staticCompositionLocalOf { AmbientController() }

/** Sets the ambient artwork while [imageUrl] is on screen. */
@Composable
fun AmbientImage(imageUrl: String?) {
    val ambient = LocalAmbient.current
    LaunchedEffect(imageUrl) { if (imageUrl != null) ambient.image = imageUrl }
}

/**
 * Pill-shaped glass button. [prominent] renders the bright "milk glass" style used for the main
 * action on a screen (Play, Install…); the others are clear glass.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val content = if (prominent) Ink else Color.White
    Row(
        modifier
            .tvFocus(shape, scale = 1.05f)
            .clip(shape)
            .then(
                if (prominent) Modifier.background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.96f), Color.White.copy(alpha = 0.80f))),
                    shape,
                ) else Modifier.glass(shape, fillAlpha = 0.10f, rimAlpha = 0.35f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Round glass icon button (download, delete, reorder…). */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .tvFocus(CircleShape, scale = 1.1f)
            .clip(CircleShape)
            .glass(CircleShape, fillAlpha = 0.10f, rimAlpha = 0.3f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Selectable glass chip (seasons, genres). */
@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .tvFocus(shape, scale = 1.05f)
            .clip(shape)
            .then(
                if (selected) Modifier.background(Color.White.copy(alpha = 0.9f), shape)
                else Modifier.glass(shape, fillAlpha = 0.08f, rimAlpha = 0.28f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Ink else Color.White)
    }
}

/** Text fields that sit on glass: clear container, luminous focus border. */
@Composable
fun glassTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.08f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
    focusedBorderColor = Color.White.copy(alpha = 0.85f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
    cursorColor = Color.White,
)

/** Dialogs float above everything, so they use deep smoked glass instead of a blur. */
val GlassDialogColor = Color(0xF0121520)
val GlassDialogShape = RoundedCornerShape(28.dp)
