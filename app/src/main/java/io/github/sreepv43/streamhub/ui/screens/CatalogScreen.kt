package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.components.CenteredMessage
import io.github.sreepv43.streamhub.ui.components.MetaCard
import io.github.sreepv43.streamhub.ui.components.PosterWidth
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogState(
    val title: String = "",
    val genres: List<String> = emptyList(),
    val genre: String? = null,
    val items: List<Meta> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

/** A full catalog with paging (Stremio "skip" extra) and genre filtering. */
class CatalogViewModel(private val repository: AddonRepository, handle: SavedStateHandle) : ViewModel() {
    private val ref = repository.findCatalog(
        checkNotNull(handle["addon"]),
        checkNotNull(handle["type"]),
        checkNotNull(handle["id"]),
    )
    private val _state = MutableStateFlow(
        CatalogState(
            title = ref?.let { "${it.title} · ${it.addon.manifest.name}" }.orEmpty(),
            genres = ref?.catalog?.options("genre").orEmpty(),
            error = if (ref == null) "This catalog is no longer installed" else null,
            endReached = ref == null,
        )
    )
    val state: StateFlow<CatalogState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        loadMore()
    }

    fun selectGenre(genre: String?) {
        job?.cancel()
        _state.update { it.copy(genre = genre, items = emptyList(), endReached = false, loading = false, error = null) }
        loadMore()
    }

    fun loadMore() {
        val ref = ref ?: return
        val current = _state.value
        if (current.loading || current.endReached) return
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val extra = buildList {
                current.genre?.let { add("genre" to it) }
                if (current.items.isNotEmpty()) {
                    if (!ref.catalog.supportsExtra("skip")) {
                        _state.update { it.copy(loading = false, endReached = true) }
                        return@launch
                    }
                    add("skip" to current.items.size.toString())
                }
            }
            runCatching { repository.catalog(ref, extra) }
                .onSuccess { page ->
                    _state.update { s ->
                        val merged = (s.items + page).distinctBy { it.type + it.id }
                        s.copy(items = merged, loading = false, endReached = merged.size == s.items.size)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message, endReached = true) } }
        }
    }
}

@Composable
fun CatalogScreen(onOpenMeta: (Meta) -> Unit) {
    val vm = appViewModel { c, handle -> CatalogViewModel(c.addons, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.items.size) {
        if (nearEnd && state.items.isNotEmpty()) vm.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        if (state.genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = state.genre == null, onClick = { vm.selectGenre(null) }, label = { Text("All") }, modifier = Modifier.tvFocus())
                }
                items(state.genres) { genre ->
                    FilterChip(
                        selected = state.genre == genre,
                        onClick = { vm.selectGenre(genre) },
                        label = { Text(genre) },
                        modifier = Modifier.tvFocus(),
                    )
                }
            }
        }
        if (state.items.isEmpty() && !state.loading) {
            CenteredMessage(state.error ?: "Nothing found")
            return@Column
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(PosterWidth + 16.dp),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.type + it.id }) { meta ->
                MetaCard(meta, onClick = { onOpenMeta(meta) })
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
