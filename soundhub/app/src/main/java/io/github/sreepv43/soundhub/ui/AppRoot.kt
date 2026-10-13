package io.github.sreepv43.soundhub.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sreepv43.soundhub.container
import io.github.sreepv43.soundhub.ui.components.ActionButton
import io.github.sreepv43.soundhub.ui.components.AlbumCover
import io.github.sreepv43.soundhub.ui.components.DialogButton
import io.github.sreepv43.soundhub.ui.components.IconAction
import io.github.sreepv43.soundhub.ui.components.ListRow
import io.github.sreepv43.soundhub.ui.components.Note
import io.github.sreepv43.soundhub.ui.components.TvDialog
import io.github.sreepv43.soundhub.ui.components.TwoLines
import io.github.sreepv43.soundhub.ui.screens.AlbumScreen
import io.github.sreepv43.soundhub.ui.screens.ArtistsScreen
import io.github.sreepv43.soundhub.ui.screens.ArtistScreen
import io.github.sreepv43.soundhub.ui.screens.HomeScreen
import io.github.sreepv43.soundhub.ui.screens.LibraryScreen
import io.github.sreepv43.soundhub.ui.screens.NowPlayingScreen
import io.github.sreepv43.soundhub.ui.screens.PlayerPanel
import io.github.sreepv43.soundhub.ui.screens.PlaylistScreen
import io.github.sreepv43.soundhub.ui.screens.QueueScreen
import io.github.sreepv43.soundhub.ui.screens.ReleaseScreen
import io.github.sreepv43.soundhub.ui.screens.SearchScreen
import io.github.sreepv43.soundhub.ui.screens.SettingsDetailScreen
import io.github.sreepv43.soundhub.ui.screens.SettingsScreen
import io.github.sreepv43.soundhub.ui.screens.SoundScreen
import io.github.sreepv43.soundhub.ui.screens.TransfersScreen
import kotlinx.coroutines.delay

/**
 * The app: the TV shell (side menu on Left, Back to the previous page) around the page on top.
 * While something is loaded, the player panel (Up Next and the song playing) is beside every page
 * on wide screens, and the player bar under it on narrow ones. Playing something opens Now
 * playing; Back returns to where it was started from.
 */
@Composable
fun AppRoot(sectionRequest: Section?, onSectionRequestHandled: () -> Unit) {
    val container = LocalContext.current.container
    val navigator = remember { Navigator(Section.HOME) }
    LaunchedEffect(sectionRequest) {
        if (sectionRequest != null) {
            if (sectionRequest == Section.NOW_PLAYING) navigator.open(SectionPage(sectionRequest)) else navigator.select(sectionRequest)
            onSectionRequestHandled()
        }
    }
    val showPlayer = { navigator.open(SectionPage(Section.NOW_PLAYING)) }
    val go = { section: Section -> navigator.select(section) }
    val signIn = { navigator.open(SettingsPage(SettingsKind.ACCOUNT)) }
    val openAlbum = { key: String -> navigator.open(AlbumPage(key)) }
    val openArtist = { name: String -> navigator.open(ArtistPage(name)) }

    val loaded by container.playback.current.collectAsStateWithLifecycle()
    val panel: @Composable () -> Unit = { PlayerPanel(onOpen = showPlayer, onViewQueue = { navigator.open(QueuePage) }) }
    val simple = currentLayout == UiLayout.SIMPLE

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= WIDE_LAYOUT
            val onNowPlaying = navigator.current == SectionPage(Section.NOW_PLAYING)
            // Panel layout: the panel is beside every page on wide screens (empty until something
            // plays, so the page never resizes when playback starts). Simple layout, and narrow
            // screens: one player bar along the bottom.
            val beside = wide && !simple
            SoundHubShell(
                navigator,
                bottomBar = {
                    if (!beside && !onNowPlaying) {
                        PlayerBar(onOpen = showPlayer, onQueue = { navigator.open(QueuePage) }, labelled = simple && wide, always = simple)
                    }
                },
                sidePanel = if (beside && !onNowPlaying) panel else null,
                sidePanelFocusable = loaded != null,
            ) { page ->
                when (page) {
                    is SectionPage -> when (page.section) {
                        Section.HOME -> HomeScreen(
                            onOpenAlbum = openAlbum,
                            onGo = go,
                            onSignIn = signIn,
                            onPlaying = showPlayer,
                            onUpdate = { navigator.open(SettingsPage(SettingsKind.UPDATES)) },
                        )
                        Section.SEARCH -> SearchScreen(onOpen = { navigator.open(ReleasePage(it, Section.SEARCH)) }, onSignIn = signIn)
                        Section.ARTISTS -> ArtistsScreen(onSearching = { navigator.bringToTop(it) })
                        Section.LIBRARY -> LibraryScreen(
                            onOpenAlbum = openAlbum,
                            onOpenArtist = openArtist,
                            onOpenPlaylist = { navigator.open(PlaylistPage(it)) },
                            onSearch = { go(Section.SEARCH) },
                            onPlaying = showPlayer,
                        )
                        Section.TRANSFERS -> TransfersScreen(onSearch = { go(Section.SEARCH) })
                        Section.SOUND -> SoundScreen(
                            onOpenAlbum = openAlbum,
                            onFindAtmos = {
                                container.setSearchAtmos(true)
                                navigator.select(Section.SEARCH)
                            },
                        )
                        Section.NOW_PLAYING -> NowPlayingScreen(onQueue = { navigator.open(QueuePage) }, onGo = go)
                        Section.SETTINGS -> SettingsScreen(
                            onOpen = { navigator.open(SettingsPage(it)) },
                            onSound = { go(Section.SOUND) },
                        )
                    }
                    is ReleasePage -> ReleaseScreen(page.release, onPlaying = showPlayer)
                    is AlbumPage -> AlbumScreen(page.albumKey, onPlaying = showPlayer, onGone = { navigator.back() }, onOpenArtist = openArtist)
                    is ArtistPage -> ArtistScreen(page.name, onOpenAlbum = openAlbum, onPlaying = showPlayer)
                    is PlaylistPage -> PlaylistScreen(page.id, onPlaying = showPlayer, onGone = { navigator.back() })
                    QueuePage -> QueueScreen()
                    is SettingsPage -> SettingsDetailScreen(page.kind)
                }
            }
        }
        NotificationPrompt()
    }
}

