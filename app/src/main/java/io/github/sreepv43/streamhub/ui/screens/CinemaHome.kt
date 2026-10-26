package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.sreepv43.streamhub.ui.components.FlatChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.addon.CatalogRef
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.data.LibraryItem
import io.github.sreepv43.streamhub.data.WatchEntry
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.TvScrolling
import io.github.sreepv43.streamhub.ui.ScrollToStartOnRestart
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.DialogShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.sreepv43.streamhub.ui.components.LocalFocusKeyScope
import io.github.sreepv43.streamhub.ui.components.MetaRow
import io.github.sreepv43.streamhub.ui.components.PosterCard
import io.github.sreepv43.streamhub.ui.components.downloadBadge
import io.github.sreepv43.streamhub.ui.components.PosterMenu
import io.github.sreepv43.streamhub.ui.components.PosterRow
import io.github.sreepv43.streamhub.ui.components.PosterTarget
import io.github.sreepv43.streamhub.ui.components.RowState
import io.github.sreepv43.streamhub.ui.components.alignRowOnFocus
import io.github.sreepv43.streamhub.ui.components.panel
import io.github.sreepv43.streamhub.ui.components.rememberPosterMenu
import io.github.sreepv43.streamhub.ui.components.tvFocus
import io.github.sreepv43.streamhub.ui.components.tvRow
import io.github.sreepv43.streamhub.ui.rememberHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import io.github.sreepv43.streamhub.ui.components.ContinueWatchingEditor
import io.github.sreepv43.streamhub.ui.components.rememberRatings
import io.github.sreepv43.streamhub.ui.components.ratingFacts

