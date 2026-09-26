package io.github.sreepv43.streamhub.ui.screens

import io.github.sreepv43.streamhub.ui.components.rememberPosterMenu
import io.github.sreepv43.streamhub.ui.components.PosterTarget
import io.github.sreepv43.streamhub.ui.components.PosterMenu
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import io.github.sreepv43.streamhub.data.HomeCache
import androidx.compose.ui.platform.LocalContext
import io.github.sreepv43.streamhub.container
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
import io.github.sreepv43.streamhub.ui.components.LocalFocusKeyScope
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

class HomeViewModel(private val repository: AddonRepository, private val cache: HomeCache) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var cached: Map<String, List<Meta>>? = null

    init {
        viewModelScope.launch {
            // The very first start needs the default addons before there is anything to show;
            // later, defaults added by an update are installed in the background.
            if (repository.isFirstRun) repository.installDefaults() else launch { repository.installDefaults() }
            repository.addons.collectLatest { load() }
        }
    }

    private suspend fun load() = coroutineScope {
        val refs = repository.boardCatalogs()
        // Rows show what they had (or what the last start showed) until they are reloaded.
        val saved = cached ?: withContext(Dispatchers.IO) { cache.read() }.also { cached = it }
        val shown = _state.value.rows.associate { (ref, row) -> ref.key to row }
        fun previous(key: String): RowState? =
            shown[key]?.takeIf { it is RowState.Loaded } ?: saved[key]?.let { RowState.Loaded(it) }
        _state.value = HomeState(initializing = false, rows = refs.map { it to (previous(it.key) ?: RowState.Loading) })
        val limit = Semaphore(4)
        refs.map { ref ->
            launch {
                val result = limit.withPermit {
                    runCatching { repository.catalog(ref) }
                        .fold({ RowState.Loaded(it) }, { RowState.Failed(it.message ?: "Failed to load") })
                }
                ensureActive() // replaced by a newer load: don't touch its rows
                _state.update { s ->
                    s.copy(rows = s.rows.map { row ->
                        if (row.first.key != ref.key) return@map row
                        val old = row.second
                        when {
                            // Offline: keep showing the last rows rather than an error.
                            result is RowState.Failed && old is RowState.Loaded -> row
                            // Same titles as shown: nothing to redraw.
                            result is RowState.Loaded && old is RowState.Loaded &&
                                old.metas.map { it.id } == result.metas.map { it.id } -> row
                            else -> ref to result
                        }
                    })
                }
            }
        }.joinAll()
        val loaded = _state.value.rows.mapNotNull { (ref, row) -> (row as? RowState.Loaded)?.let { ref.key to it.metas } }.toMap()
        cached = loaded
        withContext(Dispatchers.IO) { cache.write(loaded) }
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
    val vm = appViewModel { c, _ -> HomeViewModel(c.addons, c.homeCache) }
    val state by vm.state.collectAsStateWithLifecycle()
    val history by rememberHistory()

    if (state.initializing) {
        CenteredLoading()
        return
    }
    val listState = rememberLazyListState()
    val posterMenu = rememberPosterMenu()
    PosterMenu(posterMenu)
    val continueWatching = history.filterNot { it.isFinished }
    val myList by LocalContext.current.container.library.items.collectAsStateWithLifecycle()
    // List positions: the title is item 0, then the optional rows, then the catalogs.
    var nextIndex = 1
    val continueIndex = if (continueWatching.isNotEmpty()) nextIndex++ else -1
    val myListIndex = if (myList.isNotEmpty()) nextIndex++ else -1
    val firstRowIndex = nextIndex
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
                    Column(Modifier.alignRowOnFocus(listState, continueIndex, first = continueIndex == 1).padding(vertical = 10.dp)) {
                        Text(
                            "Continue watching",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        CompositionLocalProvider(LocalFocusKeyScope provides "continue") {
                            PosterRow {
                                items(continueWatching, key = { it.metaId }) { entry ->
                                    PosterCard(
                                        title = entry.name,
                                        image = entry.poster,
                                        caption = entry.videoTitle,
                                        progress = entry.progress,
                                        onClick = { onOpenHistory(entry) },
                                        focusKey = entry.metaId,
                                        onLongClick = { posterMenu.target = PosterTarget.of(entry) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (myList.isNotEmpty()) {
                item(key = "my-list") {
                    Column(Modifier.alignRowOnFocus(listState, myListIndex, first = myListIndex == 1).padding(vertical = 10.dp)) {
                        Text(
                            "My List",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        CompositionLocalProvider(LocalFocusKeyScope provides "my-list") {
                            PosterRow {
                                items(myList, key = { it.id }) { item ->
                                    PosterCard(
                                        title = item.name,
                                        image = item.poster,
                                        onClick = { onOpenMeta(Meta(id = item.id, type = item.type, name = item.name, poster = item.poster)) },
                                        focusKey = item.id,
                                        onLongClick = { posterMenu.target = PosterTarget.of(item) },
                                    )
                                }
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
            itemsIndexed(state.rows, key = { _, row -> row.first.key }, contentType = { _, _ -> "catalog-row" }) { i, (ref, rowState) ->
                val index = firstRowIndex + i
                MetaRow(
                    title = ref.title,
                    state = rowState,
                    onMetaClick = onOpenMeta,
                    modifier = Modifier.alignRowOnFocus(listState, index, first = index == 1),
                    onSeeAll = { onSeeAll(ref) },
                    onMetaLongClick = { posterMenu.target = PosterTarget.of(it) },
                )
            }
        }
    }
}
