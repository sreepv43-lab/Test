package io.github.sreepv43.streamhub.ui.components

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.sreepv43.streamhub.container
import io.github.sreepv43.streamhub.download.DownloadLocation
import io.github.sreepv43.streamhub.download.StorageOption

/**
 * Lists every place a download can go: each mounted drive (internal, SD card, USB) and any folder
 * the user picked with the system folder picker. Refreshes when the app returns to the foreground
 * so newly plugged-in drives show up.
 */
@Composable
fun StorageChooser(selected: DownloadLocation?, onSelect: (DownloadLocation) -> Unit) {
    val context = LocalContext.current
    val storage = context.container.storage
    var refresh by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(emptyList<StorageOption>()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(refresh) { options = withContext(Dispatchers.IO) { storage.options() } }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { storage.persistTree(uri) }
                .onSuccess { onSelect(it); refresh++ }
                .onFailure { StreamActions.toast(context, "Could not get access to that folder") }
        }
    }
    val canPickFolder = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option.location == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(RoundedCornerShape(8.dp), scale = 1.02f)
                    .clickable { onSelect(option.location) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Icon(
                    when {
                        option.location.kind == DownloadLocation.Kind.TREE -> Icons.Default.Folder
                        option.removable -> Icons.Default.Usb
                        else -> Icons.Default.PhoneAndroid
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(option.location.label, style = MaterialTheme.typography.bodyLarge)
                    val detail = buildString {
                        if (option.location.kind == DownloadLocation.Kind.DIRECTORY) append("App folder")
                        else append("Chosen folder")
                        option.freeBytes?.let { append(" · ${Formatter.formatShortFileSize(context, it)} free") }
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (canPickFolder) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Text("  Choose a folder on any drive…")
            }
        } else {
            Text(
                "This device has no folder picker. Connected USB drives and SD cards are listed above " +
                    "(files go to the app's folder on that drive).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
