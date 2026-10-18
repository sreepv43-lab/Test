package io.github.sreepv43.streamhub.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.addon.Meta
import io.github.sreepv43.streamhub.addon.PlaybackTarget
import io.github.sreepv43.streamhub.addon.Stream
import io.github.sreepv43.streamhub.addon.StreamFacts
import io.github.sreepv43.streamhub.addon.StreamResolver
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.ui.AppColors
import io.github.sreepv43.streamhub.ui.components.DialogShape
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.FlatChip
import io.github.sreepv43.streamhub.ui.components.RankedStreams
import io.github.sreepv43.streamhub.ui.components.StreamHandlers
import io.github.sreepv43.streamhub.ui.components.StreamsState
import io.github.sreepv43.streamhub.ui.components.panel
import io.github.sreepv43.streamhub.ui.components.streamKey
import io.github.sreepv43.streamhub.ui.components.tvFocus
import io.github.sreepv43.streamhub.ui.components.tvRow
import kotlinx.coroutines.launch

/** How the source list is ordered. */
internal enum class SourceSort(val label: String) {
    Suggested("Suggested"),
    Smallest("Smallest file"),
    Seeders("Most seeders"),
}

/** One source card: the stream, what it says about itself and which addon found it. */
private class Source(val stream: Stream, val facts: StreamFacts, val addon: String, val torrent: Boolean)

