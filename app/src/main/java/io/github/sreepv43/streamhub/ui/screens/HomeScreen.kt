package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import io.github.sreepv43.streamhub.ui.components.FlatButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
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
import io.github.sreepv43.streamhub.ui.components.PosterRow
import io.github.sreepv43.streamhub.ui.components.alignRowOnFocus
import io.github.sreepv43.streamhub.ui.TvScrolling
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
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

@OptIn(ExperimentalFoundationApi::class)
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
    val listState = rememberLazyListState()
    val continueWatching = history.filterNot { it.isFinished }
    val firstRowIndex = if (continueWatching.isNotEmpty()) 2 else 1
    // Rows position themselves (alignRowOnFocus): one row per Up/Down, always the same distance.
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScrolling.None) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp)) {
            item(key = "title") {
                Text(
                    "StreamHub",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    Column(Modifier.alignRowOnFocus(listState, 1, first = true).padding(vertical = 10.dp)) {
                        Text(
                            "Continue watching",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        PosterRow {
                            items(continueWatching, key = { it.metaId }) { entry ->
                                PosterCard(
                                    title = entry.name,
                                    image = entry.poster,
                                    caption = entry.videoTitle,
                                    progress = entry.progress,
                                    onClick = { onOpenHistory(entry) },
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
                        FlatButton(
                            text = "Manage addons",
                            onClick = onOpenAddons,
                            prominent = true,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.first.key }) { i, (ref, rowState) ->
                val index = firstRowIndex + i
                MetaRow(
                    title = ref.title,
                    state = rowState,
                    onMetaClick = onOpenMeta,
                    modifier = Modifier.alignRowOnFocus(listState, index, first = index == 1),
                    onSeeAll = { onSeeAll(ref) },
                )
            }
        }
    }
}
