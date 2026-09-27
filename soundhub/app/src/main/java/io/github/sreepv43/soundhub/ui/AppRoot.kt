package io.github.sreepv43.soundhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.screens.AlbumScreen
import io.github.sreepv43.soundhub.ui.screens.AtmosScreen
import io.github.sreepv43.soundhub.ui.screens.DownloadsScreen
import io.github.sreepv43.soundhub.ui.screens.FolderScreen
import io.github.sreepv43.soundhub.ui.screens.LibraryScreen
import io.github.sreepv43.soundhub.ui.screens.NowPlayingScreen
import io.github.sreepv43.soundhub.ui.screens.SearchScreen
import io.github.sreepv43.soundhub.ui.screens.SettingsScreen

/**
 * The app: the TV shell (side menu on Left, Back to the previous page) around the page on top.
 * Playing something opens Now playing; Back returns to the album it was started from.
 */
@Composable
fun AppRoot(sectionRequest: Section?, onSectionRequestHandled: () -> Unit) {
    val container = LocalContext.current.container
    val navigator = remember {
        Navigator(if (container.settings.password.value.isEmpty()) Section.SETTINGS else Section.SEARCH)
    }
    LaunchedEffect(sectionRequest) {
        if (sectionRequest != null) {
            if (sectionRequest == Section.NOW_PLAYING) navigator.open(SectionPage(sectionRequest)) else navigator.select(sectionRequest)
            onSectionRequestHandled()
        }
    }
    val showPlayer = { navigator.open(SectionPage(Section.NOW_PLAYING)) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            SoundHubShell(navigator) { page ->
                when (page) {
                    is SectionPage -> when (page.section) {
                        Section.SEARCH -> SearchScreen(onOpen = { navigator.open(FolderPage(it, Section.SEARCH)) })
                        Section.ATMOS -> AtmosScreen(
                            onOpenFolder = { navigator.open(FolderPage(it, Section.ATMOS)) },
                            onOpenAlbum = { navigator.open(AlbumPage(it.key)) },
                        )
                        Section.LIBRARY -> LibraryScreen(onOpenAlbum = { navigator.open(AlbumPage(it.key)) })
                        Section.DOWNLOADS -> DownloadsScreen()
                        Section.NOW_PLAYING -> NowPlayingScreen()
                        Section.SETTINGS -> SettingsScreen()
                    }
                    is FolderPage -> FolderScreen(page.folder, onPlaying = showPlayer)
                    is AlbumPage -> AlbumScreen(page.albumKey, onPlaying = showPlayer, onGone = { navigator.back() })
                }
            }
            if (navigator.current.section != Section.NOW_PLAYING) {
                NowPlayingBadge(onOpen = showPlayer, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/**
 * What is playing, in the corner of every page. Not selectable with the remote (the menu's Now
 * playing and the remote's play/pause keys are), but tappable on a tablet.
 */
@Composable
private fun NowPlayingBadge(onOpen: () -> Unit, modifier: Modifier) {
    val playback = LocalContext.current.container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val current = item ?: return
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .padding(16.dp)
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(AppColors.panel)
            .focusProperties { canFocus = false }
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (playing) Icons.Default.GraphicEq else Icons.Default.Pause,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            listOfNotNull(current.title, current.artist).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
