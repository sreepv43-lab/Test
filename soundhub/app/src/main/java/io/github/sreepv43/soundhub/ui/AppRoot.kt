package io.github.sreepv43.soundhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.components.tvFocus
import io.github.sreepv43.soundhub.ui.screens.AtmosScreen
import io.github.sreepv43.soundhub.ui.screens.DownloadsScreen
import io.github.sreepv43.soundhub.ui.screens.LibraryScreen
import io.github.sreepv43.soundhub.ui.screens.NowPlayingScreen
import io.github.sreepv43.soundhub.ui.screens.SearchScreen
import io.github.sreepv43.soundhub.ui.screens.SettingsScreen

enum class Section(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Default.Search),
    ATMOS("Atmos", Icons.Default.SurroundSound),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    DOWNLOADS("Downloads", Icons.Default.Download),
    NOW_PLAYING("Playing", Icons.Default.GraphicEq),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** Side navigation rail + content: works with a TV remote (D-pad) and with touch on tablets. */
@Composable
fun AppRoot(sectionRequest: Section?, onSectionRequestHandled: () -> Unit) {
    val container = LocalContext.current.container
    var section by rememberSaveable {
        mutableStateOf(if (container.settings.password.value.isEmpty()) Section.SETTINGS else Section.SEARCH)
    }
    LaunchedEffect(sectionRequest) {
        if (sectionRequest != null) {
            section = sectionRequest
            onSectionRequestHandled()
        }
    }
    val showPlayer = { section = Section.NOW_PLAYING }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.systemBarsPadding()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(16.dp))
                Section.entries.forEach { entry ->
                    NavigationRailItem(
                        selected = section == entry,
                        onClick = { section = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                        modifier = Modifier.tvFocus(),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f)) {
                    when (section) {
                        Section.SEARCH -> SearchScreen(onPlaying = showPlayer)
                        Section.ATMOS -> AtmosScreen(onPlaying = showPlayer)
                        Section.LIBRARY -> LibraryScreen(onPlaying = showPlayer)
                        Section.DOWNLOADS -> DownloadsScreen()
                        Section.NOW_PLAYING -> NowPlayingScreen()
                        Section.SETTINGS -> SettingsScreen()
                    }
                }
                if (section != Section.NOW_PLAYING) MiniPlayer(onOpen = showPlayer)
            }
        }
    }
}

/** The current song at the bottom of every page. */
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val playback = LocalContext.current.container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val current = item ?: return
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { playback.togglePlay() }, modifier = Modifier.tvFocus()) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
        }
        Text(
            listOfNotNull(current.title, current.artist).joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .tvFocus(shape, scale = 1.02f)
                .clip(shape)
                .clickable(onClick = onOpen)
                .padding(12.dp),
        )
    }
}
