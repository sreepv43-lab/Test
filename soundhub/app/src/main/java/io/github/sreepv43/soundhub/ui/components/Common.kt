package io.github.sreepv43.soundhub.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sreepv43.soundhub.audio.Atmos
import io.github.sreepv43.soundhub.audio.AudioInfo
import io.github.sreepv43.soundhub.slsk.TransferInfo
import io.github.sreepv43.soundhub.slsk.TransferStatus
import io.github.sreepv43.soundhub.ui.Accent
import io.github.sreepv43.soundhub.ui.AppColors
import io.github.sreepv43.soundhub.ui.AtmosColor
import io.github.sreepv43.soundhub.ui.HiResColor
import io.github.sreepv43.soundhub.ui.LocalTvShell
import io.github.sreepv43.soundhub.ui.LosslessColor
import java.util.Locale

@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The format label, coloured: blue for Atmos (✓ once the file itself confirmed it), gold for hi-res. */
@Composable
fun FormatBadge(info: AudioInfo) {
    val color = when {
        info.atmos != Atmos.NONE -> AtmosColor
        info.hiRes -> HiResColor
        info.codec.lossless -> LosslessColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        info.atmos == Atmos.VERIFIED -> "${info.label} ✓"
        info.hiRes && info.hiResGuessed && info.sampleRate == null -> "${info.label}?"
        else -> info.label
    }
    Badge(text, color)
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, key: Any? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val background = when {
        focused -> AppColors.text
        selected -> Accent
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape, key = key)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (focused || selected) Color.Black else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** A list row: lights up when selected with the remote. */
@Composable
fun ListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    key: Any? = null,
    pageDefault: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape, key = key, pageDefault = pageDefault)
            .clip(shape)
            .background(if (focused) AppColors.rowFocused else AppColors.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Text the remote can scroll to (plain text after the last button can't be reached otherwise). */
@Composable
fun ReadableText(text: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .tvFocus(shape)
            .clip(shape)
            .background(if (focused) AppColors.row else Color.Transparent)
            .focusable()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun TwoLines(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector? = null,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
    pageDefault: Boolean = false,
    onClick: () -> Unit,
) {
    val styled = modifier.widthIn(max = 460.dp).tvFocus(RoundedCornerShape(50), scale = 1.05f, pageDefault = pageDefault).testTag(text)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) Button(onClick = onClick, modifier = styled, content = content)
    else OutlinedButton(onClick = onClick, modifier = styled, content = content)
}

/**
 * The search box. It is a button until pressed, so moving past it with the remote never pops up
 * the on-screen keyboard; pressing it opens the keyboard, and the keyboard's Search runs the search.
 */
@Composable
fun SearchBar(query: String, hint: String, onSearch: (String) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var text by rememberSaveable(query) { mutableStateOf(query) }
    var hadFocus by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val bar = remember { FocusRequester() }
    var refocusBar by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, hint)
    }
    val voiceAvailable = remember {
        runCatching { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false)
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            onSearch(spoken.trim())
        }
    }
    fun submit() {
        keyboard?.hide()
        editing = false
        hadFocus = false
        refocusBar = true
        if (text.isNotBlank()) onSearch(text.trim())
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = RoundedCornerShape(10.dp)
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        // Moved away with the remote: back to the button.
                        if (!it.isFocused && hadFocus) {
                            editing = false
                            hadFocus = false
                        }
                    }
                    .testTag("search-field"),
            )
            LaunchedEffect(Unit) {
                runCatching { field.requestFocus() }
                keyboard?.show()
            }
        } else {
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .focusRequester(bar)
                    .onFocusChanged { focused = it.hasFocus }
                    .tvFocus(shape)
                    .clip(shape)
                    .background(if (focused) AppColors.rowFocused else AppColors.row)
                    .clickable { editing = true }
                    .padding(horizontal = 16.dp)
                    .testTag("search-bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    query.ifEmpty { hint },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (refocusBar) {
                LaunchedEffect(Unit) {
                    runCatching { bar.requestFocus() }
                    refocusBar = false
                }
            }
        }
        if (voiceAvailable) {
            ActionButton("Voice", Icons.Default.Mic, primary = false) {
                try {
                    voice.launch(voiceIntent)
                } catch (e: ActivityNotFoundException) {
                    toast(context, "Voice search isn't available on this device")
                }
            }
        }
    }
}

/** One-press repeats of earlier searches. */
@Composable
fun RecentSearches(recent: List<String>, onSearch: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.History,
            contentDescription = "Recent searches",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        LazyRow(
            modifier = Modifier.tvRow().tvEnterAt { recent.firstOrNull()?.let { "recent-$it" } }.testTag("recent"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(recent, key = { it }) { query ->
                Chip(query, selected = false, key = "recent-$query") { onSearch(query) }
            }
        }
    }
}

class DialogButton(val text: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * A dialog for the remote: the first button (or the one at [focusIndex]) is selected when it opens. Elements inside aren't
 * remembered by the page behind it, so closing it leaves the page's selection alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: List<DialogButton>,
    focusFirstButton: Boolean = true,
    focusIndex: Int = 0,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalTvShell provides null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppColors.panel,
                modifier = Modifier.widthIn(max = 600.dp).onFocusChanged { hasFocus = it.hasFocus },
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    content()
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        buttons.forEachIndexed { index, button ->
                            ActionButton(
                                button.text,
                                primary = button.primary,
                                modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
                                onClick = button.onClick,
                            )
                        }
                    }
                }
            }
        }
        val target = initialFocus ?: first.takeIf { focusFirstButton && buttons.isNotEmpty() }
        if (target != null) FocusWhenShown(target) { hasFocus }
    }
}

/**
 * Moves the selection into a dialog as it opens. Its window can need a frame or two before it
 * takes focus, so this tries again each frame until something in the dialog has it.
 */
@Composable
fun FocusWhenShown(requester: FocusRequester, hasFocus: () -> Boolean) {
    LaunchedEffect(requester) {
        repeat(FOCUS_TRIES) {
            withFrameNanos { }
            if (hasFocus()) return@LaunchedEffect
            runCatching { requester.requestFocus() }
        }
    }
}

private const val FOCUS_TRIES = 30

/** Asks for one line of text with the on-screen keyboard already open. */
@Composable
fun TextEntryDialog(
    title: String,
    initial: String,
    password: Boolean = false,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    TvDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = listOf(DialogButton("OK", primary = true) { onDone(text) }, DialogButton("Cancel", onClick = onDismiss)),
        initialFocus = field,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused },
        )
        LaunchedEffect(fieldFocused) { if (fieldFocused) keyboard?.show() }
    }
}

fun toast(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1L shl 20).toDouble())
    bytesPerSecond > 0 -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0)
    else -> ""
}

fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return ""
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** "Waiting in alice's queue (#3)", "45% · 1.2 MB/s", … */
fun transferText(transfer: TransferInfo): String = when (transfer.status) {
    TransferStatus.REQUESTING -> transfer.error?.let { "Trying again ($it)" } ?: "Asking ${transfer.username}…"
    TransferStatus.QUEUED -> "Waiting in ${transfer.username}'s queue" + (transfer.queuePosition?.let { " (#$it)" } ?: "")
    TransferStatus.CONNECTING -> "Connecting to ${transfer.username}…"
    TransferStatus.TRANSFERRING -> {
        val percent = if (transfer.size > 0) "${transfer.bytes * 100 / transfer.size}%" else formatSize(transfer.bytes)
        listOf(percent, formatSpeed(transfer.speed)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    TransferStatus.COMPLETED -> "Downloaded"
    TransferStatus.FAILED -> transfer.error ?: "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
}
