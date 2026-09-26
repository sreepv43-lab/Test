package io.github.sreepv43.streamhub.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.sreepv43.streamhub.ui.LocalTvPage
import io.github.sreepv43.streamhub.ui.LocalTvShell
import io.github.sreepv43.streamhub.ui.TvShellState
import androidx.compose.ui.node.ModifierNodeElement
import io.github.sreepv43.streamhub.ui.LocalPalette

/**
 * Focus highlight for TV remotes: the element grows a little and gets a thin glowing outline.
 * No animation, so moving focus never makes anything wobble. Place before clickable/focusable.
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1.05f,
    key: Any? = null,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = LocalPalette.current.focus
    val shell = LocalTvShell.current
    val page = LocalTvPage.current
    val requester = remember { FocusRequester() }
    // Remembered by [key] (e.g. a movie id, within its row) when given, else by its place in the UI.
    val scope = LocalFocusKeyScope.current
    val place = currentCompositeKeyHash
    val id = if (key != null) (scope to key).hashCode() else place
    this
        .focusRequester(requester)
        .then(if (shell != null) TvFocusRegistration(shell, page, id, requester) else Modifier)
        .onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) shell?.focused(page, id)
        }
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

/**
 * Makes the element known to the shell (to restore the selection) only while it is attached, i.e.
 * really on screen: posters a lazy row composed in advance or keeps for reuse have a FocusRequester
 * that can't take focus yet, and handing one of those to Compose as the page's entry crashes.
 */
private data class TvFocusRegistration(
    val shell: TvShellState,
    val page: Any?,
    val id: Int,
    val requester: FocusRequester,
) : ModifierNodeElement<TvFocusRegistrationNode>() {
    override fun create() = TvFocusRegistrationNode(shell, page, id, requester)

    override fun update(node: TvFocusRegistrationNode) = node.update(shell, page, id, requester)
}

private class TvFocusRegistrationNode(
    private var shell: TvShellState,
    private var page: Any?,
    private var id: Int,
    private var requester: FocusRequester,
) : Modifier.Node() {
    override fun onAttach() = shell.register(page, id, requester)

    override fun onDetach() = shell.unregister(page, id, requester)

    fun update(shell: TvShellState, page: Any?, id: Int, requester: FocusRequester) {
        if (isAttached) this.shell.unregister(this.page, this.id, this.requester)
        this.shell = shell
        this.page = page
        this.id = id
        this.requester = requester
        if (isAttached) shell.register(page, id, requester)
    }
}

/** Groups the [tvFocus] keys of one list (e.g. a row title), so the same movie in two rows stays apart. */
val LocalFocusKeyScope = staticCompositionLocalOf<Any?> { null }

/**
 * For horizontal lists: Left/Right never leave the list, so at the first item Left opens the side
 * menu instead of jumping diagonally into a neighbouring, partly scrolled row.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvRow(): Modifier = focusProperties {
    exit = { direction ->
        if (direction == FocusDirection.Left || direction == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default
    }
}
