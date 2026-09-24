package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.streamhub.ui.appViewModel
import io.github.sreepv43.streamhub.ui.components.CenteredLoading
import io.github.sreepv43.streamhub.ui.components.StreamDialogs
import io.github.sreepv43.streamhub.ui.components.WatchContext
import io.github.sreepv43.streamhub.ui.components.rememberStreamDialogState
import io.github.sreepv43.streamhub.ui.components.rememberStreamHandlers
import io.github.sreepv43.streamhub.ui.components.streamItems

/** Streams for one episode of a series (or any specific video id). */
@Composable
fun StreamsScreen(onOpenSettings: () -> Unit) {
    val vm = appViewModel { c, handle -> MetaViewModel(c.addons, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val streamsState by vm.streams.state.collectAsStateWithLifecycle()
    val dialogs = rememberStreamDialogState()

    val meta = state.meta
    val video = meta?.videos?.firstOrNull { it.id == vm.videoId }
    val episodeTitle = video?.let { v ->
        listOfNotNull(
            v.season?.let { s -> v.episodeNumber?.let { e -> "S${s}E$e" } },
            v.displayTitle,
        ).joinToString(" · ")
    }
    val watch = {
        meta?.let { WatchContext(it.id, it.type, checkNotNull(vm.videoId), it.name, episodeTitle, it.poster) }
    }
    val handlers = rememberStreamHandlers(dialogs, watch)
    StreamDialogs(dialogs, watch(), onOpenSettings)

    if (state.loading || meta == null) {
        CenteredLoading()
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "header") { MetaHeader(meta, subtitle = episodeTitle) }
        streamItems(streamsState, handlers.onPlay, handlers.onDownload, handlers.onExternal)
    }
}
