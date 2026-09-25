package io.github.sreepv43.streamhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.Accent
import io.github.sreepv43.streamhub.ui.Ink
import io.github.sreepv43.streamhub.ui.Panel
import io.github.sreepv43.streamhub.ui.PanelRaised

/**
 * Flat design building blocks: solid colours only (no gradients, blur or transparency), which is
 * the cheapest thing a TV GPU can draw.
 */

val PanelShape = RoundedCornerShape(14.dp)

/** Solid rounded background for cards, rows and sections. */
fun Modifier.panel(shape: Shape = PanelShape, color: Color = Panel): Modifier = background(color, shape)

/** Pill button. [prominent] is the solid accent style for the main action on a screen. */
@Composable
fun FlatButton(
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
            .tvFocus(shape)
            .clip(shape)
            .background(if (prominent) Accent else PanelRaised, shape)
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

/** Round icon button (download, delete, reorder…). */
@Composable
fun FlatIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .tvFocus(CircleShape)
            .clip(CircleShape)
            .background(PanelRaised, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Selectable chip (seasons, genres). */
@Composable
fun FlatChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .tvFocus(shape)
            .clip(shape)
            .background(if (selected) Color.White else PanelRaised, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Ink else Color.White)
    }
}

@Composable
fun flatTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel,
    focusedBorderColor = Accent,
    unfocusedBorderColor = PanelRaised,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
    cursorColor = Color.White,
)

val DialogColor = Color(0xFF1A1E27)
val DialogShape = RoundedCornerShape(20.dp)
