package com.minosuko.clouddrive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrashManagementScreen(drive: DriveProfile, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var entries by remember(drive.id) { mutableStateOf(emptyList<TrashEntry>()) }
    var loading by remember(drive.id) { mutableStateOf(true) }
    var busy by remember(drive.id) { mutableStateOf(false) }
    var error by remember(drive.id) { mutableStateOf<String?>(null) }
    var deleteEntry by remember { mutableStateOf<TrashEntry?>(null) }
    var emptyConfirmation by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { davClient(context, drive).listTrash() } }
            result.onSuccess {
                entries = it
                error = null
            }.onFailure { error = it.message ?: "Could not load Trash" }
            loading = false
        }
    }

    fun runAction(successMessage: String, action: (DavClient) -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { action(davClient(context, drive)) } }
            val refreshed = withContext(Dispatchers.IO) { runCatching { davClient(context, drive).listTrash() } }
            refreshed.onSuccess {
                entries = it
                error = null
            }.onFailure { error = it.message ?: "Could not refresh Trash" }
            busy = false
            snackbar.showSnackbar(
                if (result.isSuccess) successMessage
                else result.exceptionOrNull()?.message ?: "Trash operation failed",
            )
        }
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(drive.id) { reload() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to files") }
                Column(Modifier.weight(1f)) {
                    Text("Trash", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(drive.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = ::reload, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh Trash") }
                OutlinedButton(onClick = { emptyConfirmation = true }, enabled = entries.isNotEmpty() && !busy) {
                    Text("Empty")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> FilesEmptyState("Trash unavailable", error.orEmpty(), "Try again", ::reload)
                entries.isEmpty() -> FilesEmptyState("Trash is empty", "Deleted CloudDrive items will appear here.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = TrashEntry::id) { entry ->
                        ProductCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(entry.originalPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append("Deleted ${formatModified(entry.deletedAt)}")
                                            if (!entry.isDirectory) append("  |  ${formatBytes(entry.size)}")
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                OutlinedButton(
                                    onClick = { runAction("${entry.name} restored") { it.restoreTrash(listOf(entry.id)) } },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(5.dp))
                                    Text("Restore")
                                }
                                Button(onClick = { deleteEntry = entry }, enabled = !busy) {
                                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(5.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Delete permanently?") },
            text = { Text("${entry.name} cannot be recovered after this action.") },
            confirmButton = {
                Button(onClick = {
                    deleteEntry = null
                    runAction("${entry.name} permanently deleted") { it.deleteTrashItems(listOf(entry.id)) }
                }) { Text("Delete permanently") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteEntry = null }) { Text("Cancel") } },
        )
    }

    if (emptyConfirmation) {
        AlertDialog(
            onDismissRequest = { emptyConfirmation = false },
            title = { Text("Empty Trash?") },
            text = { Text("All ${entries.size} items will be permanently deleted and cannot be recovered.") },
            confirmButton = {
                Button(onClick = {
                    emptyConfirmation = false
                    runAction("Trash emptied") { it.emptyTrash() }
                }) { Text("Empty Trash") }
            },
            dismissButton = { OutlinedButton(onClick = { emptyConfirmation = false }) { Text("Cancel") } },
        )
    }
}
