package io.github.sreepv43.streamhub.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.FocusColor

/**
 * Makes focus obvious when navigating with a TV remote: the element grows slightly and gets a
 * bright outline. Must be placed before the clickable/focusable modifier in the chain.
 *
 * Focus state is only read in the draw phase, so moving focus redraws the two affected items
 * without recomposing them.
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1.06f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "focusScale",
    )
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = FocusColor,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
}

/**
 * TV-style scrolling: the focused item is kept about a third of the way into a row or column,
 * so lists glide at a steady position instead of jumping when focus reaches the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
val TvPivotBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = PIVOT * containerSize
        // Items that can't fit at the pivot are aligned to the far edge instead.
        val leadingEdge = if (size <= containerSize && containerSize - target < size) containerSize - size else target
        return offset - leadingEdge
    }
}

private const val PIVOT = 0.3f
