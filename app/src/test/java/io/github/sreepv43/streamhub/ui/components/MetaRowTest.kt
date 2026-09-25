package io.github.sreepv43.streamhub.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.ui.StreamHubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h720dp-land-television-mdpi")
class MetaRowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rowKeepsItsHeightWhileLoadingFailingOrEmpty() {
        val states = mapOf(
            "loaded" to RowState.Loaded(List(3) { Meta(id = "m$it", name = "Movie $it", releaseInfo = if (it == 0) "2024" else null) }),
            "loading" to RowState.Loading,
            "failed" to RowState.Failed("Addon is offline"),
            "empty" to RowState.Loaded(emptyList()),
        )
        rule.setContent {
            StreamHubTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    states.forEach { (tag, state) ->
                        Box(Modifier.testTag(tag)) { MetaRow(title = tag, state = state, onMetaClick = {}, onSeeAll = {}) }
                    }
                }
            }
        }
        val height = { tag: String -> rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top } }
        val loaded = height("loaded")
        for (tag in listOf("loading", "failed", "empty")) assertEquals("$tag vs loaded", loaded, height(tag))
    }
}
