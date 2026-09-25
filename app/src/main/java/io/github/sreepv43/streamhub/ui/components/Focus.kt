package io.github.sreepv43.streamhub.ui.components

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
import io.github.sreepv43.streamhub.ui.LocalPalette

/**
 * Focus highlight for TV remotes: the element grows a little and gets a thin glowing outline.
 * No animation, so moving focus never makes anything wobble. Place before clickable/focusable.
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1.05f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = LocalPalette.current.focus
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            val s = if (focused) scale else 1f
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, color = ring.copy(alpha = 0.25f), style = Stroke(width = 5.dp.toPx()))
                drawOutline(outline, color = ring, style = Stroke(width = 2.dp.toPx()))
            }
        }
}