/**
 * Home in the Cinema interface: a large banner for the film to continue (or the first one of the
 * catalogues), wide Continue watching cards, then My List and the addons' catalogue rows.
 * Same data and remote behaviour as the classic Home (one row per Up/Down).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CinemaHome(
    state: HomeState,
    onOpenMeta: (Meta) -> Unit,
    onOpenHistory: (WatchEntry) -> Unit,
    onSeeAll: (CatalogRef) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val container = LocalContext.current.container
    val history by rememberHistory()
    val myList by container.library.items.collectAsStateWithLifecycle()
    val continueWatching = history.filter { it.inContinueWatching }
    val loaded = remember(state.rows) {
        state.rows.flatMap { (it.second as? RowState.Loaded)?.metas.orEmpty() }.associateBy { it.id }
    }
    val resume = continueWatching.firstOrNull()
    // The catalogues usually already have the banner's picture and description; otherwise ask the addons.
    val fetched by produceState<Meta?>(null, resume?.metaId, resume != null && resume.metaId !in loaded) {
        val entry = resume ?: return@produceState
        if (entry.metaId in loaded) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { container.addons.meta(entry.type, entry.metaId) }.getOrNull() }
    }
    val hero = when {
        resume != null -> loaded[resume.metaId] ?: fetched?.takeIf { it.id == resume.metaId }
            ?: Meta(id = resume.metaId, type = resume.type, name = resume.name, poster = resume.poster)
        else -> loaded.values.firstOrNull { it.background != null } ?: loaded.values.firstOrNull()
    }

    val listState = rememberLazyListState()
    ScrollToStartOnRestart(listState)
    val posterMenu = rememberPosterMenu()
    PosterMenu(posterMenu)
    var editingContinue by remember { mutableStateOf(false) }
    if (editingContinue) ContinueWatchingEditor(onClose = { editingContinue = false })
    // List positions: the banner is item 0, then the optional rows, then the catalogues.
    var nextIndex = 1
    val continueIndex = if (continueWatching.isNotEmpty()) nextIndex++ else -1
    val myListIndex = if (myList.isNotEmpty()) nextIndex++ else -1
    val headingIndex = nextIndex++
    val firstRowIndex = nextIndex
    // "All addons" or one addon's rows only.
    var addonFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val addonNames = state.rows.map { it.first.addon }.distinctBy { it.transportUrl }
    val rows = state.rows.filter { addonFilter == null || it.first.addon.transportUrl == addonFilter }
    var customizing by remember { mutableStateOf(false) }
    if (customizing) CustomizeHome(onClose = { customizing = false })
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScrolling.None) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp)) {
            item(key = "hero") {
                Column(Modifier.alignRowOnFocus(listState, 0, first = true)) {
                    Wordmark()
                    if (hero != null) {
                        Hero(
                            meta = hero,
                            resume = resume?.takeIf { it.metaId == hero.id },
                            inMyList = myList.any { it.id == hero.id },
                            onResume = onOpenHistory,
                            onDetails = { onOpenMeta(hero) },
                            onToggleMyList = {
                                if (container.library.contains(hero.id)) container.library.remove(hero.id)
                                else container.library.add(LibraryItem(hero.id, hero.type, hero.name, hero.poster))
                            },
                        )
                    }
                }
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    Column(Modifier.alignRowOnFocus(listState, continueIndex).padding(top = 14.dp)) {
                        SectionTitle("Continue watching")
                        CompositionLocalProvider(LocalFocusKeyScope provides "continue") {
                            PosterRow {
                                items(continueWatching, key = { it.metaId }) { entry ->
                                    val meta = loaded[entry.metaId]
                                    ContinueCard(
                                        entry = entry,
                                        image = meta?.background ?: entry.poster,
                                        onClick = { onOpenHistory(entry) },
                                        onLongClick = { posterMenu.target = PosterTarget.of(entry) },
                                    )
                                }
                                item(key = "edit-continue") { EditListCard { editingContinue = true } }
                            }
                        }
                    }
                }
            }
            if (myList.isNotEmpty()) {
                item(key = "my-list") {
                    Column(Modifier.alignRowOnFocus(listState, myListIndex).padding(top = 10.dp)) {
                        SectionTitle("My List")
                        CompositionLocalProvider(LocalFocusKeyScope provides "my-list") {
                            PosterRow {
                                items(myList, key = { it.id }) { item ->
                                    PosterCard(
                                        title = item.name,
                                        image = item.poster,
                                        onClick = { onOpenMeta(Meta(id = item.id, type = item.type, name = item.name, poster = item.poster)) },
                                        focusKey = item.id,
                                        onLongClick = { posterMenu.target = PosterTarget.of(item) },
                                        badge = downloadBadge(item.id, item.name, null),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "catalogues-heading") {
                Column(Modifier.alignRowOnFocus(listState, headingIndex).padding(top = 18.dp)) {
                    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your catalogues", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("Collections from your addons.", style = MaterialTheme.typography.bodySmall, color = AppColors.textDim)
                        }
                    }
                    Row(
                        Modifier.tvRow().padding(start = 24.dp, end = 24.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (addonNames.size > 1) {
                            FlatChip("All addons", selected = addonFilter == null, onClick = { addonFilter = null })
                            addonNames.forEach { addon ->
                                FlatChip(addon.manifest.name, selected = addonFilter == addon.transportUrl, onClick = { addonFilter = addon.transportUrl })
                            }
                        }
                        FlatButton("Customize Home", onClick = { customizing = true }, icon = Icons.Default.Tune, compact = true)
                    }
                }
            }
            if (state.rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No catalogues yet. Install addons to start browsing.")
                        FlatButton(
                            text = "Manage addons",
                            onClick = onOpenAddons,
                            prominent = true,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> row.first.key }, contentType = { _, _ -> "catalog-row" }) { i, (ref, rowState) ->
                val count = (rowState as? RowState.Loaded)?.metas?.size
                MetaRow(
                    title = ref.catalog.name ?: ref.catalog.id,
                    subtitle = listOfNotNull(
                        ref.addon.manifest.name,
                        ref.catalog.type.replaceFirstChar { it.uppercase() },
                        count?.let { if (it == 1) "1 title" else "$it titles" },
                    ).joinToString(" · "),
                    focusScope = ref.key,
                    state = rowState,
                    onMetaClick = onOpenMeta,
                    modifier = Modifier.alignRowOnFocus(listState, firstRowIndex + i),
                    onSeeAll = { onSeeAll(ref) },
                    onMetaLongClick = { posterMenu.target = PosterTarget.of(it) },
                )
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Text("stream", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.text)
        Text("hub", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.accent)
        Text("TV", fontSize = 10.sp, letterSpacing = 2.sp, color = AppColors.textDim, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun Hero(
    meta: Meta,
    resume: WatchEntry?,
    inMyList: Boolean,
    onResume: (WatchEntry) -> Unit,
    onDetails: () -> Unit,
    onToggleMyList: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val background = AppColors.background
    Box(
        Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(HeroHeight)
            .clip(shape)
            .panel(shape),
    ) {
        val image = meta.background ?: meta.poster
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Keeps the text readable over any picture.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to background, 0.4f to background.copy(alpha = 0.82f), 0.75f to Color.Transparent)),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.62f)
                .padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (resume != null) "CONTINUE WATCHING" else "YOUR NEXT GREAT STORY",
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.accent,
            )
            Text(
                meta.name,
                fontFamily = FontFamily.Serif,
                fontSize = 44.sp,
                lineHeight = 48.sp,
                color = AppColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(meta.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" }) +
                ratingFacts(rememberRatings(meta.id)).take(2) + listOfNotNull(
                meta.releaseInfo?.takeIf { it.isNotBlank() },
                meta.genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(" / "),
                meta.runtime?.takeIf { it.isNotBlank() },
            )
            if (facts.isNotEmpty()) {
                Text(facts.joinToString("   "), style = MaterialTheme.typography.bodyMedium, color = AppColors.text)
            }
            meta.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.tvRow().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resume != null) {
                    FlatButton("Resume", onClick = { onResume(resume) }, icon = Icons.Default.PlayArrow, prominent = true)
                }
                FlatButton(
                    if (meta.type == "series") "Show details" else "Movie details",
                    onClick = onDetails,
                    icon = Icons.Default.Info,
                    prominent = resume == null,
                )
                FlatButton(
                    if (inMyList) "In My List" else "My List",
                    onClick = onToggleMyList,
                    icon = if (inMyList) Icons.Default.Check else Icons.Default.Add,
                )
            }
            resume?.let { entry ->
                Text(
                    listOfNotNull(entry.videoTitle, timeLeft(entry)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.textDim,
                )
            }
        }
    }
}

/** A wide card: picture on the left, title, time left and progress on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: WatchEntry, image: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .width(340.dp)
            .height(108.dp)
            .tvFocus(shape, scale = 1.04f, key = entry.metaId)
            .clip(shape)
            .panel(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.width(150.dp).fillMaxHeight().background(AppColors.panelRaised)) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.videoTitle, timeLeft(entry)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { entry.progress },
                color = AppColors.accent,
                trackColor = AppColors.panelRaised,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** End of the Continue watching row: opens the list with a Remove button per title. */
