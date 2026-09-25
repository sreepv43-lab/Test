package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import io.github.sreepv43.streamhub.ui.EyebrowStyle
import io.github.sreepv43.streamhub.ui.components.GlassButton
import io.github.sreepv43.streamhub.ui.components.LocalAmbient
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.CatalogRef
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.data.WatchEntry
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.rememberHistory
import io.github.sreepv43.streamhub.ui.components.CenteredLoading
import io.github.sreepv43.streamhub.ui.components.MetaRow
import io.github.sreepv43.streamhub.ui.components.PosterCard
import io.github.sreepv43.streamhub.ui.components.RowState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeState(
    val initializing: Boolean = true,
    val rows: List<Pair<CatalogRef, RowState>> = emptyList(),
)

class HomeViewModel(private val repository: AddonRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.installDefaults()
            repository.addons.collectLatest { load() }
        }
    }

    private suspend fun load() = coroutineScope {
        val refs = repository.boardCatalogs()
        _state.value = HomeState(initializing = false, rows = refs.map { it to RowState.Loading })
        val limit = Semaphore(4)
        refs.forEach { ref ->
            launch {
                val result = limit.withPermit {
                    runCatching { repository.catalog(ref) }
                        .fold({ RowState.Loaded(it) }, { RowState.Failed(it.message ?: "Failed to load") })
                }
                _state.update { s -> s.copy(rows = s.rows.map { if (it.first.key == ref.key) ref to result else it }) }
            }
        }
    }
}

@Composable
fun HomeScreen(
    onOpenMeta: (Meta) -> Unit,
    onOpenHistory: (WatchEntry) -> Unit,
    onSeeAll: (CatalogRef) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val vm = appViewModel { c, _ -> HomeViewModel(c.addons) }
    val state by vm.state.collectAsStateWithLifecycle()
    val history by rememberHistory()

    if (state.initializing) {
        CenteredLoading()
        return
    }
    // The focused title drives the hero text and the blurred ambient artwork behind the screen.
    var focused by remember { mutableStateOf<Meta?>(null) }
    val ambient = LocalAmbient.current
    LaunchedEffect(focused) {
        val meta = focused ?: return@LaunchedEffect
        delay(250) // don't swap artwork while the user is scrolling quickly
        ambient.image = meta.background ?: meta.poster
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)) {
        item(key = "hero") { HomeHero(focused) }
        val continueWatching = history.filterNot { it.isFinished }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(
                        "Continue watching",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(continueWatching, key = { it.metaId }) { entry ->
                            PosterCard(
                                title = entry.name,
                                image = entry.poster,
                                caption = entry.videoTitle,
                                progress = entry.progress,
                                onClick = { onOpenHistory(entry) },
                                onFocused = { entry.poster?.let { ambient.image = it } },
                            )
                        }
                    }
                }
            }
        }
        if (state.rows.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No catalogs yet. Install addons to start browsing.")
                    GlassButton(
                        text = "Manage addons",
                        onClick = onOpenAddons,
                        prominent = true,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
        items(state.rows, key = { it.first.key }) { (ref, rowState) ->
            MetaRow(
                title = ref.title,
                state = rowState,
                onMetaClick = onOpenMeta,
                onSeeAll = { onSeeAll(ref) },
                onMetaFocused = { focused = it },
            )
        }
    }
}

/** Large, quiet header describing whatever poster has focus. */
@Composable
private fun HomeHero(meta: Meta?) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text("STREAMHUB", style = EyebrowStyle, color = Color.White.copy(alpha = 0.55f))
        Text(
            meta?.name ?: "Tonight's picks",
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val info = listOfNotNull(meta?.releaseInfo, meta?.imdbRating?.let { "★ $it" }, meta?.genres?.take(3)?.joinToString(" · ")?.ifEmpty { null })
            .joinToString("   ")
        if (info.isNotEmpty()) {
            Text(info, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
        }
        meta?.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.6f),
            )
        }
    }
}
