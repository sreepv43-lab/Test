package io.github.sreepv43.streamhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.sreepv43.streamhub.addon.AddonUrls
import io.github.sreepv43.streamhub.addon.InstalledAddon
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.ui.components.StreamActions
import io.github.sreepv43.streamhub.ui.components.DialogColor
import io.github.sreepv43.streamhub.ui.components.DialogShape
import io.github.sreepv43.streamhub.ui.components.FlatIconButton
import io.github.sreepv43.streamhub.ui.components.panel
import io.github.sreepv43.streamhub.ui.components.FlatButton
import io.github.sreepv43.streamhub.ui.components.flatTextFieldColors
import io.github.sreepv43.streamhub.ui.components.tvFocus
import kotlinx.coroutines.launch

@Composable
fun AddonsScreen(initialUrl: String?) {
    val context = LocalContext.current
    val repository = context.container.addons
    val addons by repository.addons.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toRemove by remember { mutableStateOf<InstalledAddon?>(null) }

    fun install() {
        if (url.isBlank() || installing) return
        installing = true
        error = null
        scope.launch {
            runCatching { repository.install(url) }
                .onSuccess {
                    StreamActions.toast(context, "Installed ${it.manifest.name}")
                    url = ""
                }
                .onFailure { error = "Could not install: ${it.message}" }
            installing = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Addons", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Paste the manifest URL of any Stremio addon (https://…/manifest.json or stremio://…). " +
                    "Addons are listed in priority order.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    colors = flatTextFieldColors(),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Addon URL") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> @Composable { Text(message) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { install() }),
                    modifier = Modifier.weight(1f).tvFocus(),
                )
                FlatButton(
                    text = if (installing) "Installing…" else "Install",
                    onClick = { install() },
                    enabled = !installing,
                    prominent = true,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        items(addons, key = { it.transportUrl }) { addon ->
            AddonRow(
                addon,
                onUp = { repository.move(addon, -1) },
                onDown = { repository.move(addon, 1) },
                onConfigure = { StreamActions.openUrl(context, AddonUrls.configureUrl(addon.transportUrl)) },
                onRemove = { toRemove = addon },
            )
        }
    }

    toRemove?.let { addon ->
        AlertDialog(
            containerColor = DialogColor,
            shape = DialogShape,
            onDismissRequest = { toRemove = null },
            title = { Text("Uninstall ${addon.manifest.name}?") },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    repository.remove(addon)
                    toRemove = null
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tvFocus(), onClick = { toRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddonRow(
    addon: InstalledAddon,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    val manifest = addon.manifest
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .panel(RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
            manifest.logo?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("${manifest.name}  v${manifest.version}", style = MaterialTheme.typography.titleMedium)
            manifest.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val provides = manifest.resources.map { it.name }.distinct().joinToString(", ")
            Text(
                "Provides: $provides · Types: ${manifest.types.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlatIconButton(Icons.Default.ArrowUpward, "Move up", onUp, Modifier.padding(start = 8.dp))
        FlatIconButton(Icons.Default.ArrowDownward, "Move down", onDown, Modifier.padding(start = 8.dp))
        if (manifest.behaviorHints.configurable) {
            FlatIconButton(Icons.Default.Settings, "Configure", onConfigure, Modifier.padding(start = 8.dp))
        }
        FlatIconButton(Icons.Default.Delete, "Uninstall", onRemove, Modifier.padding(start = 8.dp))
    }
}
