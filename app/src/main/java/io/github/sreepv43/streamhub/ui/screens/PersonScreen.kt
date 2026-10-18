package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sreepv43.streamhub.BuildConfig
import io.github.sreepv43.streamhub.addon.Credit
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.addon.PersonCredits
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.TvScrolling
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.components.LocalFocusKeyScope
import io.github.sreepv43.streamhub.ui.components.PosterCard
import io.github.sreepv43.streamhub.ui.components.PosterRow
import io.github.sreepv43.streamhub.ui.components.alignRowOnFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class PersonState(
    val loading: Boolean = true,
    val credits: List<Credit> = emptyList(),
    val error: String? = null,
)

/** Looks up what a cast member or director worked on (see [PersonCredits]). */
class PersonViewModel(private val http: OkHttpClient, handle: SavedStateHandle) : ViewModel() {
    val name: String = checkNotNull(handle["name"])
    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
            _state.value = result.fold(
                { PersonState(loading = false, credits = it) },
                { PersonState(loading = false, error = "Couldn't look up $name (${it.message ?: "no connection"}).") },
            )
        }
    }

    private fun fetch(): List<Credit> {
        val url = PersonCredits.ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("query", PersonCredits.query(name))
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/sparql-results+json")
            // Wikidata asks every client to say who it is.
            .header("User-Agent", "StreamHub/${BuildConfig.VERSION_NAME} (Android TV app; github.com/sreepv43-lab/Test)")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return PersonCredits.parse(response.body?.string().orEmpty())
        }
    }
}

@Composable
fun PersonScreen(onOpenMeta: (Meta) -> Unit) {
    val vm = appViewModel { c, handle -> PersonViewModel(c.http, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    PersonContent(vm.name, state, onOpenMeta)
}

/** A person's page: their films and series as rows, Directed first for directors. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PersonContent(name: String, state: PersonState, onOpenMeta: (Meta) -> Unit) {
    val rows = listOf(
        "Directed" to state.credits.filter { it.role == "director" },
        "Acting" to state.credits.filter { it.role == "actor" },
    ).filter { it.second.isNotEmpty() }
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScrolling.None) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "title") {
                val count = state.credits.distinctBy { it.imdbId }.size
                CinemaPageTitle(
                    "CAST & CREATORS",
                    name,
                    when {
                        state.loading -> "Looking up their films and series…"
                        state.error != null -> state.error
                        count == 0 -> "No films or series found for this name."
                        else -> "$count titles. Select one to find its sources."
                    },
                )
            }
            if (state.loading) {
                item(key = "loading") {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = AppColors.accent)
                    }
                }
            }
            rows.forEachIndexed { i, (title, credits) ->
                item(key = title) {
                    Column(Modifier.alignRowOnFocus(listState, i + 1, first = i == 0).padding(top = 16.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        CompositionLocalProvider(LocalFocusKeyScope provides title) {
                            PosterRow {
                                items(credits, key = { it.imdbId }) { credit ->
                                    PosterCard(
                                        title = credit.title,
                                        image = PersonCredits.posterUrl(credit.imdbId),
                                        caption = listOfNotNull(credit.year?.toString(), "Series".takeIf { credit.type == "series" }).joinToString(" · "),
                                        onClick = { onOpenMeta(Meta(id = credit.imdbId, type = credit.type, name = credit.title)) },
                                        focusKey = credit.imdbId,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.credits.isNotEmpty()) {
                item(key = "note") {
                    Text(
                        "Films and series come from Wikidata, the free database behind Wikipedia, so a few may be missing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textDim,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