/** From this width the player panel sits beside the page (TVs, tablets held sideways). */
private val WIDE_LAYOUT = 840.dp

/**
 * What is loaded, under every page on narrow screens: OK on the song opens Now playing;
 * play/pause, next and the queue are one press away. Down from the end of a page reaches it; Up
 * goes back into the page.
 */
@Composable
private fun PlayerBar(onOpen: () -> Unit, onQueue: () -> Unit, labelled: Boolean, always: Boolean) {
    val playback = LocalContext.current.container.playback
    val item by playback.current.collectAsStateWithLifecycle()
    val playing by playback.isPlaying.collectAsStateWithLifecycle()
    val queue by playback.queue.collectAsStateWithLifecycle()
    val current = item
    if (current == null) {
        // The Simple layout keeps its bar when nothing plays, so the page doesn't resize when something starts.
        if (always) IdleBar()
        return
    }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(current.id) {
        while (true) {
            val duration = playback.player.duration
            progress = if (duration > 0) (playback.player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(1_000)
        }
    }
    PlayerBarLayout(
        title = current.title,
        subtitle = listOfNotNull(current.artist, current.album).joinToString(" · "),
        playing = playing,
        progress = progress,
        onOpen = onOpen,
        onPlayPause = playback::togglePlay,
        onNext = playback::next,
        onQueue = onQueue,
        labelled = labelled,
        queueCount = queue.size,
    ) { AlbumCover(current.albumKey, current.album, if (labelled) 52.dp else 40.dp) }
}

@Composable
private fun IdleBar() {
    Row(
        Modifier.fillMaxWidth().padding(top = ShellGap).card().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        TwoLines("Nothing playing", "Choose a song and it appears here")
    }
}

@Composable
fun PlayerBarLayout(
    title: String,
    subtitle: String,
    playing: Boolean,
    progress: Float,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    labelled: Boolean = false,
    queueCount: Int = 0,
    art: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = ShellGap).card()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Accent))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListRow(onClick = onOpen, key = "player-bar", modifier = Modifier.weight(1f).testTag("player-bar")) {
                art()
                TwoLines(title, subtitle, Modifier.weight(1f))
            }
            IconAction(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                key = "bar-play",
                tag = "bar-play",
                onClick = onPlayPause,
            )
            IconAction(Icons.Default.SkipNext, "Next", key = "bar-next", tag = "bar-next", onClick = onNext)
            if (labelled) {
                ActionButton("Now playing", primary = false, onClick = onOpen)
                ActionButton("Queue · $queueCount", Icons.AutoMirrored.Filled.QueueMusic, primary = false, onClick = onQueue)
            } else {
                IconAction(Icons.AutoMirrored.Filled.QueueMusic, "Queue", key = "bar-queue", tag = "bar-queue", onClick = onQueue)
            }
        }
    }
}

/**
 * Asked the first time something plays or downloads (not at startup), with the reason first:
 * Android shows playback controls and download progress as notifications.
 */
@Composable
private fun NotificationPrompt() {
    val container = LocalContext.current.container
    val request by container.notificationRequest.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (!request) return
    fun done() {
        container.settings.notificationsAsked.set(true)
        container.notificationRequest.value = false
    }
    TvDialog(
        title = "Show playback and download notifications?",
        onDismiss = ::done,
        buttons = listOf(
            DialogButton("Continue", primary = true) {
                done()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            DialogButton("Not now", onClick = ::done),
        ),
    ) {
        Note(
            "While music plays or songs download, SoundHub keeps a notification with play/pause and progress, " +
                "so playback carries on when you leave the app. Android asks you to allow it next. You can change " +
                "this later under Settings → Notifications.",
        )
    }
}