@Composable
private fun EditListCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(150.dp)
            .height(108.dp)
            .tvFocus(shape, scale = 1.04f, key = "edit-continue")
            .clip(shape)
            .panel(shape)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Edit, contentDescription = null, tint = AppColors.accent)
        Text("Edit list", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun timeLeft(entry: WatchEntry): String? {
    if (entry.durationMs <= 0) return null
    val minutes = ((entry.durationMs - entry.positionMs) / 60_000).coerceAtLeast(1)
    return "$minutes min left"
}

private val HeroHeight = 340.dp

/** Turns each catalogue row of Home on or off (hidden rows are kept in Settings, for both interfaces). */
@Composable
private fun CustomizeHome(onClose: () -> Unit) {
    val container = LocalContext.current.container
    val hiddenText by container.settings.hiddenHomeRows.flow.collectAsStateWithLifecycle()
    val hidden = hiddenText.lines().filter { it.isNotBlank() }.toSet()
    val catalogs = remember { container.addons.boardCatalogs() }
    fun toggle(key: String) {
        val next = if (key in hidden) hidden - key else hidden + key
        container.settings.hiddenHomeRows.set(next.joinToString("\n"))
    }
    AlertDialog(
        containerColor = AppColors.panel,
        shape = DialogShape,
        onDismissRequest = onClose,
        title = { Text("Customize Home") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose which catalogue rows appear on Home.", color = AppColors.textDim)
                catalogs.forEach { ref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(ref.catalog.name ?: ref.catalog.id, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                ref.addon.manifest.name + " · " + ref.catalog.type.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.textDim,
                            )
                        }
                        val shown = ref.key !in hidden
                        FlatChip(if (shown) "On Home" else "Hidden", selected = shown, onClick = { toggle(ref.key) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.tvFocus(), onClick = onClose) { Text("Done") }
        },
    )
}
