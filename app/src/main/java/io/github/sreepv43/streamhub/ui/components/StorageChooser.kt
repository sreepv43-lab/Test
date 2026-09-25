package io.github.sreepv43.streamhub.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    // Android TV often ships placeholder ("stub") apps that claim these screens but only show
    // "You don't have an app that can do this", so they don't count.
    val canPickFolder = remember { hasRealHandler(context, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
    var accessPrompt by remember { mutableStateOf<StorageOption?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option.location == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(RoundedCornerShape(8.dp), scale = 1.02f)
                    .clickable {
                        when {
                            option.needsAccess -> accessPrompt = option
                            option.problem != null -> StreamActions.toast(context, "Can't use this drive: ${option.problem}")
                            else -> onSelect(option.location)
                        }
                    }
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
                        when {
                            option.needsAccess -> append("Connected drive · select to allow access")
                            option.location.kind == DownloadLocation.Kind.TREE -> append("Chosen folder")
                            option.removable -> append("Drive folder")
                            else -> append("App folder")
                        }
                        option.freeBytes?.let { append(" · ${Formatter.formatShortFileSize(context, it)} free") }
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    option.problem?.let {
                        Text("Can't write: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (canPickFolder) {
            GlassButton(
                text = "Choose a folder on any drive…",
                icon = Icons.Default.CreateNewFolder,
                onClick = {
                    try {
                        pickFolder.launch(null)
                    } catch (e: ActivityNotFoundException) {
                        StreamActions.toast(context, "This device has no folder picker")
                    }
                },
            )
        } else {
            Text(
                "Connected USB drives and SD cards are listed above. Plug a drive in and it appears here " +
                    "within a few seconds of returning to this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    accessPrompt?.let { option ->
        DriveAccessDialog(option, onDismiss = { accessPrompt = null })
    }
}

/**
 * Explains and requests "All files access", which Android requires before an app may write to a
 * USB drive directly. Falls back to an adb command on TVs that hide that settings screen.
 */
@Composable
private fun DriveAccessDialog(option: StorageOption, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsIntent = remember { allFilesAccessIntent(context) }
    AlertDialog(
        containerColor = GlassDialogColor,
        shape = GlassDialogShape,
        onDismissRequest = onDismiss,
        title = { Text("Allow access to ${option.location.label.substringBefore(" › ")}") },
        text = {
            Text(
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                        "This Android version (older than 11) only lets apps write to USB drives through a " +
                            "folder picker, which this device doesn't have. Downloads can go to internal storage."
                    settingsIntent != null ->
                        "Android needs \"All files access\" before StreamHub can save downloads to a USB drive. " +
                            "On the next screen, turn it on for StreamHub, then press Back."
                    else ->
                        "This TV hides the \"All files access\" setting. Grant it once from a computer with adb:\n\n" +
                            "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow\n\n" +
                            "Then come back to this screen."
                },
            )
        },
        confirmButton = {
            if (settingsIntent != null) {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    onDismiss()
                    try {
                        context.startActivity(settingsIntent)
                    } catch (e: ActivityNotFoundException) {
                        StreamActions.toast(context, "Settings screen not available on this device")
                    }
                }) { Text("Open settings") }
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.tvFocus(), onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun allFilesAccessIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ).firstOrNull { hasRealHandler(context, it) }
}

private fun hasRealHandler(context: Context, intent: Intent): Boolean {
    val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
    return !info.activityInfo.packageName.contains("stub", ignoreCase = true)
}
