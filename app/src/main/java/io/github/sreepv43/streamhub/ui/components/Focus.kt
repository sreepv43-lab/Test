package io.github.sreepv43.streamhub.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.FocusColor

/**
 * Makes focus obvious when navigating with a TV remote: the element grows slightly and gets a
 * bright outline. Must be placed before the clickable/focusable modifier in the chain.
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1.06f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .border(if (focused) 3.dp else 0.dp, if (focused) FocusColor else Color.Transparent, shape)
}
