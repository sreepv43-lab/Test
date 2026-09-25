package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sreepv43.streamhub.addon.AddonRepository
import io.github.sreepv43.streamhub.addon.CatalogRef
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.components.CenteredMessage
import io.github.sreepv43.streamhub.ui.components.MetaRow
import io.github.sreepv43.streamhub.ui.components.RowState
import io.github.sreepv43.streamhub.ui.components.GlassButton
import io.github.sreepv43.streamhub.ui.components.glassTextFieldColors
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val rows: List<Pair<CatalogRef, RowState>> = emptyList(),
)

class SearchViewModel(private val repository: AddonRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var jobs = emptyList<Job>()

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        jobs.forEach { it.cancel() }
        val refs = repository.searchableCatalogs()
        _state.value = SearchState(q, refs.map { it to RowState.Loading })
        jobs = refs.map { ref ->
            viewModelScope.launch {
                val result = runCatching { repository.catalog(ref, listOf("search" to q)) }
                    .fold({ RowState.Loaded(it) }, { RowState.Failed(it.message ?: "Search failed") })
                _state.update { s -> s.copy(rows = s.rows.map { if (it.first.key == ref.key) ref to result else it }) }
            }
        }
    }
}

@Composable
fun SearchScreen(onOpenMeta: (Meta) -> Unit) {
    val vm = appViewModel { c, _ -> SearchViewModel(c.addons) }
    val state by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        vm.search(text)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassTextFieldColors(),
                value = text,
                onValueChange = { text = it },
                label = { Text("Search movies & series") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).tvFocus(),
            )
            GlassButton(
                text = "Search",
                icon = Icons.Default.Search,
                onClick = submit,
                prominent = true,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        when {
            state.query.isEmpty() -> CenteredMessage("Search across all installed addons")
            state.rows.isEmpty() -> CenteredMessage("None of your addons support search")
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.rows, key = { it.first.key }) { (ref, rowState) ->
                    MetaRow(title = "${ref.title} · ${ref.addon.manifest.name}", state = rowState, onMetaClick = onOpenMeta)
                }
            }
        }
    }
}
