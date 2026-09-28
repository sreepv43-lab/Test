package io.github.sreepv43.soundhub.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.dp
import io.github.sreepv43.soundhub.ui.FocusColor
import io.github.sreepv43.soundhub.ui.LocalTvPage
import io.github.sreepv43.soundhub.ui.LocalTvShell
import io.github.sreepv43.soundhub.ui.TvShellState

/**
 * Focus highlight for TV remotes: a bright outline (and a slight zoom for buttons). No animation,
 * so moving through a list never makes it wobble. Place before clickable/focusable.
 *
 * The element is also made known to the TV shell, so coming back to a page puts the selection
 * where it was. [key] identifies list rows across updates (e.g. an album's key); without it the
 * element is identified by its place in the UI. [pageDefault] marks the element a page starts on
 * when nothing was selected there before (e.g. Play/Pause on Now playing).
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = 1f,
    key: Any? = null,
    pageDefault: Boolean = false,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val shell = LocalTvShell.current
    val page = LocalTvPage.current
    val requester = remember { FocusRequester() }
    val place = currentCompositeKeyHash
    val id = key?.hashCode() ?: place
    this
        .focusRequester(requester)
        .then(if (shell != null) TvFocusRegistration(shell, page, id, requester, pageDefault) else Modifier)
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
                drawOutline(outline, color = FocusColor.copy(alpha = 0.3f), style = Stroke(width = 6.dp.toPx()))
                drawOutline(outline, color = FocusColor, style = Stroke(width = 3.dp.toPx()))
            }
        }
}

/**
 * Registers the element with the shell only while it is attached (really on screen): rows a lazy
 * list composed in advance have a FocusRequester that can't take focus yet, and handing one of
 * those to Compose as a page's entry crashes.
 */
private data class TvFocusRegistration(
    val shell: TvShellState,
    val page: Any?,
    val id: Int,
    val requester: FocusRequester,
    val pageDefault: Boolean,
) : ModifierNodeElement<TvFocusRegistrationNode>() {
    override fun create() = TvFocusRegistrationNode(shell, page, id, requester, pageDefault)

    override fun update(node: TvFocusRegistrationNode) = node.update(shell, page, id, requester, pageDefault)
}

private class TvFocusRegistrationNode(
    private var shell: TvShellState,
    private var page: Any?,
    private var id: Int,
    private var requester: FocusRequester,
    private var pageDefault: Boolean,
) : Modifier.Node() {
    override fun onAttach() = register()

    override fun onDetach() = unregister()

    fun update(shell: TvShellState, page: Any?, id: Int, requester: FocusRequester, pageDefault: Boolean) {
        if (isAttached) unregister()
        this.shell = shell
        this.page = page
        this.id = id
        this.requester = requester
        this.pageDefault = pageDefault
        if (isAttached) register()
    }

    private fun register() {
        shell.register(page, id, requester)
        if (pageDefault) shell.registerDefault(page, requester)
    }

    private fun unregister() {
        shell.unregister(page, id, requester)
        if (pageDefault) shell.unregisterDefault(page, requester)
    }
}

/**
 * A group (e.g. a row of chips) that the selection enters at the element whose [tvFocus] key is
 * [key] (when it is on screen), instead of whichever element happens to be nearest.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvEnterAt(key: () -> Any?): Modifier = composed {
    val shell = LocalTvShell.current
    val page = LocalTvPage.current
    focusProperties {
        enter = { key()?.let { k -> shell?.element(page, k.hashCode()) } ?: FocusRequester.Default }
    }.focusGroup()
}

/**
 * For a control that uses Left/Right itself while selected (the seek bar): the shell stops moving
 * the selection on those keys. Place before the focusable.
 */
fun Modifier.tvClaimHorizontalKeys(): Modifier = composed {
    val shell = LocalTvShell.current
    onFocusChanged { shell?.horizontalClaim = it.isFocused }
}

/**
 * For horizontal lists (filter chips): Left/Right never leave the row, so at the first chip Left
 * opens the side menu instead of jumping diagonally into the list below.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvRow(): Modifier = focusProperties {
    exit = { direction ->
        if (direction == FocusDirection.Left || direction == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default
    }
}
