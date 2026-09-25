package io.github.sreepv43.streamhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sreepv43.streamhub.AppContainer
import io.github.sreepv43.streamhub.StreamHubApp
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.data.WatchEntry

/** Creates a ViewModel scoped to the current navigation entry with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StreamHubApp
            create(app.container, createSavedStateHandle())
        }
    },
)

@Composable
fun rememberHistory(): State<List<WatchEntry>> =
    LocalContext.current.container.history.entries.collectAsStateWithLifecycle()