/**
 * A movie (or one episode) in the Cinema interface: poster and details with Play suggested / Sources /
 * Download / My List, the cast, then every source as a card (quality, languages, audio, size, seeders)
 * that plays when selected, with separate Download and Details buttons, filters and sorting.
 * [streams] is null on a series' overview, whose seasons and episodes [extra] adds instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CinemaTitlePage(
    meta: Meta,
    subtitle: String?,
    streams: StreamsState?,
    handlers: StreamHandlers,
    resumeMs: Long?,
    inList: Boolean? = null,
    onToggleList: (() -> Unit)? = null,
    onTrailer: ((String) -> Unit)? = null,
    extra: LazyListScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val settings = context.container.settings
    val maxResolution by settings.maxResolution.flow.collectAsState()
    val passthrough by settings.surroundPassthrough.flow.collectAsState()
    val results = streams?.results.orEmpty()
    val ranked = streams?.let { RankedStreams.of(it.results, maxResolution, passthrough) }.orEmpty()
    val sources = remember(ranked, results) {
        val addonOf = HashMap<Stream, String>()
        results.forEach { r -> r.streams.forEach { addonOf.putIfAbsent(it, r.addon.manifest.name) } }
        // An addon listing the same stream twice would show two identical cards (and repeat a list key).
        ranked.distinctBy { streamKey(it) + addonOf[it] }
            .map { Source(it, StreamFacts.of(it), addonOf[it].orEmpty(), StreamResolver.isTorrent(it)) }
    }
    val best = remember(sources) {
        sources.firstOrNull { StreamResolver.resolve(it.stream).let { t -> t != null && t !is PlaybackTarget.External } }
    }

    var quality by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SourceSort.Suggested) }
    var details by remember { mutableStateOf<Source?>(null) }
    val shown = remember(sources, quality, language, sort) {
        val filtered = sources.filter { s ->
            (quality == null || s.facts.quality == quality) && (language == null || language in s.facts.languages)
        }
        when (sort) {
            SourceSort.Suggested -> filtered
            SourceSort.Smallest -> filtered.sortedBy { it.facts.sizeBytes ?: Long.MAX_VALUE }
            SourceSort.Seeders -> filtered.sortedByDescending { if (it.torrent) it.facts.seeders ?: -1 else -1 }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstSource = remember { FocusRequester() }
    val hasCredits = meta.director.isNotEmpty() || meta.cast.isNotEmpty()
    // List positions: header, (credits), sources title, filters, then the cards.
    val sourcesTitleIndex = if (hasCredits) 2 else 1

    details?.let { source ->
        SourceDetails(
            source,
            onPlay = { details = null; handlers.onPlay(source.stream) },
            onExternal = { details = null; handlers.onExternal(source.stream) },
            onClose = { details = null },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "header") {
            TitleHeader(
                meta = meta,
                subtitle = subtitle,
                best = best?.takeIf { streams != null },
                sourceCount = if (streams != null) sources.size else null,
                resumeMs = resumeMs,
                inList = inList,
                onPlay = { best?.let { handlers.onPlay(it.stream) } },
                onSources = {
                    scope.launch {
                        listState.animateScrollToItem(sourcesTitleIndex)
                        runCatching { firstSource.requestFocus() }
                    }
                },
                onDownload = { best?.let { handlers.onDownload(it.stream) } },
                onToggleList = onToggleList,
                onTrailer = onTrailer,
            )
        }
        if (hasCredits) {
            item(key = "credits") { Credits(meta) }
        }
        if (streams != null) {
            item(key = "sources-title") {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp)) {
                    Text("Available sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Select a source to play it. Download and Details are separate buttons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textDim,
                    )
                }
            }
            item(key = "source-filters") {
                val qualities = remember(sources) { QUALITY_ORDER.filter { q -> sources.any { it.facts.quality == q } } }
                val languages = remember(sources) {
                    sources.flatMap { it.facts.languages }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.map { it.key }.take(MAX_LANGUAGE_CHIPS)
                }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (qualities.size > 1) {
                        ChipRow {
                            FlatChip("All quality", selected = quality == null, onClick = { quality = null })
                            qualities.forEach { q -> FlatChip(q, selected = quality == q, onClick = { quality = q }) }
                        }
                    }
                    if (languages.isNotEmpty()) {
                        ChipRow {
                            FlatChip("All languages", selected = language == null, onClick = { language = null })
                            languages.forEach { l -> FlatChip(l, selected = language == l, onClick = { language = l }) }
                        }
                    }
                    if (sources.size > 1) {
                        ChipRow {
                            SourceSort.entries.forEach { s -> FlatChip(s.label, selected = sort == s, onClick = { sort = s }) }
                        }
                    }
                }
            }
            if (streams.noAddons) {
                item(key = "no-addons") {
                    Text(
                        "No installed addon provides streams for this title. Install a stream addon in Addons.",
                        color = AppColors.textDim,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else if (shown.isEmpty() && sources.isNotEmpty()) {
                item(key = "none-match") {
                    Text("No source matches these filters.", color = AppColors.textDim, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            items(shown, key = { "src-" + streamKey(it.stream) + it.addon }) { source ->
                SourceCard(
                    source,
                    suggested = source === best,
                    modifier = if (source === shown.first()) Modifier.focusRequester(firstSource) else Modifier,
                    onPlay = { handlers.onPlay(source.stream) },
                    onDownload = { handlers.onDownload(source.stream) },
                    onDetails = { details = source },
                )
            }
            if (streams.pending > 0) {
                item(key = "pending") {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.accent)
                        Text("  Asking ${streams.pending} addon(s) for sources…", color = AppColors.textDim)
                    }
                }
            }
            val failed = results.filter { it.error != null }
            if (failed.isNotEmpty()) {
                item(key = "failed") {
                    Text(
                        "No answer from " + failed.joinToString { it.addon.manifest.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textDim,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            if (sources.isNotEmpty()) {
                item(key = "sources-note") {
                    Text(
                        "Seeders don't guarantee speed. Quality, languages and audio are as the addon describes them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textDim,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
        extra()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.tvRow(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun TitleHeader(
    meta: Meta,
    subtitle: String?,
    best: Source?,
    sourceCount: Int?,
    resumeMs: Long?,
    inList: Boolean?,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onDownload: () -> Unit,
    onToggleList: (() -> Unit)?,
    onTrailer: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val pageColor = AppColors.background
    Box(Modifier.fillMaxWidth()) {
        meta.background?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        // A quiet backdrop: the page colour over most of it, fading out at the bottom.
                        drawRect(Brush.horizontalGradient(0f to pageColor, 0.5f to pageColor.copy(alpha = 0.88f), 1f to pageColor.copy(alpha = 0.55f)))
                        drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to pageColor))
                    },
            )
        }
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp)) {
            Box(
                Modifier
                    .width(180.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.panelRaised),
            ) {
                meta.poster?.let {
                    AsyncImage(model = it, contentDescription = meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(start = 28.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val eyebrow = listOfNotNull(
                    meta.genres.take(2).joinToString(" / ").ifEmpty { null },
                    if (meta.type == "series") "Series" else "Movie",
                ).joinToString(" · ").uppercase()
                Text(eyebrow, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = AppColors.accent)
                Text(
                    meta.name,
                    fontFamily = FontFamily.Serif,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.text) }
                val facts = listOfNotNull(
                    meta.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                    meta.releaseInfo?.takeIf { it.isNotBlank() },
                    meta.runtime?.takeIf { it.isNotBlank() },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("    "), style = MaterialTheme.typography.bodyMedium)
                meta.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (meta.director.isNotEmpty()) {
                    Row {
                        Text("Directed by ", style = MaterialTheme.typography.bodyMedium)
                        Text(meta.director.take(2).joinToString(", "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                    }
                }
                Row(Modifier.tvRow().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (best != null) {
                        FlatButton(if (resumeMs != null) "Resume" else "Play suggested", onClick = onPlay, icon = Icons.Default.PlayArrow, prominent = true)
                    }
                    if (sourceCount != null) {
                        FlatButton(if (sourceCount > 0) "Sources ($sourceCount)" else "Sources", onClick = onSources, icon = Icons.AutoMirrored.Filled.List)
                    }
                    if (best != null) {
                        FlatButton("Download", onClick = onDownload, icon = Icons.Default.Download)
                    }
                    if (inList != null && onToggleList != null) {
                        FlatButton(
                            if (inList) "In My List" else "My List",
                            onClick = onToggleList,
                            icon = if (inList) Icons.Default.Check else Icons.Default.Add,
                        )
                    }
                    val trailer = meta.trailers.firstOrNull { it.type == null || it.type == "Trailer" }?.source
                    if (trailer != null && onTrailer != null) {
                        FlatButton("Trailer", onClick = { onTrailer(trailer) }, icon = Icons.Default.Movie)
                    }
                }
                val caption = buildList {
                    best?.facts?.let { f ->
                        f.quality?.let(::add)
                        f.audio?.let(::add)
                        f.sizeBytes?.let { add(Formatter.formatShortFileSize(context, it)) }
                        add("Suggested source")
                    }
                    resumeMs?.let { add("Resumes from ${clock(it)}") }
                }
                if (caption.isNotEmpty()) {
                    Text(caption.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = AppColors.textDim)
                }
            }
        }
    }
}

/** Director and cast as small cards (the cast with their initials). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Credits(meta: Meta) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Text("Cast & creators", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shape = RoundedCornerShape(12.dp)
            meta.director.take(2).forEach { name ->
                Column(Modifier.panel(shape).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Director", style = MaterialTheme.typography.bodySmall, color = AppColors.textDim)
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                }
            }
            meta.cast.take(MAX_CAST).forEach { name ->
                Row(Modifier.panel(shape).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(AppColors.panelRaised, CircleShape), contentAlignment = Alignment.Center) {
                        Text(initials(name), fontSize = 12.sp, fontFamily = FontFamily.Serif)
                    }
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: Source,
    suggested: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDetails: () -> Unit,
) {
    val context = LocalContext.current
    val facts = source.facts
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier
                .weight(1f)
                .heightIn(min = 84.dp)
                .tvFocus(shape, scale = 1.02f, key = streamKey(source.stream))
                .clip(shape)
                .panel(shape)
                .then(if (suggested) Modifier.border(1.dp, AppColors.accent, shape) else Modifier)
                .clickable(onClick = onPlay)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 58.dp, height = 40.dp).background(AppColors.panelRaised, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(facts.quality ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        facts.headline.ifEmpty { source.stream.name?.lineSequence()?.firstOrNull() ?: "Stream" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (suggested) {
                        Text(
                            "Suggested",
                            fontSize = 11.sp,
                            color = AppColors.accent,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .background(AppColors.panelRaised, RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                if (facts.languages.isNotEmpty()) {
                    Text(facts.languages.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = AppColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(facts.audio, facts.codec, source.addon.ifEmpty { null }).joinToString(" · ").ifEmpty { "No details from the addon" },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                facts.sizeBytes?.let { Text(Formatter.formatShortFileSize(context, it), fontWeight = FontWeight.Bold) }
                Text(
                    when {
                        !source.torrent -> "Direct link"
                        facts.seeders != null -> "${facts.seeders} seeders"
                        else -> "Availability unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.torrent && facts.seeders != null) Color(0xFF92D5B5) else AppColors.textDim,
                )
                Text("▶ Play", style = MaterialTheme.typography.bodySmall, color = AppColors.accent)
            }
        }
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatButton("Download", onClick = onDownload, icon = Icons.Default.Download, compact = true)
            FlatButton("Details", onClick = onDetails, icon = Icons.Default.Info, compact = true)
        }
    }
}

/** Everything the addon said about a source, plus Play and the external player. */
@Composable
private fun SourceDetails(source: Source, onPlay: () -> Unit, onExternal: () -> Unit, onClose: () -> Unit) {
    val stream = source.stream
    AlertDialog(
        containerColor = AppColors.panel,
        shape = DialogShape,
        onDismissRequest = onClose,
        title = { Text(source.facts.headline.ifEmpty { "Source details" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(
                    "From" to source.addon.ifEmpty { null },
                    "Type" to if (source.torrent) "Torrent (streams while it downloads)" else "Direct link",
                    "Languages" to source.facts.languages.joinToString(", ").ifEmpty { null },
                    "Audio" to source.facts.audio,
                    "Video" to source.facts.codec,
                    "File" to stream.behaviorHints.filename,
                    "Name" to stream.name,
                    "Description" to stream.details,
                ).filter { it.second != null }.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.textDim)
                        Text(value!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(modifier = Modifier.tvFocus(), onClick = onExternal) { Text("External player") }
                TextButton(modifier = Modifier.tvFocus(), onClick = onPlay) { Text("Play") }
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.tvFocus(), onClick = onClose) { Text("Close") }
        },
    )
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

private fun clock(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

private val QUALITY_ORDER = listOf("4K", "1440p", "1080p", "720p", "SD")
private const val MAX_LANGUAGE_CHIPS = 8
private const val MAX_CAST = 6
